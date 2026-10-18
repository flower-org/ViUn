package com.viun.core;

import com.viun.core.model.FamilyUpdate;
import com.viun.core.model.FamilyVersion;

import java.util.function.Consumer;

public interface ViUnStreamer<VERSION extends Comparable<VERSION>, ID> {
    FamilyUpdate<VERSION, ID> getUpdate(FamilyVersion<VERSION> from);

    /**
     * Consumes a stream of consecutive {@link FamilyUpdate}s, starting from the given
     * version. Each update is delivered to {@code consumer} as a separate {@link FamilyUpdate}:
     * the first covers the transition from {@code from}, and every subsequent one continues from
     * the previous update's {@link FamilyUpdate#versionTo()}.
     *
     * <p>Updates are pushed to the consumer as they become available; the stream may be unbounded.
     *
     * @param from     the version to start streaming updates from
     * @param consumer the callback invoked for each consecutive update
     */
    void streamUpdates(FamilyVersion<VERSION> from, Consumer<FamilyUpdate<VERSION, ID>> consumer);

    /**
     * Consumes a stream of consecutive {@link FamilyUpdate}s, restricted to the unions
     * and graphs selected by {@code filter}.
     *
     * <p>Behaves like {@link #streamUpdates(FamilyVersion, Consumer)}, except each delivered update
     * only carries the unions (and, within them, the graphs) selected by {@code filter}. See
     * {@link UpdateFilter} for the selection semantics.
     *
     * @param from     the version to start streaming updates from
     * @param filter   restricts which unions and graphs are delivered
     * @param consumer the callback invoked for each consecutive update
     */
    void streamUpdates(FamilyVersion<VERSION> from, UpdateFilter filter,
                       Consumer<FamilyUpdate<VERSION, ID>> consumer);
}
