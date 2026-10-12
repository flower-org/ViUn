# VUnion Database Schema

## Entity Relationship Diagram

```mermaid
erDiagram
    GRAPHS ||--o{ ELEMENTS : contains
    GRAPHS {
        bigint id_graph PK
        enum singleton_keeper UK
        varchar graph_name UK
        bigint subgraph_delete_version
        bool destroyed
        bigint destroy_revive_version
        bigint id_graph_element FK
    }

    ELEMENTS ||--o{ VERTEX_TYPES : "inherits"
    ELEMENTS ||--o{ VERTEXES : "inherits"
    ELEMENTS ||--o{ EDGE_TYPES : "inherits"
    ELEMENTS ||--o{ EDGES : "inherits"
    ELEMENTS ||--o{ LINKS : references
    ELEMENTS {
        bigint id_element PK
        varchar key UK
        text content
        bigint version UK
        bigint id_graph FK
        bigint sync
    }

    VERTEX_TYPES {
        bigint id_vertex_type PK
        varchar type_name UK
        bigint id_element FK "inherits from ELEMENTS"
    }

    VERTEXES ||--o{ EDGES : "from"
    VERTEXES ||--o{ EDGES : "to"
    VERTEXES {
        bigint id_vertex PK
        bigint id_vertex_type FK
        bigint id_element FK "inherits from ELEMENTS"
    }

    EDGE_TYPES {
        bigint id_edge_type PK
        varchar type_name UK
        bigint id_element FK "inherits from ELEMENTS"
    }

    EDGES {
        bigint id_edge PK
        bigint id_edge_type FK
        bigint id_vertex_from FK
        bigint id_vertex_to FK
        bool is_directed
        bigint id_element FK "inherits from ELEMENTS"
    }

    SUBGRAPHS ||--o{ LINKS : contains
    SUBGRAPHS {
        bigint id_subgraph PK
        varchar subgraph_name
        bigint last_version
        bigint last_delete_version
        bigint id_subgraph_element FK
    }

    LINKS {
        bigint id_link PK
        bigint id_subgraph FK
        bigint id_element FK
        bool is_tombstone
        note "UK: (id_subgraph, id_element)"
    }
```