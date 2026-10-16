package com.viun.core.model;

import javax.annotation.Nullable;

public interface VertexType<VERSION extends Comparable<VERSION>, ID> extends Element<VERSION, ID> {
    @Nullable SchemaType schemaType();
}
