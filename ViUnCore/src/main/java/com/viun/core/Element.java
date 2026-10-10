package com.viun.core;

import javax.annotation.Nullable;

public interface Element<VERSION extends Comparable<VERSION>, ID> {
    ID id();
    VERSION version();

    @Nullable String key();
    @Nullable String content();
}
