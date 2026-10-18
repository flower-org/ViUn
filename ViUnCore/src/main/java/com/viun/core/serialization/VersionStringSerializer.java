package com.viun.core.serialization;

import com.viun.core.UpdateFilter;
import com.viun.core.model.FamilyVersion;
import com.viun.core.model.UnionVersion;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Converts {@link FamilyVersion}, {@link UnionVersion} and {@link UpdateFilter} to a compact string
 * representation and back, for an arbitrary {@code VERSION} type.
 *
 * <p>The format is delimiter-based with backslash escaping, so union/graph names may contain any
 * character. The round-trip {@code deserialize(serialize(x)).equals(x)} holds (ignoring map/set
 * iteration order, which is preserved as insertion order).
 *
 * <p>Subclasses provide the {@code VERSION}-specific conversions by implementing
 * {@link #versionFromString(String)}; {@link #versionToString} may be overridden when the
 * default {@link Object#toString()} representation is not suitable.
 *
 * @param <VERSION> the version type handled by this serializer
 */
public abstract class VersionStringSerializer<VERSION extends Comparable<VERSION>> {
    private static final char ESCAPE = '\\';
    private static final char FAMILY_ENTRY_SEP = '|';
    private static final char FAMILY_NAME_VALUE_SEP = ':';
    private static final char UNION_PART_SEP = ';';
    private static final char GRAPH_NAME_VALUE_SEP = ':';
    private static final char FILTER_UNION_SEP = '|';
    private static final char FILTER_GRAPH_SEP = ';';
    private static final char FILTER_KEY_VALUE_SEP = ':';

    // ---------------------------------------------------------------------
    // Version conversion (overridable)
    // ---------------------------------------------------------------------

    /**
     * Converts a version value to its string representation. Defaults to {@link Object#toString()};
     * override when a different representation is required.
     */
    protected String versionToString(VERSION version) {
        return version.toString();
    }

    /** Parses a version value from the string produced by {@link #versionToString}. */
    protected abstract VERSION versionFromString(String value);

    // ---------------------------------------------------------------------
    // FamilyVersion
    // ---------------------------------------------------------------------

    /**
     * Format: {@code unionName:unionVersionSerialized|unionName2:unionVersionSerialized2...}
     *
     * <p>The nested union-version serialization is itself escaped, so its {@code ;} and {@code :}
     * characters appear backslash-escaped within a family version.
     *
     * <p>Examples:
     * <ul>
     *     <li>{@code {}} (no unions) &rarr; {@code ""}</li>
     *     <li>{@code {"orders" -> 5;{"items" -> 3}}} &rarr; {@code orders:5\;items\:3}</li>
     *     <li>{@code {"a" -> 1;{}, "b" -> 2;{"g" -> 9}}} &rarr; {@code a:1|b:2\;g\:9}</li>
     * </ul>
     */
    public String serializeFamilyVersion(FamilyVersion<VERSION> familyVersion) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, UnionVersion<VERSION>> entry : familyVersion.unionVersions().entrySet()) {
            if (!first) {
                sb.append(FAMILY_ENTRY_SEP);
            }
            first = false;
            sb.append(escape(entry.getKey()));
            sb.append(FAMILY_NAME_VALUE_SEP);
            sb.append(escape(serializeUnionVersion(entry.getValue())));
        }
        return sb.toString();
    }

    public FamilyVersion<VERSION> deserializeFamilyVersion(String value) {
        Map<String, UnionVersion<VERSION>> unionVersions = new LinkedHashMap<>();
        for (String entry : splitUnescaped(value, FAMILY_ENTRY_SEP)) {
            int sep = indexOfUnescaped(entry, FAMILY_NAME_VALUE_SEP);
            if (sep < 0) {
                throw new IllegalArgumentException("Malformed family version entry: " + entry);
            }
            String unionName = unescape(entry.substring(0, sep));
            String unionVersion = unescape(entry.substring(sep + 1));
            unionVersions.put(unionName, deserializeUnionVersion(unionVersion));
        }
        return new FamilyVersion<>(unionVersions);
    }

    // ---------------------------------------------------------------------
    // UnionVersion
    // ---------------------------------------------------------------------

    /**
     * Format: {@code unionVersion;graphName:graphVersion;graphName2:graphVersion2...}
     * (the union version always comes first).
     *
     * <p>Examples:
     * <ul>
     *     <li>{@code 7;{}} (no graphs) &rarr; {@code 7}</li>
     *     <li>{@code 42;{"items" -> 3}} &rarr; {@code 42;items:3}</li>
     *     <li>{@code 42;{"items" -> 3, "tags" -> 8}} &rarr; {@code 42;items:3;tags:8}</li>
     *     <li>{@code 1;{"a:b" -> 2}} (name with delimiter) &rarr; {@code 1;a\:b:2}</li>
     * </ul>
     */
    public String serializeUnionVersion(UnionVersion<VERSION> unionVersion) {
        StringBuilder sb = new StringBuilder();
        sb.append(escape(versionToString(unionVersion.unionVersion())));
        for (Map.Entry<String, VERSION> entry : unionVersion.graphVersions().entrySet()) {
            sb.append(UNION_PART_SEP);
            sb.append(escape(entry.getKey()));
            sb.append(GRAPH_NAME_VALUE_SEP);
            sb.append(escape(versionToString(entry.getValue())));
        }
        return sb.toString();
    }

    public UnionVersion<VERSION> deserializeUnionVersion(String value) {
        List<String> parts = splitUnescaped(value, UNION_PART_SEP);
        if (parts.isEmpty()) {
            throw new IllegalArgumentException("Malformed union version: " + value);
        }
        VERSION version = versionFromString(unescape(parts.get(0)));
        Map<String, VERSION> graphVersions = new LinkedHashMap<>();
        for (int i = 1; i < parts.size(); i++) {
            String entry = parts.get(i);
            int kv = indexOfUnescaped(entry, GRAPH_NAME_VALUE_SEP);
            if (kv < 0) {
                throw new IllegalArgumentException("Malformed graph version entry: " + entry);
            }
            String graphName = unescape(entry.substring(0, kv));
            VERSION graphVersion = versionFromString(unescape(entry.substring(kv + 1)));
            graphVersions.put(graphName, graphVersion);
        }
        return new UnionVersion<>(version, graphVersions);
    }

    // ---------------------------------------------------------------------
    // UpdateFilter
    // ---------------------------------------------------------------------

    /**
     * Format: {@code unionName:graphName;graphName2|unionName2:graphName3...}
     *
     * <p>The colon and graph list are present only when the union restricts to specific graphs; a
     * union with an empty graph set (meaning "all graphs") is written as the bare union name.
     *
     * <p>Examples:
     * <ul>
     *     <li>{@code {}} (no restriction) &rarr; {@code ""}</li>
     *     <li>{@code {"orders" -> {}}} (all graphs of {@code orders}) &rarr; {@code orders}</li>
     *     <li>{@code {"orders" -> {"items"}}} &rarr; {@code orders:items}</li>
     *     <li>{@code {"orders" -> {"items", "tags"}, "users" -> {}}} &rarr;
     *         {@code orders:items;tags|users}</li>
     * </ul>
     */
    public static String serializeUpdateFilter(UpdateFilter filter) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, Set<String>> entry : filter.unionGraphNames().entrySet()) {
            if (!first) {
                sb.append(FILTER_UNION_SEP);
            }
            first = false;
            sb.append(escape(entry.getKey()));
            Set<String> graphNames = entry.getValue();
            if (!graphNames.isEmpty()) {
                sb.append(FILTER_KEY_VALUE_SEP);
                boolean firstGraph = true;
                for (String graphName : graphNames) {
                    if (!firstGraph) {
                        sb.append(FILTER_GRAPH_SEP);
                    }
                    firstGraph = false;
                    sb.append(escape(graphName));
                }
            }
        }
        return sb.toString();
    }

    public static UpdateFilter deserializeUpdateFilter(String value) {
        Map<String, Set<String>> unionGraphNames = new LinkedHashMap<>();
        for (String entry : splitUnescaped(value, FILTER_UNION_SEP)) {
            int sep = indexOfUnescaped(entry, FILTER_KEY_VALUE_SEP);
            Set<String> graphNames = new LinkedHashSet<>();
            String unionName;
            if (sep < 0) {
                // No colon: the union restricts to no specific graphs, i.e. all graphs.
                unionName = unescape(entry);
            } else {
                unionName = unescape(entry.substring(0, sep));
                for (String graphName : splitUnescaped(entry.substring(sep + 1), FILTER_GRAPH_SEP)) {
                    graphNames.add(unescape(graphName));
                }
            }
            unionGraphNames.put(unionName, graphNames);
        }
        return new UpdateFilter(unionGraphNames);
    }

    // ---------------------------------------------------------------------
    // Escaping helpers
    // ---------------------------------------------------------------------

    private static String escape(String raw) {
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == ESCAPE || c == FAMILY_ENTRY_SEP || c == FAMILY_NAME_VALUE_SEP
                    || c == UNION_PART_SEP || c == GRAPH_NAME_VALUE_SEP
                    || c == FILTER_UNION_SEP || c == FILTER_GRAPH_SEP || c == FILTER_KEY_VALUE_SEP) {
                sb.append(ESCAPE);
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private static String unescape(String escaped) {
        StringBuilder sb = new StringBuilder(escaped.length());
        for (int i = 0; i < escaped.length(); i++) {
            char c = escaped.charAt(i);
            if (c == ESCAPE && i + 1 < escaped.length()) {
                i++;
                sb.append(escaped.charAt(i));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Splits on unescaped {@code delim}. An empty input yields an empty list. */
    private static List<String> splitUnescaped(String value, char delim) {
        List<String> tokens = new ArrayList<>();
        if (value.isEmpty()) {
            return tokens;
        }
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == ESCAPE && i + 1 < value.length()) {
                current.append(c);
                i++;
                current.append(value.charAt(i));
            } else if (c == delim) {
                tokens.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        tokens.add(current.toString());
        return tokens;
    }

    /** Index of the first unescaped {@code delim}, or {@code -1}. */
    private static int indexOfUnescaped(String value, char delim) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == ESCAPE) {
                i++;
            } else if (c == delim) {
                return i;
            }
        }
        return -1;
    }
}
