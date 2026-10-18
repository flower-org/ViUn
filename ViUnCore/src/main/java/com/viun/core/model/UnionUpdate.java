package com.viun.core.model;

import javax.annotation.Nullable;
import java.util.Collection;

public interface UnionUpdate<VERSION extends Comparable<VERSION>, ID> {
    String unionName();

    UnionVersion<VERSION> versionFrom();
    UnionVersion<VERSION> versionTo();

    @Nullable UnionMetadataElement<VERSION, ID> unionMetadataElement();

    @Nullable Collection<VertexType<VERSION, ID>> vertexTypes();
    @Nullable Collection<Vertex<VERSION, ID>> vertexes();
    @Nullable Collection<EdgeType<VERSION, ID>> edgeTypes();
    @Nullable Collection<Edge<VERSION, ID>> edges();

    @Nullable Collection<GraphUpdate<VERSION, ID>> graphUpdates();

    /** Default: false */
    @Nullable Boolean isTombstone();
}
