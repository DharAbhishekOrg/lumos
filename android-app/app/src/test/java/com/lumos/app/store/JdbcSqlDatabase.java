/*
 * Lumos - android-app/app / SqlDatabase over real SQLite, for CI.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-LOC-003, SRS-DAT-002, TRS-TST-001
 * ADRs: -
 * Threading: serial-executor
 * Notes: NOT a fake. This is a real SQLite engine reached through JDBC, so the
 * migration DDL and every statement the store issues are executed by the same
 * engine family that will run them on a device. A hand-written in-memory fake
 * would have been quicker and would have proved only that the store agrees
 * with a fake written by the same author - the WP-01 closeout Sec. 5.2 defect,
 * which this project has already paid for once.
 *
 * The file-backed constructor exists for one test in particular: the
 * encryption-at-rest assertion has to read the bytes SQLite actually wrote, and
 * an in-memory database has no bytes to read.
 */

package com.lumos.app.store;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SqlDatabase backed by sqlite-jdbc.
 *
 * <p>Public because test sources in other packages - the profile domain's, for
 * one - need a real engine behind the seam. It is a test source itself, so this
 * widens no production API.
 */
public final class JdbcSqlDatabase implements SqlDatabase {

    /** How deep the current transaction is nested; 0 outside one. */
    private int depth;
    /** Set when a nested body failed: the outermost transaction must roll back. */
    private boolean rollbackOnly;

    private final Connection connection;

    private JdbcSqlDatabase(final Connection connection) {
        this.connection = connection;
    }

    /**
     * Opens a database at a filesystem path.
     *
     * @param path the database file
     * @return the adapter
     * @throws StoreException if the driver refuses
     */
    public static JdbcSqlDatabase atPath(final String path) throws StoreException {
        try {
            final Connection connection = DriverManager.getConnection("jdbc:sqlite:" + path);
            connection.setAutoCommit(true);
            return new JdbcSqlDatabase(connection);
        } catch (SQLException e) {
            throw new StoreException(StoreException.Reason.ENGINE, "cannot open database", e);
        }
    }

    /**
     * Opens a private in-memory database.
     *
     * @return the adapter
     * @throws StoreException if the driver refuses
     */
    public static JdbcSqlDatabase inMemory() throws StoreException {
        return atPath(":memory:");
    }

    @Override
    public void execute(final String sql) throws StoreException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw new StoreException(StoreException.Reason.ENGINE, "statement failed", e);
        }
    }

    @Override
    public void execute(final String sql, final List<Object> arguments) throws StoreException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, arguments);
            statement.execute();
        } catch (SQLException e) {
            throw new StoreException(StoreException.Reason.ENGINE, "statement failed", e);
        }
    }

    @Override
    public List<Map<String, Object>> query(final String sql, final List<Object> arguments)
            throws StoreException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, arguments);
            try (ResultSet results = statement.executeQuery()) {
                return readRows(results);
            }
        } catch (SQLException e) {
            throw new StoreException(StoreException.Reason.ENGINE, "query failed", e);
        }
    }

    /**
     * Materialises a result set.
     *
     * @param results the open result set
     * @return the rows
     * @throws SQLException on a driver failure
     */
    private static List<Map<String, Object>> readRows(final ResultSet results) throws SQLException {
        final ResultSetMetaData meta = results.getMetaData();
        final List<Map<String, Object>> rows = new ArrayList<>();
        while (results.next()) {
            final Map<String, Object> row = new LinkedHashMap<>();
            for (int c = 1; c <= meta.getColumnCount(); c++) {
                row.put(meta.getColumnLabel(c), results.getObject(c));
            }
            rows.add(row);
        }
        return rows;
    }

    /**
     * Binds ordered arguments.
     *
     * @param statement the prepared statement
     * @param arguments the values
     * @throws SQLException on a driver failure
     */
    private static void bind(final PreparedStatement statement, final List<Object> arguments)
            throws SQLException {
        for (int i = 0; i < arguments.size(); i++) {
            statement.setObject(i + 1, arguments.get(i));
        }
    }

    @Override
    public <T> T inTransaction(final TransactionBody<T> body) throws StoreException {
        if (depth > 0) {
            // Nested: join the outer transaction, as Android SQLite does. A
            // failure here dooms the whole transaction even if the caller
            // catches it, which is exactly the platform's behaviour.
            depth++;
            try {
                return body.run();
            } catch (StoreException | RuntimeException e) {
                rollbackOnly = true;
                throw e;
            } finally {
                depth--;
            }
        }
        try {
            connection.setAutoCommit(false);
            depth = 1;
            rollbackOnly = false;
            // A success flag rather than a catch: rollback then has to happen for
            // ANY escape, including an unchecked one, and setAutoCommit(true)
            // with a transaction still open would COMMIT the partial work it was
            // supposed to discard.
            boolean committed = false;
            try {
                final T result = body.run();
                if (rollbackOnly) {
                    throw new StoreException(StoreException.Reason.ENGINE,
                            "a nested transaction failed; the whole transaction is rolled back");
                }
                connection.commit();
                committed = true;
                return result;
            } finally {
                depth = 0;
                if (!committed) {
                    connection.rollback();
                }
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new StoreException(StoreException.Reason.ENGINE, "transaction failed", e);
        }
    }


    @Override
    public void close() throws StoreException {
        try {
            connection.close();
        } catch (SQLException e) {
            throw new StoreException(StoreException.Reason.ENGINE, "cannot close database", e);
        }
    }
}
