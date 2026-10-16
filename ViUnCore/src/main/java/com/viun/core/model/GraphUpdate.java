package com.viun.core.model;

import javax.annotation.Nullable;
import java.util.Collection;

public interface GraphUpdate<VERSION extends Comparable<VERSION>, ID> {
    /** Need this one to determine whether to send link sync or not downstream */
    VERSION linkSyncVersion();

    String graphName();

    @Nullable GraphMetadataElement<VERSION, ID> graphMetadataElement();

    @Nullable Collection<ID> linkSync();
    @Nullable Collection<Link<VERSION, ID>> links();

    /** Default: false */
    @Nullable Boolean isTombstone();
}
