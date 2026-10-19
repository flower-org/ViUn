package com.viun.mysql;

import com.viun.core.model.SchemaType;
import com.viun.core.source.Element;
import io.vertx.core.Vertx;
import io.vertx.mysqlclient.MySQLBuilder;
import io.vertx.mysqlclient.MySQLConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlConnection;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.annotation.Nullable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Integration test; requires a MySQL database with {@code db/viun_schema_GENERATED.sql} applied.
 * Configure with env vars VIUN_MYSQL_HOST (required, else the test is skipped), VIUN_MYSQL_PORT,
 * VIUN_MYSQL_DATABASE, VIUN_MYSQL_USER, VIUN_MYSQL_PASSWORD. All tables are wiped before each test.
 */
public class MySqlViUnSourceIT {
    private static Vertx vertx;
    private static Pool pool;
    private static MySqlViUnSource source;

    @BeforeAll
    static void setUp() {
        String host = System.getenv("VIUN_MYSQL_HOST");
        assumeTrue(host != null, "VIUN_MYSQL_HOST not set");
        vertx = Vertx.vertx();
        MySQLConnectOptions options = new MySQLConnectOptions()
                .setHost(host)
                .setPort(Integer.parseInt(env("VIUN_MYSQL_PORT", "3306")))
                .setDatabase(env("VIUN_MYSQL_DATABASE", "viun"))
                .setUser(env("VIUN_MYSQL_USER", "viun"))
                .setPassword(env("VIUN_MYSQL_PASSWORD", "viun"));
        pool = MySQLBuilder.pool().with(new PoolOptions().setMaxSize(4)).connectingTo(options).using(vertx).build();
        source = new MySqlViUnSource();
    }

    @AfterAll
    static void tearDown() {
        if (vertx != null) {
            pool.close();
            vertx.close();
        }
    }

    @BeforeEach
    void wipe() throws Exception {
        for (String table : new String[]{"LINKS", "GRAPHS", "UNIONS", "EDGES", "VERTEXES", "EDGE_TYPES",
                "VERTEX_TYPES", "ELEMENTS", "VERSIONS"}) {
            sql("DELETE FROM " + table);
        }
    }

    @Test
    void unionAndGraphLifecycle() throws Exception {
        tx(t -> source.createUnion(t, "u", el("uk", "uc")));
        Row union = one("SELECT * FROM UNIONS");
        long unionMeta = union.getLong("id_union_metadata_element");
        assertEquals(unionMeta, union.getLong("last_element_update_version"));
        assertEquals("uc", one("SELECT content FROM ELEMENTS WHERE id_element = " + unionMeta).getString(0));

        assertFails(ViUnSourceException.class, t -> source.createUnion(t, "u2", null));

        Long unionVersion = tx(t -> source.upsertUnionMetadata(t, "u", el("uk2", "uc2")));
        union = one("SELECT * FROM UNIONS");
        assertEquals(unionMeta, union.getLong("id_union_metadata_element"));
        assertEquals("uc2", one("SELECT content FROM ELEMENTS WHERE id_element = " + unionMeta).getString(0));
        assertTrue(union.getLong("last_element_update_version") > unionMeta);
        assertEquals(union.getLong("last_element_update_version"), unionVersion);
        assertEquals(unionVersion, one("SELECT version FROM ELEMENTS WHERE id_element = " + unionMeta).getLong(0));

        tx(t -> source.createGraph(t, "g1", null));
        assertFails(ViUnSourceException.class, t -> source.createGraph(t, "g1", null));
        Row graph = one("SELECT * FROM GRAPHS WHERE graph_name = 'g1'");
        assertNull(graph.getLong("id_graph_metadata_element"));
        long created = graph.getLong("last_element_update_version");

        Long graphVersion = tx(t -> source.upsertGraphMetadata(t, "g1", el("gk", "gc")));
        graph = one("SELECT * FROM GRAPHS WHERE graph_name = 'g1'");
        assertNotNull(graph.getLong("id_graph_metadata_element"));
        assertTrue(graph.getLong("last_element_update_version") > created);
        assertEquals(graph.getLong("last_element_update_version"), graphVersion);
        assertEquals(graphVersion, graph.getLong("id_graph_metadata_element"));

        // update of an existing metadata element returns the new version, keeping the element id
        Long graphVersion2 = tx(t -> source.upsertGraphMetadata(t, "g1", el("gk2", "gc2")));
        assertTrue(graphVersion2 > graphVersion);
        graph = one("SELECT * FROM GRAPHS WHERE graph_name = 'g1'");
        assertEquals(graphVersion, graph.getLong("id_graph_metadata_element"));
        assertEquals(graphVersion2, graph.getLong("last_element_update_version"));

        tx(t -> source.tombstoneGraph(t, "g1"));
        assertFails(ViUnSourceException.class, t -> source.tombstoneGraph(t, "g1"));
        tx(t -> source.reviveGraph(t, "g1"));
        assertEquals(0, one("SELECT is_tombstone FROM GRAPHS").getInteger(0));

        tx(t -> source.tombstoneUnion(t, "u"));
        union = one("SELECT * FROM UNIONS");
        assertEquals(1, union.getInteger("is_tombstone"));
        assertEquals(union.getLong("tombstone_revive_version"),
                one("SELECT last_element_update_version FROM GRAPHS").getLong(0));
        tx(t -> source.reviveUnion(t, "u"));

        tx(t -> source.deleteGraph(t, "g1"));
        assertEquals(0, count("GRAPHS"));
        assertEquals(1, count("ELEMENTS"));

        assertFails(ViUnSourceException.class, t -> source.deleteUnion(t, "u"));
        long vt = tx(t -> source.createGraph(t, "g2", el("gk", "gc"))
                .thenCompose(v -> source.createVertexType(t, el("vt", null), null))
                .thenCompose(id -> source.createLink(t, "g2", id, el("l", null)).thenApply(l -> id)));
        tx(t -> source.tombstoneUnion(t, "u"));
        tx(t -> source.deleteUnion(t, "u"));
        assertEquals(0, count("UNIONS"));
        assertEquals(0, count("GRAPHS"));
        assertEquals(0, count("LINKS"));
        assertEquals(vt, one("SELECT id_element FROM ELEMENTS").getLong(0));
        assertFails(ViUnSourceException.NotFound.class, t -> source.deleteUnion(t, "u"));
    }

    @Test
    void elementsAndLinks() throws Exception {
        tx(t -> source.createUnion(t, "u", null)
                .thenCompose(v -> source.createGraph(t, "g", null)));

        long vt = tx(t -> source.createVertexType(t, el("vt", "vtc"), SchemaType.JSON_SCHEMA));
        long v1 = tx(t -> source.createVertex(t, vt, el("v1", null)));
        long v2 = tx(t -> source.createVertex(t, vt, el("v2", null)));
        long et = tx(t -> source.createEdgeType(t, el("et", null), true, null));
        long e = tx(t -> source.createEdge(t, et, v1, v2, el("e", null)));
        assertEquals("JSON_SCHEMA", one("SELECT schema_type FROM VERTEX_TYPES").getString(0));
        assertEquals(1, one("SELECT is_directed FROM EDGE_TYPES").getInteger(0));
        assertEquals(e, one("SELECT sync FROM ELEMENTS WHERE id_element = " + v2).getLong(0));

        assertFails(ViUnSourceException.NotFound.class, t -> source.createVertex(t, et, el("x", null)));
        assertFails(ViUnSourceException.NotFound.class, t -> source.updateVertex(t, vt, null));

        // vertex requires its type to be linked first
        assertFails(ViUnSourceException.class, t -> source.createLink(t, "g", v1, el("l", null)));

        // linking type, vertexes and edge in one transaction is fine
        long lE = tx(t -> source.createLink(t, "g", vt, el("lvt", null))
                .thenCompose(x -> source.createLink(t, "g", v1, el("lv1", null)))
                .thenCompose(x -> source.createLink(t, "g", v2, el("lv2", null)))
                .thenCompose(x -> source.createLink(t, "g", et, el("let", null)))
                .thenCompose(x -> source.createLink(t, "g", e, el("le", null))));
        assertEquals(5, count("LINKS"));
        assertEquals(lE, graphElementVersion());
        assertFails(ViUnSourceException.class, t -> source.createLink(t, "g", v1, el("dup", null)));

        // element update raises graph version
        tx(t -> source.updateVertex(t, v1, el("v1-1", "c")));
        long v1Version = one("SELECT version FROM ELEMENTS WHERE id_element = " + v1).getLong(0);
        assertTrue(v1Version > lE);
        assertEquals(v1Version, graphElementVersion());

        long lV1 = one("SELECT id_link FROM LINKS WHERE id_element = " + v1).getLong(0);
        tx(t -> source.updateLink(t, lV1, el("lv1-1", null)));
        assertEquals(one("SELECT version FROM ELEMENTS WHERE id_element = " + lV1).getLong(0), graphElementVersion());

        // can't tombstone a vertex link while its edge link is active; can't delete linked vertex
        assertFails(ViUnSourceException.class, t -> source.tombstoneLink(t, lV1));
        assertFails(ViUnSourceException.class, t -> source.deleteVertex(t, v1));
        assertFails(ViUnSourceException.class, t -> source.deleteTombstonedLink(t, lE));

        tx(t -> source.tombstoneLink(t, lE).thenCompose(x -> source.tombstoneLink(t, lV1)));
        // can't revive edge while its vertex link is tombstoned
        assertFails(ViUnSourceException.class, t -> source.reviveLink(t, lE));
        // can't delete vertex link while the (tombstoned) edge link exists
        assertFails(ViUnSourceException.class, t -> source.deleteTombstonedLink(t, lV1));
        tx(t -> source.reviveLink(t, lV1).thenCompose(x -> source.reviveLink(t, lE)));

        tx(t -> source.tombstoneLink(t, lE));
        tx(t -> source.deleteTombstonedLink(t, lE));
        Row graph = one("SELECT * FROM GRAPHS");
        assertTrue(graph.getLong("last_link_sync_version") > graph.getLong("last_element_update_version"));
        assertEquals(4, count("LINKS"));

        tx(t -> source.tombstoneLink(t, lV1));
        tx(t -> source.deleteTombstonedLinksForGraph(t, "g"));
        assertEquals(3, count("LINKS"));

        // vertex v1 is still referenced by edge e
        assertFails(ViUnSourceException.class, t -> source.deleteVertex(t, v1));
        tx(t -> source.deleteEdge(t, e).thenCompose(x -> source.deleteVertex(t, v1)));
        assertEquals(0, count("EDGES"));
        assertEquals(1, count("VERTEXES"));
    }

    @Test
    void concurrentUpdatesDoNotDeadlock() throws Exception {
        tx(t -> source.createUnion(t, "u", null)
                .thenCompose(v -> source.createGraph(t, "g1", null))
                .thenCompose(v -> source.createGraph(t, "g2", null)));
        long vt = tx(t -> source.createVertexType(t, el("vt", null), null)
                .thenCompose(id -> source.createLink(t, "g1", id, el("l", null))
                        .thenCompose(x -> source.createLink(t, "g2", id, el("l", null)))
                        .thenApply(x -> id)));
        java.util.List<CompletableFuture<?>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) {
            futures.add(MySqlTransactions.inTransaction(pool, t -> source.updateVertexType(t, vt, el("vt", "c"))));
            futures.add(MySqlTransactions.inTransaction(pool, t -> source.createVertex(t, vt, el("v", null))
                    .thenCompose(v -> source.createLink(t, "g1", v, el("l", null)))));
            futures.add(MySqlTransactions.inTransaction(pool, t -> source.upsertGraphMetadata(t, "g2", el("m", null))));
        }
        futures.add(MySqlTransactions.inTransaction(pool, t -> source.tombstoneUnion(t, "u")));
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(30, TimeUnit.SECONDS);
        assertEquals(22, count("LINKS"));
        long maxVersion = one("SELECT MAX(version) FROM ELEMENTS").getLong(0);
        Row g1 = one("SELECT * FROM GRAPHS WHERE graph_name = 'g1'");
        long tombstoneVersion = one("SELECT tombstone_revive_version FROM UNIONS").getLong(0);
        assertEquals(Math.max(maxVersionOfGraph("g1"), tombstoneVersion), g1.getLong("last_element_update_version"));
        assertTrue(maxVersion > 0);
    }

    private static long maxVersionOfGraph(String graphName) throws Exception {
        return one("SELECT GREATEST(MAX(le.version), MAX(e.version)) FROM LINKS l JOIN GRAPHS g ON g.id_graph = l.id_graph "
                + "JOIN ELEMENTS le ON le.id_element = l.id_link JOIN ELEMENTS e ON e.id_element = l.id_element "
                + "WHERE g.graph_name = '" + graphName + "'").getLong(0);
    }

    @Test
    void failedTransactionRollsBack() throws Exception {
        tx(t -> source.createUnion(t, "u", null));
        assertFails(ViUnSourceException.class, t -> source.createGraph(t, "g", null)
                .thenCompose(v -> source.createGraph(t, "g", null)));
        assertEquals(0, count("GRAPHS"));
    }

    // ------------------------------------------------------------------

    private static Element el(@Nullable String key, @Nullable String content) {
        return new Element() {
            @Override
            public @Nullable String key() {
                return key;
            }

            @Override
            public @Nullable String content() {
                return content;
            }
        };
    }

    private static <T> T tx(Function<SqlConnection, CompletableFuture<T>> work) throws Exception {
        return MySqlTransactions.inTransaction(pool, work).get(10, TimeUnit.SECONDS);
    }

    private static void assertFails(Class<? extends Throwable> expected,
                                    Function<SqlConnection, CompletableFuture<?>> work) {
        ExecutionException e = assertThrows(ExecutionException.class,
                () -> MySqlTransactions.inTransaction(pool, t -> work.apply(t).thenApply(x -> (Object) x)).get(10, TimeUnit.SECONDS));
        assertInstanceOf(expected, e.getCause(), () -> "Unexpected: " + e.getCause());
    }

    private static RowSet<Row> sql(String sql) throws Exception {
        return pool.query(sql).execute().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private static Row one(String sql) throws Exception {
        RowSet<Row> rows = sql(sql);
        assertEquals(1, rows.size(), sql);
        return rows.iterator().next();
    }

    private static long count(String table) throws Exception {
        return one("SELECT COUNT(*) FROM " + table).getLong(0);
    }

    private static long graphElementVersion() throws Exception {
        return one("SELECT last_element_update_version FROM GRAPHS").getLong(0);
    }

    private static String env(String name, String defaultValue) {
        String value = System.getenv(name);
        return value != null ? value : defaultValue;
    }
}
