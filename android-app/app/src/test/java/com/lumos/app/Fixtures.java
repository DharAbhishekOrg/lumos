/*
 * Lumos - android-app/app / One opened store, for the tests that need a real one.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-TST-001
 * ADRs: -
 * Threading: any
 * Notes: Slice S4c is the first one whose tests need the whole stack standing
 * up - a KEK, a data-key hierarchy, a migrated database and a store over it -
 * in six different test classes. Six copies of that assembly is six places to
 * fix when the assembly changes, so it lives here once.
 *
 * The engine is the real one: JdbcSqlDatabase over sqlite-jdbc, running the
 * canonical migration ladder. Nothing here is a fake, and the WP-01 closeout
 * Sec. 5.2 lesson is why - a fixture that fakes the store would let every test
 * above it agree with a fake written by the same author.
 */

package com.lumos.app;

import com.lumos.app.data.DataKeyHierarchy;
import com.lumos.app.keystore.SoftwareKekStore;
import com.lumos.app.store.JdbcSqlDatabase;
import com.lumos.app.store.LocalStore;

/** A standing local store with its keys, for tests. */
public final class Fixtures {

    /** A KEK, a store over a real SQLite engine, and the database behind both. */
    public static final class Stack implements AutoCloseable {

        private final JdbcSqlDatabase database;
        private final SoftwareKekStore kek;
        private final LocalStore store;
        private final DataKeyHierarchy hierarchy;

        private Stack(final JdbcSqlDatabase database, final SoftwareKekStore kek,
                final LocalStore store, final DataKeyHierarchy hierarchy) {
            this.database = database;
            this.kek = kek;
            this.store = store;
            this.hierarchy = hierarchy;
        }

        /**
         * The key-encryption key.
         *
         * @return the software KEK, which is the only tier a JVM has
         */
        public SoftwareKekStore kek() {
            return kek;
        }

        /**
         * The opened store.
         *
         * @return the store
         */
        public LocalStore store() {
            return store;
        }

        /**
         * The data-key hierarchy the store's key came from.
         *
         * @return the hierarchy
         */
        public DataKeyHierarchy hierarchy() {
            return hierarchy;
        }

        /**
         * The raw database, for tests that reopen or inspect it.
         *
         * @return the engine adapter
         */
        public JdbcSqlDatabase database() {
            return database;
        }

        @Override
        public void close() throws Exception {
            database.close();
        }
    }

    /** A clock a test can wind by hand. */
    public static final class TestClock implements LocalStore.Clock {

        private long now;

        /**
         * Starts the clock.
         *
         * @param start the initial UTC milliseconds
         */
        public TestClock(final long start) {
            this.now = start;
        }

        /**
         * Moves the clock forward.
         *
         * @param millis how far
         */
        public void advance(final long millis) {
            now += millis;
        }

        @Override
        public long nowUtcMillis() {
            return now;
        }
    }

    private Fixtures() {
        throw new AssertionError("no instances");
    }

    /**
     * Opens an in-memory stack: a fresh KEK, a fresh root, a migrated database.
     *
     * @param clock the clock the store timestamps with
     * @return the stack, which the caller closes
     * @throws Exception if key generation or migration fails
     */
    public static Stack openInMemory(final LocalStore.Clock clock) throws Exception {
        final JdbcSqlDatabase database = JdbcSqlDatabase.inMemory();
        final SoftwareKekStore kek = SoftwareKekStore.create();
        final DataKeyHierarchy hierarchy = DataKeyHierarchy.create(kek);
        final LocalStore store = LocalStore.open(database,
                hierarchy.deriveKey(DataKeyHierarchy.DataDomain.DATABASE),
                () -> Long.MAX_VALUE, clock);
        return new Stack(database, kek, store, hierarchy);
    }

    /**
     * Opens an in-memory stack with a clock fixed at zero.
     *
     * @return the stack, which the caller closes
     * @throws Exception if key generation or migration fails
     */
    public static Stack openInMemory() throws Exception {
        return openInMemory(() -> 0L);
    }
}
