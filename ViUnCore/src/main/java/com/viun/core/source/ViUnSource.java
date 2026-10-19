package com.viun.core.source;

import com.viun.core.model.*;

import javax.annotation.Nullable;
import java.util.concurrent.CompletableFuture;

/**
 * A mutable ViUn source: the authoritative set of write operations that mutate a family of
 * unions and advance its version.
 *
 * <p>Every mutation returns the {@link FamilyVersion} the source reached after applying the
 * operation.
 *
 * <p>The ViUn source is expected to be backed by some transactional store, but the concrete
 * store/technology is deliberately left open. Operations that only make sense when applied
 * atomically together must share a single transaction context; the caller obtains one from the
 * concrete implementation and passes it to every participating operation.
 *
 * @param <VERSION> element/version type, globally monotonically increasing
 * @param <ID>      element identifier type
 * @param <TRANSACTION_CONTEXT>      concrete transaction context type this source operates within
 */
public interface ViUnSource<VERSION extends Comparable<VERSION>, ID, TRANSACTION_CONTEXT> {

    // TODO: think about returning version from each operation.

    // ------------------------------------------------------------------
    // Union
    // ------------------------------------------------------------------

    CompletableFuture<Void> createUnion(TRANSACTION_CONTEXT transactionContext, String unionName, @Nullable Element unionMetadataElement);

    /** Updates union metadata and/or its content. */
    CompletableFuture<Void> upsertUnionMetadata(TRANSACTION_CONTEXT transactionContext, String unionName, Element updatedUnionMetadataElement);

    CompletableFuture<Void> tombstoneUnion(TRANSACTION_CONTEXT transactionContext, String unionName);

    CompletableFuture<Void> reviveUnion(TRANSACTION_CONTEXT transactionContext, String unionName);

    /** Permanently removes the union. Only permitted when it is tombstoned. */
    CompletableFuture<Void> deleteUnion(TRANSACTION_CONTEXT transactionContext, String unionName);

    // ------------------------------------------------------------------
    // Graph
    // ------------------------------------------------------------------

    CompletableFuture<Void> createGraph(TRANSACTION_CONTEXT transactionContext, String graphName, @Nullable Element graphMetadataElement);

    /**` Updates graph metadata and/or its content. */
    CompletableFuture<Void> upsertGraphMetadata(TRANSACTION_CONTEXT transactionContext, String graphName, Element updatedGraphMetadataElement);

    CompletableFuture<Void> tombstoneGraph(TRANSACTION_CONTEXT transactionContext, String graphName);

    CompletableFuture<Void> reviveGraph(TRANSACTION_CONTEXT transactionContext, String graphName);

    CompletableFuture<Void> deleteGraph(TRANSACTION_CONTEXT transactionContext, String graphName);

    // ------------------------------------------------------------------
    // VertexType / Vertex / EdgeType / Edge
    //
    // These elements have no tombstone/revive lifecycle. A delete is only
    // permitted for an element that is not referenced by any link.
    // ------------------------------------------------------------------

    CompletableFuture<ID> createVertexType(TRANSACTION_CONTEXT transactionContext, Element vertexTypeElement, @Nullable SchemaType schemaType);

    /** Updates vertex type content. or schema type */
    CompletableFuture<Void> updateVertexType(TRANSACTION_CONTEXT transactionContext, ID vertexTypeId, @Nullable Element updatedVertexTypeElement);

    /** Deletes a vertex type. Only permitted when it is not referenced by any link. */
    CompletableFuture<Void> deleteVertexType(TRANSACTION_CONTEXT transactionContext, ID vertexTypeId);

    CompletableFuture<ID> createVertex(TRANSACTION_CONTEXT transactionContext, ID vertexTypeId, Element vertexElement);

    /** Updates vertex content. */
    CompletableFuture<Void> updateVertex(TRANSACTION_CONTEXT transactionContext, ID vertexId, @Nullable Element vertexElement);

    /** Deletes a vertex. Only permitted when it is not referenced by any link. */
    CompletableFuture<Void> deleteVertex(TRANSACTION_CONTEXT transactionContext, ID vertexId);

    CompletableFuture<ID> createEdgeType(TRANSACTION_CONTEXT transactionContext, Element edgeTypeElement, @Nullable Boolean isDirected,
                      @Nullable SchemaType schemaType);

    /** Updates edge type content. */
    CompletableFuture<Void> updateEdgeType(TRANSACTION_CONTEXT transactionContext, ID edgeTypeId, @Nullable Element edgeTypeElement);

    /** Deletes an edge type. Only permitted when it is not referenced by any link. */
    CompletableFuture<Void> deleteEdgeType(TRANSACTION_CONTEXT transactionContext, ID edgeTypeId);

    CompletableFuture<ID> createEdge(TRANSACTION_CONTEXT transactionContext, ID edgeTypeId, ID vertexFrom, ID vertexTo, Element edgeElement);

    /** Updates edge content. */
    CompletableFuture<Void> updateEdge(TRANSACTION_CONTEXT transactionContext, ID edgeId, @Nullable Element edgeElement);

    /** Deletes an edge. Only permitted when it is not referenced by any link. */
    CompletableFuture<Void> deleteEdge(TRANSACTION_CONTEXT transactionContext, ID edgeId);

    // ------------------------------------------------------------------
    // Link (scoped to a graph)
    // ------------------------------------------------------------------

    CompletableFuture<ID> createLink(TRANSACTION_CONTEXT transactionContext, String graphName, ID linkedElementId, Element linkElement);

    /** Updates link content. */
    CompletableFuture<Void> updateLink(TRANSACTION_CONTEXT transactionContext, ID linkId, @Nullable Element linkElement);

    CompletableFuture<Void> tombstoneLink(TRANSACTION_CONTEXT transactionContext, ID linkId);

    CompletableFuture<Void> reviveLink(TRANSACTION_CONTEXT transactionContext, ID linkId);

    /** Permanently removes a link that is currently tombstoned. */
    CompletableFuture<Void> deleteTombstonedLink(TRANSACTION_CONTEXT transactionContext, ID linkId);

    /** Permanently removes every tombstoned link of the given graph. */
    CompletableFuture<Void> deleteTombstonedLinksForGraph(TRANSACTION_CONTEXT transactionContext, String graphName);
}
