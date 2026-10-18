package com.viun.core.source;

import com.viun.core.model.*;

import javax.annotation.Nullable;

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

    void createUnion(TRANSACTION_CONTEXT transactionContext, String unionName, @Nullable Element unionMetadataElement);

    /** Updates union metadata and/or its content. */
    void upsertUnionMetadata(TRANSACTION_CONTEXT transactionContext, String unionName, Element updatedUnionMetadataElement);

    void tombstoneUnion(TRANSACTION_CONTEXT transactionContext, String unionName);

    void reviveUnion(TRANSACTION_CONTEXT transactionContext, String unionName);

    void deleteUnion(TRANSACTION_CONTEXT transactionContext, String unionName);

    // ------------------------------------------------------------------
    // Graph
    // ------------------------------------------------------------------

    void createGraph(TRANSACTION_CONTEXT transactionContext, String graphName, @Nullable Element graphMetadataElement);

    /** Updates graph metadata and/or its content. */
    void upsertGraphMetadata(TRANSACTION_CONTEXT transactionContext, String graphName, Element updatedGraphMetadataElement);

    void tombstoneGraph(TRANSACTION_CONTEXT transactionContext, String graphName);

    void reviveGraph(TRANSACTION_CONTEXT transactionContext, String graphName);

    void deleteGraph(TRANSACTION_CONTEXT transactionContext, String graphName);

    // ------------------------------------------------------------------
    // VertexType / Vertex / EdgeType / Edge
    //
    // These elements have no tombstone/revive lifecycle. A delete is only
    // permitted for an element that is not referenced by any link.
    // ------------------------------------------------------------------

    ID createVertexType(TRANSACTION_CONTEXT transactionContext, Element vertexTypeElement, @Nullable SchemaType schemaType);

    /** Updates vertex type content. or schema type */
    void updateVertexType(TRANSACTION_CONTEXT transactionContext, ID vertexTypeId, @Nullable Element updatedVertexTypeElement);

    /** Deletes a vertex type. Only permitted when it is not referenced by any link. */
    void deleteVertexType(TRANSACTION_CONTEXT transactionContext, ID vertexTypeId);

    ID createVertex(TRANSACTION_CONTEXT transactionContext, ID vertexTypeId, Element vertexElement);

    /** Updates vertex content. */
    void updateVertex(TRANSACTION_CONTEXT transactionContext, ID vertexId, @Nullable Element vertexElement);

    /** Deletes a vertex. Only permitted when it is not referenced by any link. */
    void deleteVertex(TRANSACTION_CONTEXT transactionContext, ID vertexId);

    ID createEdgeType(TRANSACTION_CONTEXT transactionContext, Element edgeTypeElement, @Nullable Boolean isDirected,
                      @Nullable SchemaType schemaType);

    /** Updates edge type content. */
    void updateEdgeType(TRANSACTION_CONTEXT transactionContext, ID edgeTypeId, @Nullable Element edgeTypeElement);

    /** Deletes an edge type. Only permitted when it is not referenced by any link. */
    void deleteEdgeType(TRANSACTION_CONTEXT transactionContext, ID edgeTypeId);

    ID createEdge(TRANSACTION_CONTEXT transactionContext, ID edgeTypeId, ID vertexFrom, ID vertexTo, Element edgeElement);

    /** Updates edge content. */
    void updateEdge(TRANSACTION_CONTEXT transactionContext, ID edgeId, @Nullable Element edgeElement);

    /** Deletes an edge. Only permitted when it is not referenced by any link. */
    void deleteEdge(TRANSACTION_CONTEXT transactionContext, ID edgeId);

    // ------------------------------------------------------------------
    // Link (scoped to a graph)
    // ------------------------------------------------------------------

    ID createLink(TRANSACTION_CONTEXT transactionContext, String graphName, ID linkedElementId, Element linkElement);

    /** Updates link content. */
    void updateLink(TRANSACTION_CONTEXT transactionContext, ID linkId, @Nullable Element linkElement);

    void tombstoneLink(TRANSACTION_CONTEXT transactionContext, ID linkId);

    void reviveLink(TRANSACTION_CONTEXT transactionContext, ID linkId);

    /** Permanently removes a link that is currently tombstoned. */
    void deleteTombstonedLink(TRANSACTION_CONTEXT transactionContext, ID linkId);

    /** Permanently removes every tombstoned link of the given graph. */
    void deleteTombstonedLinksForGraph(TRANSACTION_CONTEXT transactionContext, String graphName);
}
