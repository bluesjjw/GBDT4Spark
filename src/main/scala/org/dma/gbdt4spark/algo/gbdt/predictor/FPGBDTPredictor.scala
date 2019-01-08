package org.dma.gbdt4spark.algo.gbdt.predictor

import org.apache.hadoop.fs.Path
import org.apache.spark.{SparkConf, SparkContext, SparkEnv}
import org.apache.spark.ml.linalg.{Vector, Vectors}
import org.apache.spark.rdd.RDD
import org.dma.gbdt4spark.algo.gbdt.tree.{GBTNode, GBTTree}
import org.dma.gbdt4spark.common.Global.Conf._
import org.dma.gbdt4spark.objective.ObjectiveFactory
import org.dma.gbdt4spark.util.DataLoader
import org.dma.gbdt4spark.data.Instance

class FPGBDTPredictor extends Serializable {

  var forest: Seq[GBTTree] = _

  def loadModel(sc: SparkContext, modelPath: String): Unit = {
    forest = sc.objectFile[Seq[GBTTree]](modelPath).collect().head
    println(s"Reading model from $modelPath")
  }

  def predict(predictor: FPGBDTPredictor, instances: RDD[Vector]): RDD[Array[Float]] = {
    val bcPredictor = instances.sparkContext.broadcast(predictor)
    instances.map(bcPredictor.value.predict)
  }

  def predict(implicit sc: SparkContext, validPath: String, predPath: String): Unit = {
    println(s"Predicting dataset $validPath")
    val instances: RDD[Instance] = DataLoader.loadLibsvmDP(validPath, forest.head.getParam.numFeature).cache()
    val labels = instances.map(_.label.toFloat).collect()
    val preds = predict(this, instances.map(_.feature)).collect().flatten
    instances.unpersist()

    println(s"Evaluating predictions")
    val evalMetrics = ObjectiveFactory.getEvalMetrics(
      sc.getConf.get(ML_EVAL_METRIC, DEFAULT_ML_EVAL_METRIC)
        .split(",").map(_.trim).filter(_.nonEmpty)
    )
    println(evalMetrics.map(evalMetric => {
      val kind = evalMetric.getKind
      val metric = evalMetric.eval(preds, labels)
      s"$kind[$metric]"
    }).mkString(", "))

    val path = new Path(predPath)
    val fs = path.getFileSystem(sc.hadoopConfiguration)
    if (fs.exists(path)) fs.delete(path, true)

    sc.makeRDD(labels.zip(preds)).saveAsTextFile(predPath)
    println(s"Writing predictions to $predPath")
  }

  def predict(instance: Vector): Array[Float] = {

    val param = forest.head.getParam
    val preds = Array.ofDim[Float](if (param.numClass == 2) 1 else param.numClass)
    forest.foreach(tree => {
      var node = tree.getRoot
      while (!node.isLeaf) {
        if (node.getSplitEntry.flowTo(instance) == 0)
          node = node.getLeftChild.asInstanceOf[GBTNode]
        else
          node = node.getRightChild.asInstanceOf[GBTNode]
      }
      if (param.numClass == 2) {
        preds(0) += node.getWeight * param.learningRate
      } else {
        val weights = node.getWeights
        for (k <- 0 until param.numClass)
          preds(k) += weights(k)
      }
    })
    preds
  }

  def probToClass(preds: Array[Float]): Int = {
    preds.length match {
      case 1 => if (preds.head > 0.5) 1 else 0
      case _ => preds.zipWithIndex.maxBy(_._1)._2 + 1
    }
  }

  def predict(features: Array[Double]): Int = {
    val instance = Vectors.dense(features)
    val preds = predict(instance)
    probToClass(preds)
  }

  def predict(dim: Int, indices: Array[Int], values: Array[Double]): Int = {
    val instance = Vectors.sparse(dim, indices, values)
    val preds = predict(instance)
    probToClass(preds)
  }

}

object FPGBDTPredictor {

  def predict(predictor: FPGBDTPredictor, instances: RDD[Vector]): RDD[Array[Float]] = {
    val bcPredictor = instances.sparkContext.broadcast(predictor)
    instances.map(bcPredictor.value.predict)
  }

  def main(args: Array[String]): Unit = {
    @transient val conf = new SparkConf()
    @transient implicit val sc = SparkContext.getOrCreate(conf)

    val modelPath = conf.get(ML_MODEL_PATH)
    val validPath = conf.get(ML_VALID_DATA_PATH)
    val predPath = conf.get(ML_PREDICT_DATA_PATH)

    val predictor = new FPGBDTPredictor
    predictor.loadModel(sc, modelPath)
    predictor.predict(sc, validPath, predPath)

    sc.stop
  }
}