package com.viun.mysql;

import com.viun.core.model.SchemaType;
import com.viun.core.source.Element;
import com.viun.core.source.ViUnSource;
import io.vertx.core.Future;
import io.vertx.mysqlclient.MySQLClient;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Tuple;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * {@link ViUnSource} backed by MySQL (schema {@code db/viun_schema_GENERATED.sql}) using the
 * non-blocking Vert.x MySQL client.
 *
 * <p>Versions and element ids are {@code Long}s drawn from the {@code VERSIONS} auto-increment
 * counter. A newly created element's id equals its creation version.
 *
 * <p>The transaction context is a {@link SqlConnection} with an open transaction, e.g. obtained via
 * {@link MySqlTransactions#inTransaction}. Operations sharing a connection must be composed
 * sequentially (each started after the previous one's future completes).
 *
 * <p>Locking follows "UnionVersion update mechanics": rows are locked with
 * {@code SELECT ... FOR UPDATE} before a version is generated (local transaction ordering), and
 * every operation writes to the rows it depends on so that concurrent conflicting transactions
 * fail Galera certification. Lock order is: union, then elements, then graphs (by id),
 * then links.
 *
 * <p>Link rules (from "VUnion operations"): a vertex link requires an active link to its vertex
 * type, an edge link requires active links to its edge type and both vertexes; a link can't be
 * tombstoned while active links depend on it, nor deleted while any links depend on it.
 */
public class MySqlViUnSource implements ViUnSource<Long, Long, SqlConnection> {
    private static final int IN_CLAUSE_CHUNK_SIZE = 500;

    // ------------------------------------------------------------------
    // Elements / versions
    // ------------------------------------------------------------------

    private Future<Long> nextVersion(SqlConnection tx) {
        return query(tx, "INSERT INTO VERSIONS () VALUES ()", Tuple.tuple())
                .map(rows -> rows.property(MySQLClient.LAST_INSERTED_ID));
    }

    // ------------------------------------------------------------------
    // Union
    // ------------------------------------------------------------------

    @Override
    public CompletableFuture<Void> createUnion(SqlConnection tx, String unionName,
                                               @Nullable Element unionMetadataElement) {
        return toCf(query(tx, "SELECT id_union FROM UNIONS FOR UPDATE", Tuple.tuple())
                .compose(rows -> rows.size() > 0
                        ? Future.<Long>failedFuture(new ViUnSourceException(
                                "Union already exists; a source DB holds a single union"))
                        : nextVersion(tx))
                .compose(version -> insertOptionalElement(tx, version, unionMetadataElement)
                        .compose(metadataId -> query(tx,
                                "INSERT INTO UNIONS (singleton_keeper, union_name, id_union_metadata_element, "
                                        + "last_element_update_version) VALUES ('', ?, ?, ?)",
                                Tuple.of(unionName, metadataId, version))))
                .mapEmpty());
    }

    @Override
    public CompletableFuture<Void> upsertUnionMetadata(SqlConnection tx, String unionName,
                                                       Element updatedUnionMetadataElement) {
        return toCf(lockUnion(tx, unionName)
                .compose(union -> nextVersion(tx)
                        .compose(version -> upsertMetadataElement(tx, union.metadataElementId(), version,
                                updatedUnionMetadataElement)
                                .compose(metadataId -> query(tx,
                                        "UPDATE UNIONS SET id_union_metadata_element = ?, last_element_update_version = ? "
                                                + "WHERE id_union = ?",
                                        Tuple.of(metadataId, version, union.id())))))
                .mapEmpty());
    }

    @Override
    public CompletableFuture<Void> tombstoneUnion(SqlConnection tx, String unionName) {
        return toCf(setUnionTombstone(tx, unionName, true));
    }

    @Override
    public CompletableFuture<Void> reviveUnion(SqlConnection tx, String unionName) {
        return toCf(setUnionTombstone(tx, unionName, false));
    }

    /**
     * All graphs are locked and their versions raised to the tombstone/revive version, so that no
     * graph version can exceed the union tombstone/revive version before this transaction completes.
     */
    private Future<Void> setUnionTombstone(SqlConnection tx, String unionName, boolean tombstone) {
        return lockUnion(tx, unionName)
                .compose(union -> {
                    if (union.isTombstone() == tombstone) {
                        return Future.failedFuture(new ViUnSourceException("Union '" + unionName + "' is "
                                + (tombstone ? "already tombstoned" : "not tombstoned")));
                    }
                    return lockAllGraphs(tx)
                            .compose(graphs -> nextVersion(tx))
                            .compose(version -> query(tx,
                                    "UPDATE UNIONS SET is_tombstone = ?, tombstone_revive_version = ? WHERE id_union = ?",
                                    Tuple.of(tombstone, version, union.id()))
                                    .compose(r -> query(tx, "UPDATE GRAPHS SET last_element_update_version = ?",
                                            Tuple.of(version))));
                })
                .mapEmpty();
    }

    /** Deletes a tombstoned union along with its metadata element, all graphs and all links. */
    @Override
    public CompletableFuture<Void> deleteUnion(SqlConnection tx, String unionName) {
        return toCf(lockUnion(tx, unionName)
                .compose(union -> union.isTombstone() ? Future.succeededFuture(union)
                        : Future.failedFuture(new ViUnSourceException(
                                "Union '" + unionName + "' must be tombstoned before deletion")))
                .compose(union -> lockAllGraphs(tx)
                        .compose(graphs -> {
                            Future<Void> deleted = Future.succeededFuture();
                            for (GraphRow graph : graphs) {
                                deleted = deleted.compose(v -> deleteGraphRow(tx, graph));
                            }
                            return deleted;
                        })
                        .compose(v -> query(tx, "DELETE FROM UNIONS WHERE id_union = ?", Tuple.of(union.id())))
                        .compose(r -> deleteElements(tx, Collections.singletonList(union.metadataElementId()))))
                .mapEmpty());
    }

    // ------------------------------------------------------------------
    // Graph
    // ------------------------------------------------------------------

    @Override
    public CompletableFuture<Void> createGraph(SqlConnection tx, String graphName,
                                               @Nullable Element graphMetadataElement) {
        return toCf(lockSingletonUnion(tx)
                .compose(unionId -> query(tx, "SELECT id_graph FROM GRAPHS WHERE graph_name = ?", Tuple.of(graphName)))
                .compose(rows -> rows.size() > 0
                        ? Future.<Long>failedFuture(new ViUnSourceException("Graph '" + graphName + "' already exists"))
                        : nextVersion(tx))
                .compose(version -> insertOptionalElement(tx, version, graphMetadataElement)
                        .compose(metadataId -> query(tx,
                                "INSERT INTO GRAPHS (graph_name, id_graph_metadata_element, last_element_update_version) "
                                        + "VALUES (?, ?, ?)",
                                Tuple.of(graphName, metadataId, version))))
                .mapEmpty());
    }

    @Override
    public CompletableFuture<Void> upsertGraphMetadata(SqlConnection tx, String graphName,
                                                       Element updatedGraphMetadataElement) {
        return toCf(lockGraph(tx, graphName)
                .compose(graph -> nextVersion(tx)
                        .compose(version -> upsertMetadataElement(tx, graph.metadataElementId(), version,
                                updatedGraphMetadataElement)
                                .compose(metadataId -> query(tx,
                                        "UPDATE GRAPHS SET id_graph_metadata_element = ?, last_element_update_version = ? "
                                                + "WHERE id_graph = ?",
                                        Tuple.of(metadataId, version, graph.id())))))
                .mapEmpty());
    }

    @Override
    public CompletableFuture<Void> tombstoneGraph(SqlConnection tx, String graphName) {
        return toCf(setGraphTombstone(tx, graphName, true));
    }

    @Override
    public CompletableFuture<Void> reviveGraph(SqlConnection tx, String graphName) {
        return toCf(setGraphTombstone(tx, graphName, false));
    }

    private Future<Void> setGraphTombstone(SqlConnection tx, String graphName, boolean tombstone) {
        return lockGraph(tx, graphName)
                .compose(graph -> {
                    if (graph.isTombstone() == tombstone) {
                        return Future.failedFuture(new ViUnSourceException("Graph '" + graphName + "' is "
                                + (tombstone ? "already tombstoned" : "not tombstoned")));
                    }
                    return nextVersion(tx).compose(version -> query(tx,
                            "UPDATE GRAPHS SET is_tombstone = ?, tombstone_revive_version = ? WHERE id_graph = ?",
                            Tuple.of(tombstone, version, graph.id())));
                })
                .mapEmpty();
    }

    /**
     * Deletes the graph with its metadata element and links. The schema has no dedicated
     * "graph deleted" union version, so the union's {@code last_element_update_version} is raised
     * to signal the change in the union's graph set.
     */
    @Override
    public CompletableFuture<Void> deleteGraph(SqlConnection tx, String graphName) {
        return toCf(lockSingletonUnion(tx)
                .compose(unionId -> lockGraph(tx, graphName)
                        .compose(graph -> nextVersion(tx)
                                .compose(version -> deleteGraphRow(tx, graph)
                                        .compose(v -> query(tx,
                                                "UPDATE UNIONS SET last_element_update_version = ? WHERE id_union = ?",
                                                Tuple.of(version, unionId))))))
                .mapEmpty());
    }

    // ------------------------------------------------------------------
    // VertexType / Vertex / EdgeType / Edge
    // ------------------------------------------------------------------

    @Override
    public CompletableFuture<Long> createVertexType(SqlConnection tx, Element vertexTypeElement,
                                                    @Nullable SchemaType schemaType) {
        return toCf(nextVersion(tx)
                .compose(id -> insertElement(tx, id, vertexTypeElement)
                        .compose(r -> query(tx, "INSERT INTO VERTEX_TYPES (id_vertex_type, schema_type) VALUES (?, ?)",
                                Tuple.of(id, schemaTypeName(schemaType))))
                        .map(r -> id)));
    }

    @Override
    public CompletableFuture<Void> updateVertexType(SqlConnection tx, Long vertexTypeId,
                                                    @Nullable Element updatedVertexTypeElement) {
        return toCf(updateLinkableElement(tx, Kind.VERTEX_TYPE, vertexTypeId, updatedVertexTypeElement));
    }

    @Override
    public CompletableFuture<Void> deleteVertexType(SqlConnection tx, Long vertexTypeId) {
        return toCf(deleteLinkableElement(tx, Kind.VERTEX_TYPE, vertexTypeId));
    }

    @Override
    public CompletableFuture<Long> createVertex(SqlConnection tx, Long vertexTypeId, Element vertexElement) {
        return toCf(nextVersion(tx)
                .compose(id -> touch(tx, Kind.VERTEX_TYPE, vertexTypeId, id)
                        .compose(v -> insertElement(tx, id, vertexElement))
                        .compose(r -> query(tx, "INSERT INTO VERTEXES (id_vertex, id_vertex_type) VALUES (?, ?)",
                                Tuple.of(id, vertexTypeId)))
                        .map(r -> id)));
    }

    @Override
    public CompletableFuture<Void> updateVertex(SqlConnection tx, Long vertexId,
                                                @Nullable Element vertexElement) {
        return toCf(updateLinkableElement(tx, Kind.VERTEX, vertexId, vertexElement));
    }

    @Override
    public CompletableFuture<Void> deleteVertex(SqlConnection tx, Long vertexId) {
        return toCf(deleteLinkableElement(tx, Kind.VERTEX, vertexId));
    }

    @Override
    public CompletableFuture<Long> createEdgeType(SqlConnection tx, Element edgeTypeElement,
                                                  @Nullable Boolean isDirected, @Nullable SchemaType schemaType) {
        boolean directed = isDirected != null && isDirected;
        return toCf(nextVersion(tx)
                .compose(id -> insertElement(tx, id, edgeTypeElement)
                        .compose(r -> query(tx,
                                "INSERT INTO EDGE_TYPES (id_edge_type, schema_type, is_directed) VALUES (?, ?, ?)",
                                Tuple.of(id, schemaTypeName(schemaType), directed)))
                        .map(r -> id)));
    }

    @Override
    public CompletableFuture<Void> updateEdgeType(SqlConnection tx, Long edgeTypeId,
                                                  @Nullable Element edgeTypeElement) {
        return toCf(updateLinkableElement(tx, Kind.EDGE_TYPE, edgeTypeId, edgeTypeElement));
    }

    @Override
    public CompletableFuture<Void> deleteEdgeType(SqlConnection tx, Long edgeTypeId) {
        return toCf(deleteLinkableElement(tx, Kind.EDGE_TYPE, edgeTypeId));
    }

    @Override
    public CompletableFuture<Long> createEdge(SqlConnection tx, Long edgeTypeId, Long vertexFrom,
                                              Long vertexTo, Element edgeElement) {
        return toCf(nextVersion(tx)
                .compose(id -> touch(tx, Kind.EDGE_TYPE, edgeTypeId, id)
                        .compose(v -> touch(tx, Kind.VERTEX, vertexFrom, id))
                        .compose(v -> vertexTo.equals(vertexFrom)
                                ? Future.<Void>succeededFuture() : touch(tx, Kind.VERTEX, vertexTo, id))
                        .compose(v -> insertElement(tx, id, edgeElement))
                        .compose(r -> query(tx,
                                "INSERT INTO EDGES (id_edge, id_edge_type, id_vertex_from, id_vertex_to) VALUES (?, ?, ?, ?)",
                                Tuple.of(id, edgeTypeId, vertexFrom, vertexTo)))
                        .map(r -> id)));
    }

    @Override
    public CompletableFuture<Void> updateEdge(SqlConnection tx, Long edgeId, @Nullable Element edgeElement) {
        return toCf(updateLinkableElement(tx, Kind.EDGE, edgeId, edgeElement));
    }

    @Override
    public CompletableFuture<Void> deleteEdge(SqlConnection tx, Long edgeId) {
        return toCf(deleteLinkableElement(tx, Kind.EDGE, edgeId));
    }

    /** Updates the element and raises the version of every graph it is linked to. */
    private Future<Void> updateLinkableElement(SqlConnection tx, Kind kind, Long id,
                                               @Nullable Element element) {
        return lockElementOfKind(tx, kind, id)
                .compose(v -> query(tx, "SELECT DISTINCT id_graph FROM LINKS WHERE id_element = ?", Tuple.of(id)))
                .map(rows -> longs(rows, "id_graph"))
                .compose(graphIds -> lockGraphs(tx, graphIds)
                        .compose(v -> nextVersion(tx))
                        .compose(version -> updateElement(tx, id, version, element)
                                .compose(v -> graphIds.isEmpty() ? Future.<Void>succeededFuture()
                                        : query(tx, "UPDATE GRAPHS SET last_element_update_version = ? WHERE id_graph IN ("
                                                + placeholders(graphIds.size()) + ")",
                                        tupleOf(version, graphIds)).mapEmpty())));
    }

    /** Deletes an element that is neither linked to any graph nor referenced by other elements. */
    private Future<Void> deleteLinkableElement(SqlConnection tx, Kind kind, Long id) {
        return lockElementOfKind(tx, kind, id)
                .compose(v -> query(tx, "SELECT 1 FROM LINKS WHERE id_element = ? LIMIT 1", Tuple.of(id)))
                .compose(rows -> rows.size() > 0
                        ? Future.failedFuture(new ViUnSourceException(
                                kind.displayName + " " + id + " is referenced by a link"))
                        : query(tx, "SELECT 1 FROM VERTEXES WHERE id_vertex_type = ? UNION ALL "
                                        + "SELECT 1 FROM EDGES WHERE id_edge_type = ? OR id_vertex_from = ? OR id_vertex_to = ? "
                                        + "LIMIT 1",
                                Tuple.of(id, id, id, id)))
                .compose(rows -> rows.size() > 0
                        ? Future.failedFuture(new ViUnSourceException(
                                kind.displayName + " " + id + " is referenced by another element"))
                        : query(tx, "DELETE FROM ELEMENTS WHERE id_element = ?", Tuple.of(id)))
                .mapEmpty();
    }

    // ------------------------------------------------------------------
    // Link
    // ------------------------------------------------------------------

    @Override
    public CompletableFuture<Long> createLink(SqlConnection tx, String graphName, Long linkedElementId,
                                              Element linkElement) {
        return toCf(linkableDependencies(tx, linkedElementId, true)
                .compose(dependencies -> lockGraph(tx, graphName)
                        .compose(graph -> query(tx, "SELECT id_link FROM LINKS WHERE id_graph = ? AND id_element = ?",
                                Tuple.of(graph.id(), linkedElementId))
                                .compose(rows -> rows.size() > 0
                                        ? Future.<Void>failedFuture(new ViUnSourceException("Element " + linkedElementId
                                                + " is already linked to graph '" + graphName + "'"))
                                        : requireActiveLinks(tx, graph.id(), dependencies))
                                .compose(v -> nextVersion(tx))
                                .compose(linkId -> query(tx, "UPDATE ELEMENTS SET sync = ? WHERE id_element = ?",
                                        Tuple.of(linkId, linkedElementId))
                                        .compose(r -> insertElement(tx, linkId, linkElement))
                                        .compose(r -> query(tx,
                                                "INSERT INTO LINKS (id_link, id_graph, id_element, is_tombstone) VALUES (?, ?, ?, 0)",
                                                Tuple.of(linkId, graph.id(), linkedElementId)))
                                        .compose(r -> bumpGraphElementVersion(tx, graph.id(), linkId))
                                        .map(v -> linkId)))));
    }

    @Override
    public CompletableFuture<Void> updateLink(SqlConnection tx, Long linkId, @Nullable Element linkElement) {
        return toCf(lockLink(tx, linkId)
                .compose(link -> nextVersion(tx)
                        .compose(version -> updateElement(tx, linkId, version, linkElement)
                                .compose(v -> bumpGraphElementVersion(tx, link.graphId(), version)))));
    }

    @Override
    public CompletableFuture<Void> tombstoneLink(SqlConnection tx, Long linkId) {
        return toCf(lockLink(tx, linkId)
                .compose(link -> {
                    if (link.isTombstone()) {
                        return Future.failedFuture(new ViUnSourceException("Link " + linkId + " is already tombstoned"));
                    }
                    return requireNoDependentLinks(tx, link, true)
                            .compose(v -> setLinkTombstone(tx, link, true));
                }));
    }

    @Override
    public CompletableFuture<Void> reviveLink(SqlConnection tx, Long linkId) {
        return toCf(lockLink(tx, linkId)
                .compose(link -> {
                    if (!link.isTombstone()) {
                        return Future.failedFuture(new ViUnSourceException("Link " + linkId + " is not tombstoned"));
                    }
                    return linkableDependencies(tx, link.elementId(), false)
                            .compose(dependencies -> requireActiveLinks(tx, link.graphId(), dependencies))
                            .compose(v -> setLinkTombstone(tx, link, false));
                }));
    }

    @Override
    public CompletableFuture<Void> deleteTombstonedLink(SqlConnection tx, Long linkId) {
        return toCf(lockLink(tx, linkId)
                .compose(link -> {
                    if (!link.isTombstone()) {
                        return Future.failedFuture(new ViUnSourceException("Link " + linkId + " is not tombstoned"));
                    }
                    return requireNoDependentLinks(tx, link, false)
                            .compose(v -> nextVersion(tx))
                            .compose(version -> deleteElements(tx, Collections.singletonList(linkId))
                                    .compose(v -> bumpGraphLinkSyncVersion(tx, link.graphId(), version)));
                }));
    }

    @Override
    public CompletableFuture<Void> deleteTombstonedLinksForGraph(SqlConnection tx, String graphName) {
        return toCf(lockGraph(tx, graphName)
                .compose(graph -> query(tx, "SELECT id_link FROM LINKS WHERE id_graph = ? AND is_tombstone = 1 FOR UPDATE",
                        Tuple.of(graph.id()))
                        .map(rows -> longs(rows, "id_link"))
                        .compose(linkIds -> linkIds.isEmpty() ? Future.<Void>succeededFuture()
                                : nextVersion(tx).compose(version -> deleteElements(tx, linkIds)
                                        .compose(v -> bumpGraphLinkSyncVersion(tx, graph.id(), version))))));
    }

    private Future<Void> setLinkTombstone(SqlConnection tx, LinkRow link, boolean tombstone) {
        return nextVersion(tx)
                .compose(version -> query(tx, "UPDATE ELEMENTS SET version = ? WHERE id_element = ?",
                        Tuple.of(version, link.id()))
                        .compose(r -> query(tx, "UPDATE LINKS SET is_tombstone = ? WHERE id_link = ?",
                                Tuple.of(tombstone, link.id())))
                        .compose(r -> bumpGraphElementVersion(tx, link.graphId(), version)));
    }

    /**
     * Fails if the link's element has dependents (vertexes of a vertex type, edges of an edge type,
     * edges of a vertex) linked to the same graph; only active links count when {@code activeOnly}.
     */
    private Future<Void> requireNoDependentLinks(SqlConnection tx, LinkRow link, boolean activeOnly) {
        Long elementId = link.elementId();
        return query(tx, "SELECT l.id_element FROM LINKS l "
                        + "LEFT JOIN VERTEXES v ON v.id_vertex = l.id_element "
                        + "LEFT JOIN EDGES e ON e.id_edge = l.id_element "
                        + "WHERE l.id_graph = ? " + (activeOnly ? "AND l.is_tombstone = 0 " : "")
                        + "AND (v.id_vertex_type = ? OR e.id_edge_type = ? OR e.id_vertex_from = ? OR e.id_vertex_to = ?) "
                        + "LIMIT 1",
                Tuple.of(link.graphId(), elementId, elementId, elementId, elementId))
                .compose(rows -> rows.size() > 0
                        ? Future.failedFuture(new ViUnSourceException("Link " + link.id() + " can't be "
                                + (activeOnly ? "tombstoned" : "deleted") + ": element "
                                + rows.iterator().next().getLong(0) + " linked to the same graph depends on element "
                                + elementId))
                        : Future.succeededFuture());
    }

    /** Fails unless every element in {@code elementIds} has an active link in the graph. */
    private Future<Void> requireActiveLinks(SqlConnection tx, long graphId, Set<Long> elementIds) {
        if (elementIds.isEmpty()) {
            return Future.succeededFuture();
        }
        return query(tx, "SELECT id_element FROM LINKS WHERE id_graph = ? AND is_tombstone = 0 AND id_element IN ("
                        + placeholders(elementIds.size()) + ")",
                tupleOf(graphId, elementIds))
                .compose(rows -> {
                    Set<Long> missing = new LinkedHashSet<>(elementIds);
                    longs(rows, "id_element").forEach(missing::remove);
                    return missing.isEmpty() ? Future.succeededFuture()
                            : Future.failedFuture(new ViUnSourceException(
                                    "Elements " + missing + " must be actively linked to the graph first"));
                });
    }

    /**
     * Returns ids of elements the given linkable element depends on (vertex: its type; edge: its type and
     * vertexes). Fails if the element doesn't exist or isn't a vertex type, vertex, edge type or edge.
     */
    private Future<Set<Long>> linkableDependencies(SqlConnection tx, Long elementId, boolean lock) {
        return query(tx, "SELECT vt.id_vertex_type AS vt, v.id_vertex AS v, v.id_vertex_type AS v_type, "
                        + "et.id_edge_type AS et, ed.id_edge AS ed, ed.id_edge_type AS ed_type, "
                        + "ed.id_vertex_from AS ed_from, ed.id_vertex_to AS ed_to "
                        + "FROM ELEMENTS x "
                        + "LEFT JOIN VERTEX_TYPES vt ON vt.id_vertex_type = x.id_element "
                        + "LEFT JOIN VERTEXES v ON v.id_vertex = x.id_element "
                        + "LEFT JOIN EDGE_TYPES et ON et.id_edge_type = x.id_element "
                        + "LEFT JOIN EDGES ed ON ed.id_edge = x.id_element "
                        + "WHERE x.id_element = ?" + (lock ? " FOR UPDATE" : ""),
                Tuple.of(elementId))
                .compose(rows -> {
                    if (rows.size() == 0) {
                        return Future.failedFuture(new ViUnSourceException.NotFound("Element " + elementId + " not found"));
                    }
                    Row row = rows.iterator().next();
                    Set<Long> dependencies = new LinkedHashSet<>();
                    if (row.getLong("v") != null) {
                        dependencies.add(row.getLong("v_type"));
                    } else if (row.getLong("ed") != null) {
                        dependencies.add(row.getLong("ed_type"));
                        dependencies.add(row.getLong("ed_from"));
                        dependencies.add(row.getLong("ed_to"));
                    } else if (row.getLong("vt") == null && row.getLong("et") == null) {
                        return Future.failedFuture(new ViUnSourceException("Element " + elementId
                                + " is not a vertex type, vertex, edge type or edge and can't be linked"));
                    }
                    return Future.succeededFuture(dependencies);
                });
    }

    /** Locks the link's graph and then the link itself. */
    private Future<LinkRow> lockLink(SqlConnection tx, Long linkId) {
        return query(tx, "SELECT id_graph FROM LINKS WHERE id_link = ?", Tuple.of(linkId))
                .compose(rows -> rows.size() == 0
                        ? Future.<RowSet<Row>>failedFuture(linkNotFound(linkId))
                        : query(tx, "SELECT id_graph FROM GRAPHS WHERE id_graph = ? FOR UPDATE",
                                Tuple.of(rows.iterator().next().getLong("id_graph"))))
                .compose(r -> query(tx, "SELECT id_graph, id_element, is_tombstone FROM LINKS WHERE id_link = ? FOR UPDATE",
                        Tuple.of(linkId)))
                .compose(rows -> {
                    if (rows.size() == 0) {
                        return Future.failedFuture(linkNotFound(linkId));
                    }
                    Row row = rows.iterator().next();
                    return Future.succeededFuture(new LinkRow(linkId, row.getLong("id_graph"),
                            row.getLong("id_element"), row.getInteger("is_tombstone") != 0));
                });
    }

    private static ViUnSourceException linkNotFound(Long linkId) {
        return new ViUnSourceException.NotFound("Link " + linkId + " not found");
    }

    // ------------------------------------------------------------------
    // Locking
    // ------------------------------------------------------------------

    private Future<UnionRow> lockUnion(SqlConnection tx, String unionName) {
        return query(tx, "SELECT id_union, is_tombstone, id_union_metadata_element FROM UNIONS "
                + "WHERE union_name = ? FOR UPDATE", Tuple.of(unionName))
                .compose(rows -> {
                    if (rows.size() == 0) {
                        return Future.failedFuture(new ViUnSourceException.NotFound("Union '" + unionName + "' not found"));
                    }
                    Row row = rows.iterator().next();
                    return Future.succeededFuture(new UnionRow(row.getLong("id_union"),
                            row.getInteger("is_tombstone") != 0, row.getLong("id_union_metadata_element")));
                });
    }

    /** Locks the (single) union row, serializing changes to the set of graphs. Returns the union id. */
    private Future<Long> lockSingletonUnion(SqlConnection tx) {
        return query(tx, "SELECT id_union FROM UNIONS FOR UPDATE", Tuple.tuple())
                .compose(rows -> rows.size() == 0
                        ? Future.failedFuture(new ViUnSourceException.NotFound("Union not found"))
                        : Future.succeededFuture(rows.iterator().next().getLong("id_union")));
    }

    private Future<GraphRow> lockGraph(SqlConnection tx, String graphName) {
        return query(tx, "SELECT id_graph, is_tombstone, id_graph_metadata_element FROM GRAPHS "
                + "WHERE graph_name = ? FOR UPDATE", Tuple.of(graphName))
                .compose(rows -> rows.size() == 0
                        ? Future.failedFuture(new ViUnSourceException.NotFound("Graph '" + graphName + "' not found"))
                        : Future.succeededFuture(graphRow(rows.iterator().next())));
    }

    private Future<List<GraphRow>> lockAllGraphs(SqlConnection tx) {
        return query(tx, "SELECT id_graph, is_tombstone, id_graph_metadata_element FROM GRAPHS "
                + "ORDER BY id_graph FOR UPDATE", Tuple.tuple())
                .map(rows -> {
                    List<GraphRow> graphs = new ArrayList<>();
                    rows.forEach(row -> graphs.add(graphRow(row)));
                    return graphs;
                });
    }

    private Future<Void> lockGraphs(SqlConnection tx, List<Long> graphIds) {
        if (graphIds.isEmpty()) {
            return Future.succeededFuture();
        }
        return query(tx, "SELECT id_graph FROM GRAPHS WHERE id_graph IN (" + placeholders(graphIds.size())
                + ") ORDER BY id_graph FOR UPDATE", tupleOf(null, graphIds)).mapEmpty();
    }

    private Future<Void> lockElementOfKind(SqlConnection tx, Kind kind, Long id) {
        return query(tx, "SELECT e.id_element FROM ELEMENTS e JOIN " + kind.table + " t ON t." + kind.idColumn
                + " = e.id_element WHERE e.id_element = ? FOR UPDATE", Tuple.of(id))
                .compose(rows -> rows.size() == 0
                        ? Future.failedFuture(new ViUnSourceException.NotFound(kind.displayName + " " + id + " not found"))
                        : Future.succeededFuture());
    }

    /**
     * Writes the {@code sync} field of a referenced element: verifies it exists and is of the expected kind,
     * locks it, and makes concurrent conflicting writers (e.g. its deletion) fail Galera certification.
     */
    private Future<Void> touch(SqlConnection tx, Kind kind, Long id, long syncValue) {
        return query(tx, "UPDATE ELEMENTS e JOIN " + kind.table + " t ON t." + kind.idColumn
                + " = e.id_element SET e.sync = ? WHERE e.id_element = ?", Tuple.of(syncValue, id))
                .compose(r -> r.rowCount() == 0
                        ? Future.failedFuture(new ViUnSourceException.NotFound(kind.displayName + " " + id + " not found"))
                        : Future.succeededFuture());
    }

    // ------------------------------------------------------------------
    // Elements
    // ------------------------------------------------------------------

    /** Inserts an element whose id equals its creation version. */
    private Future<Long> insertElement(SqlConnection tx, long version, Element element) {
        return query(tx, "INSERT INTO ELEMENTS (id_element, `key`, content, version) VALUES (?, ?, ?, ?)",
                Tuple.of(version, element.key(), element.content(), version))
                .map(r -> version);
    }

    private Future<Long> insertOptionalElement(SqlConnection tx, long version,
                                                         @Nullable Element element) {
        return element == null ? Future.succeededFuture(null) : insertElement(tx, version, element);
    }

    /** A {@code null} element clears the element's key and content. */
    private Future<Void> updateElement(SqlConnection tx, Long id, long version, @Nullable Element element) {
        return query(tx, "UPDATE ELEMENTS SET `key` = ?, content = ?, version = ? WHERE id_element = ?",
                Tuple.of(element == null ? null : element.key(), element == null ? null : element.content(),
                        version, id))
                .mapEmpty();
    }

    /** Updates the metadata element if present, otherwise creates it. Returns the element id. */
    private Future<Long> upsertMetadataElement(SqlConnection tx, @Nullable Long metadataElementId,
                                               long version, Element element) {
        return metadataElementId == null
                ? insertElement(tx, version, element)
                : updateElement(tx, metadataElementId, version, element).map(v -> metadataElementId);
    }

    /** Deletes a (locked) graph together with its links and metadata element. */
    private Future<Void> deleteGraphRow(SqlConnection tx, GraphRow graph) {
        return query(tx, "SELECT id_link FROM LINKS WHERE id_graph = ?", Tuple.of(graph.id()))
                .compose(rows -> deleteElements(tx, longs(rows, "id_link")))
                .compose(v -> query(tx, "DELETE FROM GRAPHS WHERE id_graph = ?", Tuple.of(graph.id())))
                .compose(r -> deleteElements(tx, Collections.singletonList(graph.metadataElementId())));
    }

    /** Deletes elements by id (cascading to subtype tables and LINKS); {@code null} ids are skipped. */
    private Future<Void> deleteElements(SqlConnection tx, Collection<Long> ids) {
        List<Long> nonNullIds = new ArrayList<>();
        for (Long id : ids) {
            if (id != null) {
                nonNullIds.add(id);
            }
        }
        Future<Void> result = Future.succeededFuture();
        for (int from = 0; from < nonNullIds.size(); from += IN_CLAUSE_CHUNK_SIZE) {
            List<Long> chunk = nonNullIds.subList(from, Math.min(from + IN_CLAUSE_CHUNK_SIZE, nonNullIds.size()));
            result = result.compose(v -> query(tx, "DELETE FROM ELEMENTS WHERE id_element IN ("
                    + placeholders(chunk.size()) + ")", tupleOf(null, chunk)).mapEmpty());
        }
        return result;
    }

    private Future<Void> bumpGraphElementVersion(SqlConnection tx, long graphId, long version) {
        return query(tx, "UPDATE GRAPHS SET last_element_update_version = ? WHERE id_graph = ?",
                Tuple.of(version, graphId)).mapEmpty();
    }

    private Future<Void> bumpGraphLinkSyncVersion(SqlConnection tx, long graphId, long version) {
        return query(tx, "UPDATE GRAPHS SET last_link_sync_version = ? WHERE id_graph = ?",
                Tuple.of(version, graphId)).mapEmpty();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static Future<RowSet<Row>> query(SqlConnection tx, String sql, Tuple args) {
        return tx.preparedQuery(sql).execute(args);
    }

    private static <T> CompletableFuture<T> toCf(Future<T> future) {
        return future.toCompletionStage().toCompletableFuture();
    }

    private static String placeholders(int count) {
        return String.join(", ", Collections.nCopies(count, "?"));
    }

    private static Tuple tupleOf(@Nullable Object first, Collection<Long> rest) {
        Tuple tuple = Tuple.tuple();
        if (first != null) {
            tuple.addValue(first);
        }
        rest.forEach(tuple::addValue);
        return tuple;
    }

    private static List<Long> longs(RowSet<Row> rows, String column) {
        List<Long> result = new ArrayList<>();
        rows.forEach(row -> result.add(row.getLong(column)));
        return result;
    }

    private static @Nullable String schemaTypeName(@Nullable SchemaType schemaType) {
        return schemaType == null ? null : schemaType.name();
    }

    private static GraphRow graphRow(Row row) {
        return new GraphRow(row.getLong("id_graph"), row.getInteger("is_tombstone") != 0,
                row.getLong("id_graph_metadata_element"));
    }

    private enum Kind {
        VERTEX_TYPE("VERTEX_TYPES", "id_vertex_type", "Vertex type"),
        VERTEX("VERTEXES", "id_vertex", "Vertex"),
        EDGE_TYPE("EDGE_TYPES", "id_edge_type", "Edge type"),
        EDGE("EDGES", "id_edge", "Edge");

        final String table;
        final String idColumn;
        final String displayName;

        Kind(String table, String idColumn, String displayName) {
            this.table = table;
            this.idColumn = idColumn;
            this.displayName = displayName;
        }
    }

    private record UnionRow(long id, boolean isTombstone, @Nullable Long metadataElementId) {
    }

    private record GraphRow(long id, boolean isTombstone, @Nullable Long metadataElementId) {
    }

    private record LinkRow(long id, long graphId, Long elementId, boolean isTombstone) {
    }
}
