package com.viun.core.model;

public interface Edge<VERSION extends Comparable<VERSION>, ID> extends Element<VERSION, ID> {
    ID edgeTypeId();

    ID vertexFromId();
    ID vertexToId();
}
