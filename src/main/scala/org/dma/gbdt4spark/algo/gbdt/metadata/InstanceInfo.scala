package org.dma.gbdt4spark.algo.gbdt.metadata

import org.dma.gbdt4spark.algo.gbdt.dataset.Dataset
import org.dma.gbdt4spark.algo.gbdt.histogram.{BinaryGradPair, GradPair, MultiGradPair}
import org.dma.gbdt4spark.objective.loss.{BinaryLoss, Loss, MultiLoss}
import org.dma.gbdt4spark.tree.param.GBDTParam
import org.dma.gbdt4spark.tree.split.SplitEntry
import org.dma.gbdt4spark.util.{Maths, RangeBitSet}

object InstanceInfo {

  def apply(param: GBDTParam, numData: Int): InstanceInfo = {
    val size = if (param.numClass == 2) numData else param.numClass * numData
    val predictions = new Array[Float](size)
    val weights = Array.fill[Float](numData)(1.0f)
    val gradients = new Array[Double](size)
    val hessians = new Array[Double](size)
    val maxNodeNum = Maths.pow(2, param.maxDepth + 1) - 1
    val nodePosStart = new Array[Int](maxNodeNum)
    val nodePosEnd = new Array[Int](maxNodeNum)
    val nodeToIns = new Array[Int](numData)
    val insPos = new Array[Int](numData)
    InstanceInfo(predictions, weights, gradients, hessians, nodePosStart, nodePosEnd, nodeToIns, insPos)
  }
}

// TODO: gradPairs may exceed MAX_INT, use two-level indexing
case class InstanceInfo(predictions: Array[Float], weights: Array[Float], gradients: Array[Double], hessians: Array[Double],
                        nodePosStart: Array[Int], nodePosEnd: Array[Int], nodeToIns: Array[Int], insPos: Array[Int]) {

  def resetPosInfo(): Unit = {
    val num = weights.length
    nodePosStart(0) = 0
    nodePosEnd(0) = num - 1
    for (i <- 0 until num) {
      nodeToIns(i) = i
      insPos(i) = i
    }
  }

  def calcGradPairs(labels: Array[Float], loss: Loss, param: GBDTParam): GradPair = {
    val numIns = labels.length
    val numClass = param.numClass
    if (numClass == 2) {
      // binary classification
      val binaryLoss = loss.asInstanceOf[BinaryLoss]
      var sumGrad = 0.0
      var sumHess = 0.0
      for (insId <- 0 until numIns) {
        val grad = binaryLoss.firOrderGrad(predictions(insId), labels(insId))
        val hess = binaryLoss.secOrderGrad(predictions(insId), labels(insId), grad)
        gradients(insId) = grad
        hessians(insId) = hess
        sumGrad += grad
        sumHess += hess
      }
      new BinaryGradPair(sumGrad, sumHess)
    } else if (!param.fullHessian) {
      // multi-label classification, assume hessian matrix is diagonal
      val multiLoss = loss.asInstanceOf[MultiLoss]
      val preds = new Array[Float](numClass)
      val sumGrad = new Array[Double](numClass)
      val sumHess = new Array[Double](numClass)
      for (insId <- 0 until numIns) {
        Array.copy(predictions, insId * numClass, preds, 0, numClass)
        val grad = multiLoss.firOrderGrad(preds, labels(insId))
        val hess = multiLoss.secOrderGradDiag(preds, labels(insId), grad)
        for (k <- 0 until numClass) {
          gradients(insId * numClass + k) = grad(k)
          hessians(insId * numClass + k) = hess(k)
          sumGrad(k) += grad(k)
          sumHess(k) += hess(k)
        }
      }
      new MultiGradPair(sumGrad, sumHess)
    } else {
      // multi-label classification, represent hessian matrix as lower triangular matrix
      throw new UnsupportedOperationException("Full hessian not supported")
    }
  }

  def getSplitResult(nid: Int, fidInWorker: Int, splitEntry: SplitEntry, splits: Array[Float],
                     dataset: Dataset[Int, Int]): RangeBitSet = {
    val res = new RangeBitSet(nodePosStart(nid), nodePosEnd(nid))
    for (posId <- nodePosStart(nid) to nodePosEnd(nid)) {
      val insId = nodeToIns(posId)
      val binId = dataset.get(insId, fidInWorker)
      val flowTo = if (binId >= 0) {
        splitEntry.flowTo(splits(binId))
      } else {
        splitEntry.defaultTo()
      }
      if (flowTo == 1)
        res.set(posId)
    }
    res
  }

  def updatePos(nid: Int, splitResult: RangeBitSet): Unit = {
    val nodeStart = nodePosStart(nid)
    val nodeEnd = nodePosEnd(nid)
    var left = nodeStart
    var right = nodeEnd
    while (left < right) {
      while (left < right && !splitResult.get(left)) left += 1
      while (left < right && splitResult.get(right)) right -= 1
      if (left < right) {
        val leftInsId = nodeToIns(left)
        val rightInsId = nodeToIns(right)
        nodeToIns(left) = rightInsId
        nodeToIns(right) = leftInsId
        insPos(leftInsId) = right
        insPos(rightInsId) = left
        left += 1
        right -= 1
      }
    }
    // find the cut position
    val cutPos = if (left == right) {
      if (splitResult.get(left)) left - 1
      else left
    } else {
      right
    }
    nodePosStart(2 * nid + 1) = nodeStart
    nodePosEnd(2 * nid + 1) = cutPos
    nodePosStart(2 * nid + 2) = cutPos + 1
    nodePosEnd(2 * nid + 2) = nodeEnd
  }

  def updatePreds(nid: Int, update: Float, learningRate: Float): Unit = {
    val update_ = update * learningRate
    val nodeStart = nodePosStart(nid)
    val nodeEnd = nodePosEnd(nid)
    for (i <- nodeStart to nodeEnd) {
      val insId = nodeToIns(i)
      predictions(insId) += update_
    }
  }

  def updatePreds(nid: Int, update: Array[Float], learningRate: Float): Unit = {
    val numClass = update.length
    val update_ = update.map(_ * learningRate)
    val nodeStart = nodePosStart(nid)
    val nodeEnd = nodePosEnd(nid)
    for (i <- nodeStart to nodeEnd) {
      val insId = nodeToIns(i)
      val offset = insId * numClass
      for (k <- 0 until numClass)
        predictions(offset + k) += update_(k)
    }
  }

  def numInstance: Int = insPos.length

  def getNodePosStart(nid: Int) = nodePosStart(nid)

  def getNodePosEnd(nid: Int) = nodePosEnd(nid)

  def getNodeSize(nid: Int): Int = nodePosEnd(nid) - nodePosStart(nid) + 1

}
