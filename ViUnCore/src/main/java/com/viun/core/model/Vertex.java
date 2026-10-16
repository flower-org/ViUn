package com.viun.core.model;

public interface Vertex<VERSION extends Comparable<VERSION>, ID> extends Element<VERSION, ID> {
    ID vertexTypeId();
}
