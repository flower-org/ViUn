# Family Versions, Union Versions, and Serialization Rules

This document describes the ViUn version model (`Family Versions` and `Union
Versions`), the related `Update Filter`, and the rules for serializing them to a compact, reversible text
form and parsing them back.

---

## 1. The version model

A **version value** is a single value of the version type used throughout a given family. This
specification places two requirements on a version type (detailed in [§4](#4-version-values)):

1. **Comparable** — version values of a type are **totally ordered**, so any two can be compared.
2. **Serializable** — a version value has a text form, and that text form parses back into the same value.

### 1.1 Union Version

A Union Version pairs one version value for the union itself with a mapping of named graph versions:

```
UnionVersion
├── unionVersion : version value          (exactly one)
└── graphVersions : map                    (zero or more entries)
        graphName (text) ─► version value
```

### 1.2 Family Version

A Family Version aggregates the Union Versions of all unions it contains, keyed by union name:

```
FamilyVersion
└── unionVersions : map                    (zero or more entries)
        unionName (text) ─► UnionVersion   (see §1.1)
                               ├── unionVersion : version value
                               └── graphVersions : map
                                       graphName (text) ─► version value
```

### 1.3 Update Filter

An Update Filter restricts which parts of an update are delivered to a consumer. It maps each selected
union name to the set of graph names to include:

```
UpdateFilter
└── unionGraphNames : map                  (zero or more entries)
        unionName (text) ─► set of graphName (text)   (zero or more)
```

Semantics:

| Shape                                          | Meaning                                     |
|------------------------------------------------|---------------------------------------------|
| Empty mapping                                  | **No restriction** — all unions, all graphs |
| `unionName` present                            | Select that union                           |
| `unionName` &rarr; **empty** set               | Include **all** graphs of that union        |
| `unionName` &rarr; **non-empty** set           | Include **only** the listed graphs          |

---

## 2. Serialization overview

The serialized form of each object is a single text string. The encoding is **delimiter-based with
backslash escaping**. Because every structural delimiter that could otherwise appear inside a name or
a version value is escaped, union names, graph names, and version text may contain **any** character.

**Round-trip property.** Serializing an object and then parsing the resulting string yields an object
equal in content to the original. Equality is **content-based**: it compares the set of entries and
their values, nothing else.

**Ordering is not guaranteed.** The order in which entries (unions, graphs, graph names) appear within
a serialized string is unspecified. An implementation may emit entries in any order, and that order
carries no meaning. Consumers must not depend on it, and two strings that differ only in entry order
represent the same object.

### 2.1 Delimiters

| Char  | Role                                                       |
|:-----:|------------------------------------------------------------|
| `\`   | Escape character                                           |
| `\|`  | Separates union entries in a family version or update filter |
| `:`   | Separates a union name from its union version (family version), or a graph name from its version (union version) |
| `>`   | Separates the union part (version value or union name) from its graph list, uniformly in a union version and an update filter |
| `;`   | Separates graph entries from each other (union version and update filter) |

### 2.2 Escaping rule

A text token is escaped by placing a backslash before each occurrence of any of these characters:
`\`  `|`  `:`  `>`  `;`. Parsing reverses this: a backslash is removed and the character that follows
it is taken literally.

All splitting and name/value scanning acts **only on unescaped delimiters**, so an escaped delimiter
inside a name or value is never mistaken for a separator.

---

## 3. Formats

In the examples below, version values are shown as decimal integers for readability; the rules apply
to any version type.

### 3.1 Union Version

```
unionVersion>graphName:graphVersion;graphName2:graphVersion2...
```

The union version comes first. A `>` introduces the graph list (present only when there is at least
one graph). Each graph entry is `graphName:graphVersion`, and graph entries are separated by `;`. The
union version, every graph name, and every graph version are individually escaped. Graph entries may
appear in any order.

| Object                                        | Serialized (one valid form) |
|-----------------------------------------------|-----------------------------|
| `7; {}`                                        | `7`                        |
| `42; {items -> 3}`                             | `42>items:3`                |
| `42; {items -> 3, tags -> 8}`                  | `42>items:3;tags:8`         |
| `1; {"a:b" -> 2}` (name with delimiter)        | `1>a\:b:2`                  |

An empty string is not a valid union version (a union version always has at least its version value).
A graph entry without an unescaped `:` is malformed.

### 3.2 Family Version

```
unionName:unionVersionSerialized|unionName2:unionVersionSerialized2...
```

Each entry is `unionName:<serialized Union Version>`. The union name is escaped; the serialized Union
Version is embedded **verbatim** after the first (unescaped) `:`. This is unambiguous because a
serialized Union Version never contains an unescaped `|` (so entry splitting on `|` is safe) and the
escaped union name contains no unescaped `:` (so the first unescaped `:` is always the real
separator). Union entries may appear in any order.

| Object                                                   | Serialized (one valid form) |
|----------------------------------------------------------|-----------------------------|
| `{}` (no unions)                                          | `""` (empty string)        |
| `{orders -> 5; {items -> 3}}`                             | `orders:5>items:3`          |
| `{a -> 1; {}, b -> 2; {g -> 9}}`                          | `a:1\|b:2>g:9`               |

An empty string denotes an **empty** family version. An entry without an unescaped `:` is malformed.

### 3.3 Update Filter

```
unionName>graphName;graphName2|unionName2>graphName3...
```

Each entry is a union name, optionally followed by `>` and a semicolon-separated list of graph names.
The `>` and list are present **only when the union restricts to specific graphs**; a union with an
empty graph set (meaning "all graphs") is written as the bare union name. Names are escaped. Entries
and graph names may appear in any order.

| Object                                                       | Serialized (one valid form) |
|--------------------------------------------------------------|-----------------------------|
| `{}` (no restriction)                                        | `""` (empty string)        |
| `{orders -> {}}` (all graphs of `orders`)                    | `orders`                    |
| `{orders -> {items}}`                                        | `orders>items`              |
| `{orders -> {items, tags}, users -> {}}`                     | `orders>items;tags\|users`  |

An empty string denotes an **empty** filter (no restriction). An entry with no unescaped `>` denotes a
union with an empty graph set (all graphs). A trailing `>` with nothing after it (`unionName>`) means
the same as the bare `unionName` — both are the empty graph set (all graphs). An entry whose union
name or any listed graph name is empty is malformed.

---

## 4. Version values

The format is parameterized by the version type. Every structural rule above is independent of that
type. A version type must satisfy two requirements.

**Comparability.** Version values of a type are **totally ordered**: any two values can be compared
and ranked. The serialization format itself does not use this ordering, but the version model relies
on it to reason about which version is newer.

**Serialization.** Only the conversion of a single version value to and from text is type-specific.
Two operations define it:

- **value → text.** Produces the text form of a version value. By default this is the value's natural
  text form. The result may contain any characters, including delimiters — the format escapes them.
- **text → value.** Parses a version value from the text produced above. There is no universal
  default; each version type must define it.

**Correctness requirement.** Parsing the text form of any valid version value must reproduce that same
value. For example, integer version values are written in decimal and read back as decimal integers.

An Update Filter contains no version values, so its serialization does not involve the version type.

---

## 5. Names containing delimiters

Union names and graph names must be **non-empty** and may otherwise contain **any** character,
including every structural delimiter. They are escaped when written and unescaped when read, and they
survive the round-trip exactly.

| Name        | Character | Raw name | Escaped form | Round-trips |
|-------------|:---------:|----------|--------------|:-----------:|
| graph name  | `:`       | `g:x`    | `g\:x`       | yes         |
| graph name  | `;`       | `g;x`    | `g\;x`       | yes         |
| graph name  | `>`       | `g>x`    | `g\>x`       | yes         |
| graph name  | `\|`      | `g\|x`   | `g\\|x`      | yes         |
| union name  | `:`       | `u:x`    | `u\:x`       | yes         |
| union name  | `;`       | `u;x`    | `u\;x`       | yes         |
| union name  | `>`       | `u>x`    | `u\>x`       | yes         |
| union name  | `\|`      | `u\|x`   | `u\\|x`      | yes         |

The same holds for the backslash itself, in both names and version text.

---

## 6. Edge cases and limitations

- **Empty strings.**
  - Empty family version &rarr; empty family.
  - Empty update filter &rarr; empty filter (no restriction).
  - Empty union version &rarr; not valid (a union version always has at least its version value).
  - Empty union name or graph name &rarr; not allowed (names must be non-empty).

- **Order is insignificant.** As stated in [§2](#2-serialization-overview), entry order within a
  serialized string carries no meaning and is not guaranteed. Equality compares content only.

- **Malformed input.** A missing required separator makes a union or family version malformed, as does
  a version value whose text cannot be parsed by the version type. An empty union name or graph name is
  malformed in every format (names must be non-empty).
