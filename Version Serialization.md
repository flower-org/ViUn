# Family Versions, Union Versions, and Serialization Rules

This document describes the version model used by ViUn (`FamilyVersion` and `UnionVersion`), the
related `UpdateFilter`, and the exact rules the string serializer applies to convert these objects to
a compact, reversible text form and back.

Source of truth:

- `com.viun.core.model.FamilyVersion`
- `com.viun.core.model.UnionVersion`
- `com.viun.core.UpdateFilter`
- `com.viun.core.serialization.VersionStringSerializer` (generic engine)
- `com.viun.core.serialization.LongVersionStringSerializer` (`Long` specialization)

---

## 1. The version model

### 1.1 UnionVersion

A `UnionVersion<VERSION>` is one union-level version plus zero or more named graph versions:

```
UnionVersion<VERSION>(
    VERSION               unionVersion,
    Map<String, VERSION>  graphVersions   // graphName -> version
)
```

Conceptually: `[<unionVersion>, graph1:<v1>, graph2:<v2>, ...]`. The union version always comes
first; the graph versions are an ordered map keyed by graph name.

### 1.2 FamilyVersion

A `FamilyVersion<VERSION>` is an ordered map from union name to that union's `UnionVersion`:

```
FamilyVersion<VERSION>(
    Map<String, UnionVersion<VERSION>>  unionVersions   // unionName -> UnionVersion
)
```

A family aggregates the versions of all unions it contains.

### 1.3 UpdateFilter

An `UpdateFilter` restricts which parts of an update are streamed to a consumer. It is an ordered map
from union name to a set of graph names:

```
UpdateFilter(
    Map<String, Set<String>>  unionGraphNames   // unionName -> {graphName, ...}
)
```

Semantics:

- Empty map &rarr; **no restriction**: all unions and all their graphs are included.
- A key present in the map selects that union.
- An **empty** graph-name set for a union &rarr; include **all** graphs of that union.
- A **non-empty** graph-name set &rarr; include **only** those graphs.

### 1.4 The `VERSION` type parameter

`VERSION` is a generic type parameter bound by `VERSION extends Comparable<VERSION>`. The serializer
is written against this parameter, so any comparable version type can be serialized. The only
version-specific logic lives in two small hooks (see §4).

The repository ships one concrete specialization, `LongVersionStringSerializer`
(`VERSION = Long`).

---

## 2. Serialization overview

The format is **delimiter-based with backslash escaping**. Because every structural delimiter that
could appear inside a name or a version value is escaped, union names, graph names, and version
strings may contain **any** character.

Guaranteed property (round-trip):

```
deserialize(serialize(x)).equals(x)
```

holds for `FamilyVersion`, `UnionVersion`, and `UpdateFilter`. Map and set iteration order is treated
as insertion order and is preserved by the round-trip (the implementations use `LinkedHashMap` /
`LinkedHashSet`).

### 2.1 Delimiters

| Constant                  | Char  | Role                                                   |
|---------------------------|:-----:|--------------------------------------------------------|
| `ESCAPE`                  | `\`   | Escape character                                       |
| `FAMILY_ENTRY_SEP`        | `\|`  | Separates union entries in a family version            |
| `FAMILY_NAME_VALUE_SEP`   | `:`   | Separates a union name from its serialized union version |
| `UNION_PART_SEP`          | `;`   | Separates the union version and each graph entry       |
| `GRAPH_NAME_VALUE_SEP`    | `:`   | Separates a graph name from its version                |
| `FILTER_UNION_SEP`        | `\|`  | Separates union entries in an update filter            |
| `FILTER_GRAPH_SEP`        | `;`   | Separates graph names within a filter union            |
| `FILTER_KEY_VALUE_SEP`    | `:`   | Separates a union name from its graph-name list (present only when the list is non-empty) |

### 2.2 Escaping rule

A token is escaped by prefixing a backslash before any of: `\  \|  :  ;`.
Unescaping removes a backslash and keeps the following character verbatim.

Splitting and key/value scanning operate **only on unescaped delimiters** (`splitUnescaped`,
`indexOfUnescaped`), so escaped delimiters inside a name or value are never mistaken for separators.

---

## 3. Formats

### 3.1 UnionVersion

```
unionVersion;graphName:graphVersion;graphName2:graphVersion2...
```

The union version is always first. Each graph entry is `graphName:graphVersion`. The union version,
every graph name, and every graph version are individually escaped.

Examples (`VERSION = Long`):

| Object                                        | Serialized         |
|-----------------------------------------------|--------------------|
| `7; {}`                                        | `7`                |
| `42; {"items" -> 3}`                            | `42;items:3`       |
| `42; {"items" -> 3, "tags" -> 8}`              | `42;items:3;tags:8`|
| `1; {"a:b" -> 2}` (name with delimiter)         | `1;a\:b:2`         |

Deserialization of an **empty string** throws `IllegalArgumentException` (a union version must have at
least the union-version field). A graph entry without an unescaped `:` throws
`IllegalArgumentException` ("Malformed graph version entry").

### 3.2 FamilyVersion

```
unionName:unionVersionSerialized|unionName2:unionVersionSerialized2...
```

Each entry is `unionName:<serialized UnionVersion>`. The union name is escaped once. The nested
union-version string is escaped **again** as a whole, so its own `;` and `:` appear backslash-escaped
within the family entry (double escaping).

Examples (`VERSION = Long`):

| Object                                                   | Serialized            |
|----------------------------------------------------------|-----------------------|
| `{}` (no unions)                                          | `""` (empty string)   |
| `{"orders" -> 5; {"items" -> 3}}`                         | `orders:5\;items\:3`  |
| `{"a" -> 1; {}, "b" -> 2; {"g" -> 9}}`                    | `a:1\|b:2\;g\:9`      |

Deserialization of an **empty string** yields an **empty** family version. An entry without an
unescaped `:` throws `IllegalArgumentException` ("Malformed family version entry").

### 3.3 UpdateFilter

```
unionName:graphName;graphName2|unionName2:graphName3...
```

Each entry is a union name, optionally followed by `:` and a semicolon-separated list of graph names.
The colon and list are present **only when the union restricts to specific graphs**; a union with an
empty graph set (meaning "all graphs") is written as the bare union name. Names are escaped.

Examples:

| Object                                                       | Serialized           |
|--------------------------------------------------------------|----------------------|
| `{}` (no restriction)                                        | `""` (empty string)  |
| `{"orders" -> {}}` (all graphs of `orders`)                  | `orders`             |
| `{"orders" -> {"items"}}`                                    | `orders:items`       |
| `{"orders" -> {"items", "tags"}, "users" -> {}}`             | `orders:items;tags\|users` |

Deserialization of an **empty string** yields an **empty** filter (no restriction). An entry with no
unescaped `:` denotes a union with an empty graph set (all graphs); there is no malformed-entry case
for update filters. A trailing colon with nothing after it (`unionName:`) deserializes identically to
the bare `unionName` — both yield the empty graph set (all graphs).

---

## 4. The generic engine and its extension points

`VersionStringSerializer<VERSION>` contains all structural logic. Two protected hooks isolate the
version-specific part:

```java
// Render a version value. Defaults to Object.toString().
protected String versionToString(VERSION version) {
    return version.toString();
}

// Parse a version value produced by versionToString(...). No generic default exists,
// so subclasses must implement this.
protected abstract VERSION versionFromString(String value);
```

Rules for a correct subclass:

- `versionFromString(versionToString(v))` must equal `v` for every valid version `v`.
- `versionToString` may return any string; the engine escapes it, so delimiter characters in the
  output are safe.

The `Long` specialization:

```java
public final class LongVersionStringSerializer extends VersionStringSerializer<Long> {
    @Override
    protected Long versionFromString(String value) {
        return Long.parseLong(value);
    }
    // versionToString inherits the default Long.toString(...)
}
```

`serializeUpdateFilter` / `deserializeUpdateFilter` are `static` because an update filter contains no
`VERSION` values and is therefore version-independent.

---

## 5. Official stance on names containing delimiters

Union names and graph names may contain **any** character, including every structural delimiter.
They are always escaped on write and unescaped on read, and they round-trip exactly.

| Name        | Character | Serialized form | Round-trips |
|-------------|:---------:|-----------------|:-----------:|
| graph name  | `:`       | `g\:x`          | yes         |
| graph name  | `;`       | `g\;x`          | yes         |
| graph name  | `\|`      | `g\|x`          | yes         |
| union name  | `:`       | `u\:x`          | yes         |
| union name  | `;`       | `u\;x`          | yes         |
| union name  | `\|`      | `u\|x`          | yes         |

The same holds for the backslash itself, in both names and version strings.

---

## 6. Edge cases and limitations

- **Empty input strings.**
  - `deserializeFamilyVersion("")` &rarr; empty family.
  - `deserializeUpdateFilter("")` &rarr; empty filter (no restriction).
  - `deserializeUnionVersion("")` &rarr; throws `IllegalArgumentException` (a union version always has
    at least its version field).

- **Empty graph-name string in an `UpdateFilter` is ambiguous.** A union mapped to the single
  graph-name set `{""}` serializes to `unionName:` (colon followed by an empty graph token). On
  deserialization the empty list after the colon reads back as the empty set `{}` (all graphs). Do
  **not** rely on an empty-string graph name inside an update filter; it is not preserved. Note the
  common "all graphs" set `{}` is unaffected: it serializes to the bare `unionName` (no colon) and
  round-trips exactly. (The `{""}` case is the only known non-round-tripping case; empty strings are
  fine elsewhere, including as union/graph names in versions.)

- **Ordering.** Iteration order of unions and graphs is preserved as insertion order via
  `LinkedHashMap`/`LinkedHashSet`. Equality of the deserialized object ignores iteration order anyway
  (map/set equality is content-based), but the textual output is order-sensitive.

- **Malformed input.** For union and family versions, missing required separators throw
  `IllegalArgumentException`; a malformed version value throws whatever `versionFromString` throws
  (for `Long`, `NumberFormatException`). Update filters have no malformed-entry case: any token is a
  valid union name, with or without a graph list.

---

## 7. Test coverage

The behavior above is exercised by:

- `VersionStringSerializerTest` — generic engine over `String` and `Integer` versions: default and
  overridden `versionToString`/`versionFromString`, escaping of every delimiter in names and version
  values, union/family formats and ordering, empty-input handling, and malformed-input exceptions for
  union/family versions. Also covers the static `UpdateFilter` methods, including the bare-name
  "all graphs" form and colon-less entries.
- `LongVersionStringSerializerTest` — the `Long` specialization: the documented format examples,
  `Long` parsing, `NumberFormatException` on non-numeric input, and `Long.MIN_VALUE` / `Long.MAX_VALUE`
  round-trips.
