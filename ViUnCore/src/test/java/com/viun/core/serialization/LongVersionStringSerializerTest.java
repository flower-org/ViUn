package com.viun.core.serialization;

import com.viun.core.model.FamilyVersion;
import com.viun.core.model.UnionVersion;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the {@code Long} specialization {@link LongVersionStringSerializer}, covering the
 * documented format examples and {@code Long}-specific parsing.
 */
class LongVersionStringSerializerTest {

    private final LongVersionStringSerializer serializer = new LongVersionStringSerializer();

    private static UnionVersion<Long> union(long version, Object... graphPairs) {
        Map<String, Long> graphs = new LinkedHashMap<>();
        for (int i = 0; i < graphPairs.length; i += 2) {
            graphs.put((String) graphPairs[i], (Long) graphPairs[i + 1]);
        }
        return new UnionVersion<>(version, graphs);
    }

    private static FamilyVersion<Long> family(Object... unionPairs) {
        Map<String, UnionVersion<Long>> unions = new LinkedHashMap<>();
        for (int i = 0; i < unionPairs.length; i += 2) {
            @SuppressWarnings("unchecked")
            UnionVersion<Long> value = (UnionVersion<Long>) unionPairs[i + 1];
            unions.put((String) unionPairs[i], value);
        }
        return new FamilyVersion<>(unions);
    }

    // ---------------------------------------------------------------------
    // UnionVersion format examples (from the class Javadoc)
    // ---------------------------------------------------------------------

    @Test
    void unionVersionNoGraphs() {
        assertEquals("7", serializer.serializeUnionVersion(union(7L)));
    }

    @Test
    void unionVersionSingleGraph() {
        assertEquals("42;items:3", serializer.serializeUnionVersion(union(42L, "items", 3L)));
    }

    @Test
    void unionVersionMultipleGraphs() {
        assertEquals("42;items:3;tags:8",
                serializer.serializeUnionVersion(union(42L, "items", 3L, "tags", 8L)));
    }

    @Test
    void unionVersionGraphNameWithDelimiterIsEscaped() {
        assertEquals("1;a\\:b:2", serializer.serializeUnionVersion(union(1L, "a:b", 2L)));
    }

    @Test
    void unionVersionRoundTrip() {
        UnionVersion<Long> uv = union(999L, "a", 1L, "b:c", 2L, "d;e", 3L);
        assertEquals(uv, serializer.deserializeUnionVersion(serializer.serializeUnionVersion(uv)));
    }

    @Test
    void deserializeUnionVersionReadsLongValues() {
        UnionVersion<Long> uv = serializer.deserializeUnionVersion("42;items:3");
        assertEquals(42L, uv.unionVersion());
        assertEquals(3L, uv.graphVersions().get("items"));
    }

    @Test
    void deserializeUnionVersionWithNonNumericVersionThrows() {
        assertThrows(NumberFormatException.class,
                () -> serializer.deserializeUnionVersion("notANumber"));
    }

    @Test
    void negativeAndLargeLongVersionsRoundTrip() {
        UnionVersion<Long> uv = union(Long.MIN_VALUE, "g", Long.MAX_VALUE);
        assertEquals(uv, serializer.deserializeUnionVersion(serializer.serializeUnionVersion(uv)));
    }

    // ---------------------------------------------------------------------
    // FamilyVersion format examples
    // ---------------------------------------------------------------------

    @Test
    void emptyFamilyVersion() {
        assertEquals("", serializer.serializeFamilyVersion(family()));
        assertTrue(serializer.deserializeFamilyVersion("").unionVersions().isEmpty());
    }

    @Test
    void familyVersionSingleUnionNestedEscaping() {
        assertEquals("orders:5\\;items\\:3",
                serializer.serializeFamilyVersion(family("orders", union(5L, "items", 3L))));
    }

    @Test
    void familyVersionMultipleUnions() {
        FamilyVersion<Long> fv = family("a", union(1L), "b", union(2L, "g", 9L));
        assertEquals("a:1|b:2\\;g\\:9", serializer.serializeFamilyVersion(fv));
    }

    @Test
    void familyVersionRoundTrip() {
        FamilyVersion<Long> fv = family(
                "orders", union(5L, "items", 3L, "ta:gs", 8L),
                "us|ers", union(2L),
                "misc", union(0L, "x", -1L));
        assertEquals(fv, serializer.deserializeFamilyVersion(serializer.serializeFamilyVersion(fv)));
    }
}
