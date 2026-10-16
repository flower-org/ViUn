package com.viun.core.model;

import javax.annotation.Nullable;

public interface Element<VERSION extends Comparable<VERSION>, ID> {
    ID elementId();
    VERSION version();

    @Nullable String key();
    @Nullable String content();
}
