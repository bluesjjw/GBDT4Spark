package org.dma.gbdt4spark.algo.gbdt.histogram;

import org.dma.gbdt4spark.algo.gbdt.metadata.FeatureInfo;
import org.dma.gbdt4spark.algo.gbdt.tree.GBTSplit;
import org.dma.gbdt4spark.tree.param.GBDTParam;
import org.dma.gbdt4spark.tree.split.SplitPoint;
import org.dma.gbdt4spark.tree.split.SplitSet;
import org.dma.gbdt4spark.util.Maths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.Option;

import java.util.ArrayList;
import java.util.List;

public class SplitFinder {
    private static final Logger LOG = LoggerFactory.getLogger(SplitFinder.class);

    private final GBDTParam param;

    public SplitFinder(GBDTParam param) {
        this.param = param;
    }

    public GBTSplit findBestSplit(int[] sampledFeats, Option<Histogram>[] histograms,
                                  FeatureInfo featureInfo, GradPair sumGradPair, float nodeGain) {
        GBTSplit bestSplit = new GBTSplit();
        for (int i = 0; i < sampledFeats.length; i++) {
            if (histograms[i].isDefined()) {
                Histogram histogram = histograms[i].get();
                int fid = sampledFeats[i];
                boolean isCategorical = featureInfo.isCategorical(fid);
                float[] splits = featureInfo.getSplits(fid);
                int defaultBin = featureInfo.getDefaultBin(fid);
                GBTSplit curSplit = findBestSplitOfOneFeature(fid, isCategorical,
                        splits, defaultBin, histogram, sumGradPair, nodeGain);
                bestSplit.update(curSplit);
            }
        }
        return bestSplit;
    }

    public GBTSplit findBestSplitOfOneFeature(int fid, boolean isCategorical, float[] splits, int defaultBin,
                                              Histogram histogram, GradPair sumGradPair, float nodeGain) {
        if (isCategorical) {
            return findBestSplitSet(fid, splits, defaultBin, histogram, sumGradPair, nodeGain);
        } else {
            return findBestSplitPoint(fid, splits, defaultBin, histogram, sumGradPair, nodeGain);
        }
    }

    // TODO: use more schema on default bin
    private GBTSplit findBestSplitPoint(int fid, float[] splits, int defaultBin, Histogram histogram,
                                        GradPair sumGradPair, float nodeGain) {
        SplitPoint splitPoint = new SplitPoint();
        GradPair leftStat = param.numClass == 2 ? new BinaryGradPair()
                : new MultiGradPair(param.numClass, param.fullHessian);
        GradPair rightStat = sumGradPair.copy();
        GradPair bestLeftStat = null, bestRightStat = null;
        for (int i = 0; i < histogram.getNumBin() - 1; i++) {
            leftStat.plusBy(histogram.get(i));
            rightStat.subtractBy(histogram.get(i));
            if (leftStat.satisfyWeight(param) && rightStat.satisfyWeight(param)) {
                float lossChg = leftStat.calcGain(param) + rightStat.calcGain(param)
                        - nodeGain - param.regLambda;
                if (splitPoint.needReplace(lossChg)) {
                    splitPoint.setFid(fid);
                    splitPoint.setFvalue(splits[i + 1]);
                    splitPoint.setGain(lossChg);
                    bestLeftStat = leftStat.copy();
                    bestRightStat = rightStat.copy();
                }
            }
        }
        return new GBTSplit(splitPoint, bestLeftStat, bestRightStat);
    }

    private GBTSplit findBestSplitSet(int fid, float[] splits, int defaultBin, Histogram histogram,
                                      GradPair sumGradPair, float nodeGain) {
        // 1. set default bin to left child
        GradPair leftStat = histogram.get(defaultBin).copy();
        GradPair rightStat = null;
        // 2. for other bins, find its location
        int firstFlow = -1, curFlow = -1, curSplitId = 0;
        List<Float> edges = new ArrayList<>();
        edges.add(Float.NEGATIVE_INFINITY);
        for (int i = 0; i < histogram.getNumBin(); i++) {
            if (i == defaultBin) continue; // skip default bin
            GradPair binGradPair = histogram.get(i);
            int flowTo = binFlowTo(sumGradPair, leftStat, binGradPair);
            if (flowTo == 0) {
                leftStat.plusBy(binGradPair);
            }
            if (firstFlow == -1) {
                firstFlow = flowTo;
                curFlow = flowTo;
            } else if (flowTo != curFlow) {
                edges.add(splits[curSplitId]);
                curFlow = flowTo;
            }
            curSplitId++;
        }
        // 3. create split set
        if (edges.size() > 1 || curFlow != 0) { // whether all bins go to the same direction
            rightStat = sumGradPair.subtract(leftStat);
            if (leftStat.satisfyWeight(param) && rightStat.satisfyWeight(param)) {
                float splitGain = leftStat.calcGain(param) + rightStat.calcGain(param)
                        - nodeGain - param.regLambda;
                if (splitGain > 0.0f) {
                    SplitSet splitSet = new SplitSet(fid, splitGain, Maths.floatListToArray(edges),
                            firstFlow, 0);
                    return new GBTSplit(splitSet, leftStat, rightStat);
                }
            }
        }
        return new GBTSplit();
    }

    private int binFlowTo(GradPair sumGradPair, GradPair leftStat, GradPair binGradPair) {
        if (param.numClass == 2) {
            float sumGrad = ((BinaryGradPair) sumGradPair).getGrad();
            float leftGrad = ((BinaryGradPair) leftStat).getGrad();
            float binGrad = ((BinaryGradPair) binGradPair).getGrad();
            return binGrad * (2 * leftGrad + binGrad - sumGrad) >= 0.0f ? 0 : 1;
        } else {
            float[] sumGrad = ((MultiGradPair) sumGradPair).getGrad();
            float[] leftGrad = ((MultiGradPair) leftStat).getGrad();
            float[] binGrad = ((MultiGradPair) binGradPair).getGrad();
            float[] tmp = new float[param.numClass];
            for (int i = 0; i < param.numClass; i++) {
                tmp[i] = 2 * leftGrad[i] + binGrad[i] - sumGrad[i];
            }
            return Maths.dot(binGrad, tmp) >= 0.0f ? 0 : 1;
        }
    }
}
