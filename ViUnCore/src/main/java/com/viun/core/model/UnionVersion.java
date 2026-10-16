package com.viun.core.model;

import java.util.Map;

public interface UnionVersion<VERSION extends Comparable<VERSION>> {
    VERSION unionVersion();
    Map<String, VERSION> graphVersions();
}
