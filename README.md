# ViUn

Для начала разберемся с терминами, и тем, что же такое ViUn.
ViUn - это сокращенное V-Union млм Versioned Union.
В данном случае Union - это термин из теории графов.

## 1. Назначение и задачи ViUn

ViUn предоставляет средства для описания сложных структур в форме графов, обеспечивая их объединение в единую структуру при сохранении версионности и возможности потоковой передачи обновлений посредством механизма подписки на push-уведомления, включая фильтрацию по заданным подмножествам.

Одной из ключевых особенностей ViUn является возможность объединения потоков обновлений из нескольких источников в единый поток, что позволяет формировать сложные цепочки ретрансляции.


## 2. Релевантные определения из теории графов

В документе будут использоваться следующие определения из теории графов для описания структуры ViUn:

- **Vertex (Node)**  
A vertex is an element of a finite set V in a graph G = (V, E). It is an atomic unit without internal structure in the graph representation.

- **Edge**  
An edge is an element of a set E consisting of unordered pairs (in undirected graphs) or ordered pairs (in directed graphs) of distinct vertices. An edge connects two vertices.

- **Graph**  
A graph is an ordered pair G = (V, E) where V is a finite non-empty set of vertices and E is a set of edges, where each edge is a pair of vertices from V.

- **Union (of graphs)**  
The union of graphs G₁ = (V₁, E₁) and G₂ = (V₂, E₂) is a graph G₁ ∪ G₂ = (V₁ ∪ V₂, E₁ ∪ E₂), defined as the graph whose vertex set is the union of the vertex sets and whose edge set is the union of the edge sets.

- **Family (of graphs)**  
A family of graphs is a collection of graphs, typically denoted as a set or class of graphs that share common structural properties or characteristics. Formally, F = {G₁, G₂, ..., Gₙ} where each Gᵢ is a graph.


## 3. Модель ViUn

