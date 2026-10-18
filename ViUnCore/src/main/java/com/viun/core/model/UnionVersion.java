package com.viun.core.model;

import java.util.Map;

public record UnionVersion<VERSION extends Comparable<VERSION>>(
        VERSION unionVersion, Map<String, VERSION> graphVersions) {
}
