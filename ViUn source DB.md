# VUnion Database Schema

## Entity Relationship Diagram


`UNION.last_graph_sync_version` is not needed because `UnionVersion` does graph sync every update

```mermaid
erDiagram
    VERSIONS {
        bigint version PK
        timestamp created_at
    }

    UNIONS ||--|| ELEMENTS : "has_union_metadata_element"
    UNIONS {
        bigint id_union PK
        enum singleton_keeper UK
        varchar union_name UK
        bool is_tombstone
        bigint id_union_metadata_element FK
        bigint last_element_update_version
        bigint tombstone_revive_version
    }

    ELEMENTS ||--o{ VERTEX_TYPES : "inherits"
    ELEMENTS ||--o{ VERTEXES : "inherits"
    ELEMENTS ||--o{ EDGE_TYPES : "inherits"
    ELEMENTS ||--o{ EDGES : "inherits"
    ELEMENTS ||--o{ LINKS : "inherits"
    ELEMENTS ||--o{ LINKS : references
    ELEMENTS {
        bigint id_element PK
        varchar key
        text content
        bigint version UK
        bigint sync
    }

    VERTEX_TYPES ||--o{ VERTEXES : "defines"
    VERTEX_TYPES {
        bigint id_vertex_type PK "inherits from ELEMENTS"
        enum schema_type
    }

    VERTEXES ||--o{ EDGES : "from"
    VERTEXES ||--o{ EDGES : "to"
    VERTEXES {
        bigint id_vertex PK "inherits from ELEMENTS"
        bigint id_vertex_type FK
    }

    EDGE_TYPES {
        bigint id_edge_type PK "inherits from ELEMENTS"
        enum schema_type
        bool is_directed
    }

    EDGE_TYPES ||--o{ EDGES : "defines"
    EDGES {
        bigint id_edge PK "inherits from ELEMENTS"
        bigint id_edge_type FK
        bigint id_vertex_from FK
        bigint id_vertex_to FK
    }

    GRAPHS ||--|| ELEMENTS : "has_graph_metadata_element"
    GRAPHS ||--o{ LINKS : contains
    GRAPHS {
        bigint id_graph PK
        varchar graph_name
        bool is_tombstone
        bigint id_graph_metadata_element FK
        bigint last_element_update_version
        bigint last_link_sync_version
        bigint tombstone_revive_version
    }

    LINKS {
        bigint id_link PK "inherits from ELEMENTS"
        bigint id_graph FK
        bigint id_element FK
        bool is_tombstone
    }
```
