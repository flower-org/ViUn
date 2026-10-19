package com.viun.mysql;

import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.SqlConnection;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/** Runs {@link MySqlViUnSource} operations within transactions. */
public final class MySqlTransactions {
    private MySqlTransactions() {
    }

    /**
     * Runs {@code work} within a new transaction on a pooled connection, committing when the returned
     * future succeeds and rolling back when it fails; the connection is always returned to the pool.
     */
    public static <T> CompletableFuture<T> inTransaction(Pool pool,
                                                         Function<SqlConnection, CompletableFuture<T>> work) {
        return pool.<T>withTransaction(connection -> {
            CompletableFuture<T> result;
            try {
                result = work.apply(connection);
            } catch (RuntimeException e) {
                return Future.failedFuture(e);
            }
            Context context = Vertx.currentContext();
            return context != null ? Future.fromCompletionStage(result, context) : Future.fromCompletionStage(result);
        }).toCompletionStage().toCompletableFuture();
    }
}
