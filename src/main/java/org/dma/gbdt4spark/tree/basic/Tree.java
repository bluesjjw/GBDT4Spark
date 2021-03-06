package org.dma.gbdt4spark.tree.basic;

import org.dma.gbdt4spark.tree.param.TreeParam;
import org.dma.gbdt4spark.tree.split.SplitEntry;

import java.io.Serializable;
import java.util.Map;
import java.util.TreeMap;

public abstract class Tree<TParam extends TreeParam, Node extends TNode> implements Serializable {
    protected final TParam param;
    //private int[] fset; // features used in this tree, null means all the features are used
    protected Map<Integer, Node> nodes; // nodes in the tree

    public Tree(TParam param) {
        this.param = param;
        this.nodes = new TreeMap<>();
    }

    //public int[] getFset() {
    //    return this.fset;
    //}

    public Node getRoot() {
        return this.nodes.get(0);
    }

    public Node getNode(int nid) {
        return this.nodes.get(nid);
    }

    public void setNode(int nid, Node node) {
        this.nodes.put(nid, node);
    }

    public int size() {
        return nodes.size();
    }

    //public void setFset(int[] fset) {
    //    this.fset = fset;
    //}
}
