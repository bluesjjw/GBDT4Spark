package org.dma.gbdt4spark.util

import it.unimi.dsi.fastutil.doubles.DoubleArrayList
import it.unimi.dsi.fastutil.ints.IntArrayList
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.rdd.RDD
import org.apache.spark.storage.StorageLevel
import org.apache.spark.{SparkConf, SparkContext}
import org.dma.gbdt4spark.algo.gbdt.GBDTTrainer
import org.dma.gbdt4spark.algo.gbdt.metadata.FeatureInfo
import org.dma.gbdt4spark.common.Global.Conf._
import org.dma.gbdt4spark.data.{FeatureRow, Instance}
import org.dma.gbdt4spark.logging.LoggerFactory
import org.dma.gbdt4spark.sketch.HeapQuantileSketch
import org.dma.gbdt4spark.tree.param.GBDTParam

import scala.collection.mutable.ArrayBuffer

object Transposer {
  private val LOG = LoggerFactory.getLogger(Transposer.getClass)

  private val param = new GBDTParam

  type FR = (Array[Int], Array[Double])

  def main(args: Array[String]): Unit = {
    val conf = new SparkConf().setAppName("GBDT")
    implicit val sc = SparkContext.getOrCreate(conf)

    param.numClass = conf.getInt(ML_NUM_CLASS, DEFAULT_ML_NUM_CLASS)
    param.numFeature = conf.get(ML_NUM_FEATURE).toInt
    param.featSampleRatio = conf.getDouble(ML_FEATURE_SAMPLE_RATIO, DEFAULT_ML_FEATURE_SAMPLE_RATIO).toFloat
    param.numWorker = conf.get(ML_NUM_WORKER).toInt
    param.numThread = conf.getInt(ML_NUM_THREAD, DEFAULT_ML_NUM_THREAD)
    param.lossFunc = conf.get(ML_LOSS_FUNCTION)
    param.evalMetrics = conf.get(ML_EVAL_METRIC, DEFAULT_ML_EVAL_METRIC).split(",").map(_.trim).filter(_.nonEmpty)
    param.learningRate = conf.getDouble(ML_LEARN_RATE, DEFAULT_ML_LEARN_RATE).toFloat
    param.histSubtraction = conf.getBoolean(ML_GBDT_HIST_SUBTRACTION, DEFAULT_ML_GBDT_HIST_SUBTRACTION)
    param.lighterChildFirst = conf.getBoolean(ML_GBDT_LIGHTER_CHILD_FIRST, DEFAULT_ML_GBDT_LIGHTER_CHILD_FIRST)
    param.fullHessian = conf.getBoolean(ML_GBDT_FULL_HESSIAN, DEFAULT_ML_GBDT_FULL_HESSIAN)
    param.numSplit = conf.getInt(ML_GBDT_SPLIT_NUM, DEFAULT_ML_GBDT_SPLIT_NUM)
    param.numTree = conf.getInt(ML_GBDT_TREE_NUM, DEFAULT_ML_GBDT_TREE_NUM)
    param.maxDepth = conf.getInt(ML_GBDT_MAX_DEPTH, DEFAULT_ML_GBDT_MAX_DEPTH)
    val maxNodeNum = Maths.pow(2, param.maxDepth + 1) - 1
    param.maxNodeNum = conf.getInt(ML_GBDT_MAX_NODE_NUM, maxNodeNum) min maxNodeNum
    param.minChildWeight = conf.getDouble(ML_GBDT_MIN_CHILD_WEIGHT, DEFAULT_ML_GBDT_MIN_CHILD_WEIGHT).toFloat
    param.minSplitGain = conf.getDouble(ML_GBDT_MIN_SPLIT_GAIN, DEFAULT_ML_GBDT_MIN_SPLIT_GAIN).toFloat
    param.regAlpha = conf.getDouble(ML_GBDT_REG_ALPHA, DEFAULT_ML_GBDT_REG_ALPHA).toFloat
    param.regLambda = conf.getDouble(ML_GBDT_REG_LAMBDA, DEFAULT_ML_GBDT_REG_LAMBDA).toFloat max 1.0f
    param.maxLeafWeight = conf.getDouble(ML_GBDT_MAX_LEAF_WEIGHT, DEFAULT_ML_GBDT_MAX_LEAF_WEIGHT).toFloat

    val input = conf.get(ML_TRAIN_DATA_PATH)
    val validRatio = conf.getDouble(ML_VALID_DATA_RATIO, DEFAULT_ML_VALID_DATA_RATIO)
    val oriTrainData = loadData(input, validRatio)

    val numKV = oriTrainData.map(ins => ins.feature.numActives).reduce(_+_)

    val res = transpose(oriTrainData)

    val numKV2 = res._1.map {
      case Some(row) => row.size
      case None => 0
    }.reduce(_+_)
    LOG.info(s"#KV pairs: $numKV vs. $numKV2")
  }

  def loadData(input: String, validRatio: Double)(implicit sc: SparkContext): RDD[Instance] = {
    val loadStart = System.currentTimeMillis()
    // 1. load original data, split into train data and valid data
    val dim = param.numFeature
    val fullDataset = sc.textFile(input)
      .map(_.trim)
      .filter(_.nonEmpty)
      .map(line => DataLoader.parseLibsvm(line, dim))
      .persist(StorageLevel.MEMORY_AND_DISK)
    val dataset = fullDataset.randomSplit(Array[Double](1.0 - validRatio, validRatio))
    val trainData = dataset(0).persist(StorageLevel.MEMORY_AND_DISK)
    val validData = dataset(1).persist(StorageLevel.MEMORY_AND_DISK)
    val numTranData = trainData.count().toInt
    val numValidData = validData.count().toInt
    fullDataset.unpersist()
    LOG.info(s"Load data cost ${System.currentTimeMillis() - loadStart} ms, " +
      s"$numTranData train data, $numValidData valid data")
    trainData
  }

  def transpose(dpData: RDD[Instance])(implicit sc: SparkContext):
        (RDD[Option[FeatureRow]], Broadcast[FeatureInfo], Broadcast[Array[Float]]) = {
    val transposeStart = System.currentTimeMillis()
    val numFeature = param.numFeature

    val oriNumPart = dpData.getNumPartitions
    val partNumInstance = new Array[Int](oriNumPart)
    dpData.mapPartitionsWithIndex((partId, iterator) =>
      Seq((partId, iterator.size)).iterator)
      .collect()
      .foreach(part => partNumInstance(part._1) = part._2)
    val partInsIdOffset = new Array[Int](oriNumPart)
    for (i <- 1 until oriNumPart)
      partInsIdOffset(i) += partInsIdOffset(i - 1) + partNumInstance(i - 1)
    val bcNumTrainData = sc.broadcast(partNumInstance.sum)
    val bcPartInsIdOffset = sc.broadcast(partInsIdOffset)

    // labels
    val labelsRdd = dpData.mapPartitionsWithIndex((partId, iterator) => {
      val offsets = bcPartInsIdOffset.value
      val partSize = if (partId + 1 == offsets.length) {
        bcNumTrainData.value - offsets(partId)
      } else {
        offsets(partId + 1) - offsets(partId)
      }
      val labels = new Array[Float](partSize)
      var count = 0
      while (iterator.hasNext) {
        labels(count) = iterator.next().label.toFloat
        count += 1
      }
      require(count == partSize)
      Seq((offsets(partId), labels)).iterator
    }, preservesPartitioning = true)

    val labels = new Array[Float](bcNumTrainData.value)
    labelsRdd.collect().foreach(part => {
      val offset = part._1
      val partLabels = part._2
      for (i <- partLabels.indices)
        labels(offset + i) = partLabels(i)
    })
    dpData.unpersist()
    GBDTTrainer.ensureLabel(labels, param.numClass)

    val bcLabels = sc.broadcast(labels)
    LOG.info(s"Collect labels cost ${System.currentTimeMillis() - transposeStart} ms")

    // 1. transpose to FP dataset
    val toFPStart = System.currentTimeMillis()
    val evenPartitioner = new EvenPartitioner(param.numFeature, param.numWorker)
    val bcFeatureEdges = sc.broadcast(evenPartitioner.partitionEdges())
    val fpData = dpData.mapPartitionsWithIndex((partId, iterator) => {
      val startTime = System.currentTimeMillis()
      val insIdLists = new Array[IntArrayList](numFeature)
      val valueLists = new Array[DoubleArrayList](numFeature)
      for (fid <- 0 until numFeature) {
        insIdLists(fid) = new IntArrayList()
        valueLists(fid) = new DoubleArrayList()
      }
      var insId = bcPartInsIdOffset.value(partId)
      while (iterator.hasNext) {
        iterator.next().feature.foreachActive((fid, value) => {
          insIdLists(fid).add(insId)
          valueLists(fid).add(value)
        })
        insId += 1
      }
      val featRows = new Array[(Int, FR)](numFeature)
      for (fid <- 0 until numFeature) {
        if (insIdLists(fid).size() > 0) {
          val indices = insIdLists(fid).toIntArray(null)
          val values = valueLists(fid).toDoubleArray(null)
          featRows(fid) = (fid, (indices, values))
        } else {
          featRows(fid) = (fid, null)
        }
      }
      LOG.info(s"Local transpose cost ${System.currentTimeMillis() - startTime} ms")
      featRows.iterator
    }).repartitionAndSortWithinPartitions(evenPartitioner)
      .mapPartitionsWithIndex((partId, iterator) => {
        val startTime = System.currentTimeMillis()
        val featLo = bcFeatureEdges.value(partId)
        val featHi = bcFeatureEdges.value(partId + 1)
        val featureRows = new ArrayBuffer[Option[FR]](featHi - featLo)
        val partFeatRows = new collection.mutable.ArrayBuffer[FR]()
        var curFid = featLo
        while (iterator.hasNext) {
          val entry = iterator.next()
          val fid = entry._1
          val partRow = entry._2
          require(featLo <= fid && fid < featHi)
          if (fid != curFid) {
            featureRows += compact(partFeatRows)
            partFeatRows.clear()
            curFid = fid
            partFeatRows += partRow
          } else if (!iterator.hasNext) {
            partFeatRows += partRow
            featureRows += compact(partFeatRows)
          } else {
            partFeatRows += partRow
          }
        }
        LOG.info(s"Merge feature rows cost ${System.currentTimeMillis() - startTime} ms")
        featureRows.iterator
      }).persist(StorageLevel.MEMORY_AND_DISK)
    require(fpData.count() == numFeature)
    LOG.info(s"To FP cost ${System.currentTimeMillis() - toFPStart} ms")
    // 2. splits
    val getSplitStart = System.currentTimeMillis()
    val bcParam = sc.broadcast(param)
    val splits = new Array[Array[Float]](numFeature)
    fpData.mapPartitionsWithIndex((partId, iterator) => {
      val startTime = System.currentTimeMillis()
      var curFid = bcFeatureEdges.value(partId)
      val numSplit = bcParam.value.numSplit
      val splits = collection.mutable.ArrayBuffer[(Int, Array[Float])]()
      while (iterator.hasNext) {
        iterator.next() match {
          case Some(row) => {
            val sketch = new HeapQuantileSketch()
            for (v <- row._2)
              sketch.update(v.toFloat)
            splits += ((curFid, sketch.getQuantiles(numSplit)))
          }
          case None =>
        }
        curFid += 1
      }
      LOG.info(s"Create sketch and get split cost ${System.currentTimeMillis() - startTime} ms")
      splits.iterator
    }).collect()
      .foreach(s => splits(s._1) = s._2)
    val bcFeatureInfo = sc.broadcast(FeatureInfo(numFeature, splits))
    LOG.info(s"Get split cost ${System.currentTimeMillis() - getSplitStart} ms")
    // 3. truncate
    val truncateStart = System.currentTimeMillis()
    val res = fpData.mapPartitionsWithIndex((partId, iterator) => {
      val startTime = System.currentTimeMillis()
      val featLo = bcFeatureEdges.value(partId)
      val featHi = bcFeatureEdges.value(partId + 1)
      var curFid = featLo
      val featureRows = new Array[Option[FeatureRow]](featHi - featLo)
      val splits = bcFeatureInfo.value.splits
      while (iterator.hasNext) {
        featureRows(curFid - featLo) = iterator.next() match {
          case Some(row) => {
            val indices = row._1
            val bins = row._2.map(v => Maths.indexOf(splits(curFid), v.toFloat))
            Option(FeatureRow(indices, bins))
          }
          case None => Option.empty
        }
        curFid += 1
      }
      require(curFid == featHi, s"cur fid = $curFid, should be $featHi")
      LOG.info(s"Truncate cost ${System.currentTimeMillis() - startTime} ms")
      featureRows.iterator
    }).persist(StorageLevel.MEMORY_AND_DISK)
    LOG.info(s"Truncate cost ${System.currentTimeMillis() - truncateStart} ms")
    fpData.unpersist()

    LOG.info(s"Transpose train data cost ${System.currentTimeMillis() - transposeStart} ms, " +
      s"feature edges: [${bcFeatureEdges.value.mkString(", ")}]")
    (res, bcFeatureInfo, bcLabels)
  }

  def compact(rows: Seq[FR]): Option[FR] = {
    val nonEmptyRows = rows.filter(r => r != null && r._1 != null && r._1.length != 0)
    if (nonEmptyRows.isEmpty) {
      Option.empty
    } else {
      val size = nonEmptyRows.map(_._1.length).sum
      val indices = new Array[Int](size)
      val values = new Array[Double](size)
      var offset = 0
      nonEmptyRows.sortWith((row1, row2) => row1._1(0) < row2._1(0))
        .foreach(row => {
          val partSize = row._1.length
          Array.copy(row._1, 0, indices, offset, partSize)
          Array.copy(row._2, 0, values, offset, partSize)
          offset += partSize
        })
      Option((indices, values))
    }
  }

}