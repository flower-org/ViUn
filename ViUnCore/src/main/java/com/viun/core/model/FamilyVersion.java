package com.viun.core.model;

import java.util.Map;

public record FamilyVersion<VERSION extends Comparable<VERSION>>(
        Map<String, UnionVersion<VERSION>> unionVersions) {
}
