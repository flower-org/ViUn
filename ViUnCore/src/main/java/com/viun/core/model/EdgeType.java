package com.viun.core.model;

import javax.annotation.Nullable;

public interface EdgeType<VERSION extends Comparable<VERSION>, ID> extends Element<VERSION, ID> {
    @Nullable SchemaType schemaType();
    /** Default: false */
    @Nullable Boolean isDirected();
}
