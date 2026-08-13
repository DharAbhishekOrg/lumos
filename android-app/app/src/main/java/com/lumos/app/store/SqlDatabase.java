/*
 * Lumos - android-app/app / The narrow seam between the store and a SQL engine.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-ARCH-003, TRS-LOC-003, SRS-DAT-002
 * ADRs: -
 * Threading: serial-executor
 * Notes: Six operations, chosen so that both android.database.sqlite and a JDBC
 * driver can implement them honestly. This is what lets the SAME store, the
 * SAME DDL and the SAME transaction semantics execute in CI against a real
 * SQLite engine and on a device against the platform's - rather than the CI
 * path testing a hand-written fake that could only ever agree with its author
 * (WP-01 closeout Sec. 5.2).
 *
 * `inTransaction` takes a unit of work rather than exposing begin/commit,
 * because crash consistency (TRS-LOC-003) depends on commit and rollback being
 * unskippable. A caller that could forget to commit would eventually forget.
 */

package com.lumos.app.store;

import java.util.List;

/** The minimum SQL surface the local store needs. */
public interface SqlDatabase extends AutoCloseable {

    /**
     * A unit of work that runs inside a transaction.
     *
     * @param <T> the work's result type
     */
    @FunctionalInterface
    interface TransactionBody<T> {
        /**
         * Performs the work.
         *
         * @return the work's result
         * @throws StoreException to roll the transaction back
         */
        T run() throws StoreException;
    }

    /**
     * Executes a statement that returns no rows.
     *
     * @param sql the statement
     * @throws StoreException on any engine failure
     */
    void execute(String sql) throws StoreException;

    /**
     * Executes a parameterised statement that returns no rows.
     *
     * @param sql the statement, with {@code ?} placeholders
     * @param arguments the bound values, in order
     * @throws StoreException on any engine failure
     */
    void execute(String sql, List<Object> arguments) throws StoreException;

    /**
     * Runs a parameterised query.
     *
     * @param sql the query, with {@code ?} placeholders
     * @param arguments the bound values, in order
     * @return the result rows, each a column-name-to-value map
     * @throws StoreException on any engine failure
     */
    List<java.util.Map<String, Object>> query(String sql, List<Object> arguments) throws StoreException;

    /**
     * Runs work inside a transaction, committing on return and rolling back on
     * any thrown exception.
     *
     * @param <T> the work's result type
     * @param body the unit of work
     * @return the work's result
     * @throws StoreException if the work fails or the transaction cannot complete
     */
    <T> T inTransaction(TransactionBody<T> body) throws StoreException;

    /**
     * Closes the underlying database.
     *
     * @throws StoreException on failure to close
     */
    @Override
    void close() throws StoreException;
}
