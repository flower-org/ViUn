package com.viun.core.serialization;

/**
 * {@link VersionStringSerializer} specialization for {@code Long} versions.
 *
 * <p>Uses the default {@link Object#toString()} representation for versions and {@link Long#parseLong}
 * to read them back.
 */
public final class LongVersionStringSerializer extends VersionStringSerializer<Long> {
    @Override
    protected Long versionFromString(String value) {
        return Long.parseLong(value);
    }
}
