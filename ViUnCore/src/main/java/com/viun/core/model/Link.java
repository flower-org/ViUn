package com.viun.core.model;

import javax.annotation.Nullable;

public interface Link<VERSION extends Comparable<VERSION>, ID> extends Element<VERSION, ID> {
    ID elementId();

    /** Default: false */
    @Nullable Boolean isTombstone();
}
