package com.viun.core.model;

import java.util.Map;

public interface FamilyUpdate<VERSION extends Comparable<VERSION>, ID> {
    FamilyVersion<VERSION> versionFrom();
    FamilyVersion<VERSION> versionTo();

    Map<String, UnionUpdate<VERSION, ID>> unionVersions();
}
