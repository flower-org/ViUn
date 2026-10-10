package com.viun.core;

public interface Edge<VERSION extends Comparable<VERSION>, ID> extends Element<VERSION, ID> {
    ID vertexFromId();
    ID vertexToId();
    boolean isDirected();
}
