package com.viun.core.serialization;

import com.viun.core.UpdateFilter;
import com.viun.core.model.FamilyVersion;
import com.viun.core.model.UnionVersion;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the generic {@link VersionStringSerializer} behaviour: escaping, overridable version
 * conversion, round-trips and malformed-input handling. Uses {@code String} and {@code Integer}
 * version types to exercise the template independently of {@link LongVersionStringSerializer}.
 */
class VersionStringSerializerTest {

    /** Identity serializer over {@code String} versions; version strings may contain delimiters. */
    private static final class StringVersionStringSerializer
            extends VersionStringSerializer<String> {
        @Override
        protected String versionFromString(String value) {
            return value;
        }
    }

    /** Serializer that renders {@code Integer} versions in hex, overriding {@code versionToString}. */
    private static final class HexIntVersionStringSerializer
            extends VersionStringSerializer<Integer> {
        @Override
        protected String versionToString(Integer version) {
            return Integer.toHexString(version);
        }

        @Override
        protected Integer versionFromString(String value) {
            return Integer.parseInt(value, 16);
        }
    }

    private final StringVersionStringSerializer stringSerializer = new StringVersionStringSerializer();
    private final HexIntVersionStringSerializer hexSerializer = new HexIntVersionStringSerializer();

    // ---------------------------------------------------------------------
    // Builders
    // ---------------------------------------------------------------------

    private static <V extends Comparable<V>> UnionVersion<V> union(V version, Object... graphPairs) {
        Map<String, V> graphs = new LinkedHashMap<>();
        for (int i = 0; i < graphPairs.length; i += 2) {
            @SuppressWarnings("unchecked")
            V value = (V) graphPairs[i + 1];
            graphs.put((String) graphPairs[i], value);
        }
        return new UnionVersion<>(version, graphs);
    }

    private static <V extends Comparable<V>> FamilyVersion<V> family(Object... unionPairs) {
        Map<String, UnionVersion<V>> unions = new LinkedHashMap<>();
        for (int i = 0; i < unionPairs.length; i += 2) {
            @SuppressWarnings("unchecked")
            UnionVersion<V> value = (UnionVersion<V>) unionPairs[i + 1];
            unions.put((String) unionPairs[i], value);
        }
        return new FamilyVersion<>(unions);
    }

    // ---------------------------------------------------------------------
    // Default versionToString uses Object.toString()
    // ---------------------------------------------------------------------

    @Test
    void defaultVersionToStringUsesToString() {
        UnionVersion<Integer> uv = union(42, "items", 3);
        // Integer.toString(42) == "42", not hex.
        String str = new VersionStringSerializer<Integer>() {
            @Override
            protected Integer versionFromString(String value) {
                return Integer.parseInt(value);
            }
        }.serializeUnionVersion(uv);
        assertEquals("42>items:3", str);
    }

    // ---------------------------------------------------------------------
    // Overridable versionToString
    // ---------------------------------------------------------------------

    @Test
    void overriddenVersionToStringIsUsedForUnionAndGraphVersions() {
        UnionVersion<Integer> uv = union(255, "g", 16);
        assertEquals("ff>g:10", hexSerializer.serializeUnionVersion(uv));
    }

    @Test
    void overriddenVersionRoundTrips() {
        UnionVersion<Integer> uv = union(4095, "a", 10, "b", 171);
        String str = hexSerializer.serializeUnionVersion(uv);
        assertEquals(uv, hexSerializer.deserializeUnionVersion(str));
    }

    // ---------------------------------------------------------------------
    // Escaping: version values containing delimiters survive round-trip
    // ---------------------------------------------------------------------

    @Test
    void versionValuesWithDelimitersAreEscaped() {
        String trickyVersion = "a;b:c|d,e=f\\g";
        UnionVersion<String> uv = union(trickyVersion, "graph", "x:y;z");
        String str = stringSerializer.serializeUnionVersion(uv);
        assertEquals(uv, stringSerializer.deserializeUnionVersion(str));
    }

    @Test
    void graphNamesWithEveryDelimiterRoundTrip() {
        String tricky = "\\:;|,=>";
        UnionVersion<String> uv = union("v", tricky, "w");
        assertEquals(uv, stringSerializer.deserializeUnionVersion(
                stringSerializer.serializeUnionVersion(uv)));
    }

    // ---------------------------------------------------------------------
    // UnionVersion
    // ---------------------------------------------------------------------

    @Test
    void unionVersionWithoutGraphsHasNoSeparator() {
        assertEquals("7", stringSerializer.serializeUnionVersion(union("7")));
    }

    @Test
    void unionVersionMultipleGraphsPreserveInsertionOrder() {
        UnionVersion<String> uv = union("42", "items", "3", "tags", "8");
        assertEquals("42>items:3;tags:8", stringSerializer.serializeUnionVersion(uv));
    }

    @Test
    void deserializeEmptyUnionVersionThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> stringSerializer.deserializeUnionVersion(""));
    }

    @Test
    void deserializeUnionVersionWithMalformedGraphEntryThrows() {
        // "items3" has no unescaped ':' separator.
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> stringSerializer.deserializeUnionVersion("1>items3"));
        assertTrue(ex.getMessage().contains("graph version entry"));
    }

    // ---------------------------------------------------------------------
    // FamilyVersion
    // ---------------------------------------------------------------------

    @Test
    void emptyFamilyVersionSerializesToEmptyString() {
        assertEquals("", stringSerializer.serializeFamilyVersion(family()));
    }

    @Test
    void deserializeEmptyFamilyVersionYieldsEmptyMap() {
        FamilyVersion<String> fv = stringSerializer.deserializeFamilyVersion("");
        assertTrue(fv.unionVersions().isEmpty());
    }

    @Test
    void familyVersionEmbedsUnionVersionVerbatim() {
        FamilyVersion<String> fv = family(
                "orders", union("5", "items", "3"));
        // The nested "5>items:3" is embedded as-is after the first colon.
        assertEquals("orders:5>items:3", stringSerializer.serializeFamilyVersion(fv));
    }

    @Test
    void familyVersionWithMultipleUnionsRoundTrips() {
        FamilyVersion<String> fv = family(
                "a", union("1"),
                "b", union("2", "g", "9"),
                "name|with:delims", union("3", "graph;x", "4"));
        String str = stringSerializer.serializeFamilyVersion(fv);
        assertEquals(fv, stringSerializer.deserializeFamilyVersion(str));
    }

    @Test
    void deserializeFamilyVersionWithMalformedEntryThrows() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> stringSerializer.deserializeFamilyVersion("noSeparatorHere"));
        assertTrue(ex.getMessage().contains("family version entry"));
    }

    // ---------------------------------------------------------------------
    // UpdateFilter (static, version-independent)
    // ---------------------------------------------------------------------

    private static UpdateFilter filter(Object... pairs) {
        Map<String, Set<String>> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            @SuppressWarnings("unchecked")
            Set<String> graphs = new LinkedHashSet<>((Set<String>) pairs[i + 1]);
            map.put((String) pairs[i], graphs);
        }
        return new UpdateFilter(map);
    }

    @Test
    void emptyUpdateFilterSerializesToEmptyString() {
        assertEquals("", VersionStringSerializer.serializeUpdateFilter(filter()));
    }

    @Test
    void deserializeEmptyUpdateFilterYieldsEmptyMap() {
        assertTrue(VersionStringSerializer.deserializeUpdateFilter("").unionGraphNames().isEmpty());
    }

    @Test
    void updateFilterUnionWithAllGraphsSerializesToBareName() {
        assertEquals("orders", VersionStringSerializer.serializeUpdateFilter(
                filter("orders", Set.of())));
    }

    @Test
    void updateFilterUnionWithSelectedGraphs() {
        assertEquals("orders>items;tags", VersionStringSerializer.serializeUpdateFilter(
                filter("orders", new LinkedHashSet<>(java.util.List.of("items", "tags")))));
    }

    @Test
    void updateFilterRoundTripWithDelimitersInNames() {
        UpdateFilter f = filter(
                "orders|weird:name", new LinkedHashSet<>(java.util.List.of("a;b", "c:d", "e|f")),
                "users", Set.of());
        String str = VersionStringSerializer.serializeUpdateFilter(f);
        assertEquals(f, VersionStringSerializer.deserializeUpdateFilter(str));
    }

    @Test
    void deserializeUpdateFilterEntryWithoutSeparatorMeansAllGraphs() {
        UpdateFilter f = VersionStringSerializer.deserializeUpdateFilter("orders|users>items");
        assertEquals(Set.of(), f.unionGraphNames().get("orders"));
        assertEquals(Set.of("items"), f.unionGraphNames().get("users"));
    }

    @Test
    void deserializeUpdateFilterTrailingSeparatorEqualsBareName() {
        assertEquals(
                VersionStringSerializer.deserializeUpdateFilter("orders"),
                VersionStringSerializer.deserializeUpdateFilter("orders>"));
    }

    // ---------------------------------------------------------------------
    // Empty names are not allowed (enforced on serialize and deserialize)
    // ---------------------------------------------------------------------

    @Test
    void serializeFamilyVersionWithEmptyUnionNameThrows() {
        FamilyVersion<String> fv = family("", union("1"));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> stringSerializer.serializeFamilyVersion(fv));
        assertTrue(ex.getMessage().contains("union name"));
    }

    @Test
    void serializeUnionVersionWithEmptyGraphNameThrows() {
        UnionVersion<String> uv = union("1", "", "2");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> stringSerializer.serializeUnionVersion(uv));
        assertTrue(ex.getMessage().contains("graph name"));
    }

    @Test
    void serializeUpdateFilterWithEmptyUnionNameThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> VersionStringSerializer.serializeUpdateFilter(filter("", Set.of())));
    }

    @Test
    void serializeUpdateFilterWithEmptyGraphNameThrows() {
        assertThrows(IllegalArgumentException.class, () -> VersionStringSerializer.serializeUpdateFilter(
                filter("orders", new LinkedHashSet<>(java.util.List.of("")))));
    }

    @Test
    void deserializeFamilyVersionWithEmptyUnionNameThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> stringSerializer.deserializeFamilyVersion(":1"));
    }

    @Test
    void deserializeUnionVersionWithEmptyGraphNameThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> stringSerializer.deserializeUnionVersion("1>:2"));
    }

    @Test
    void deserializeUpdateFilterWithEmptyUnionNameThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> VersionStringSerializer.deserializeUpdateFilter("orders|"));
    }

    @Test
    void deserializeUpdateFilterWithEmptyGraphNameThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> VersionStringSerializer.deserializeUpdateFilter("orders>items;"));
    }
}
