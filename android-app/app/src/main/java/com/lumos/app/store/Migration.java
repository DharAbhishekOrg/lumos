/*
 * Lumos - android-app/app / One forward-only schema migration.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-LOC-003
 * ADRs: -
 * Threading: any
 * Notes: A value, not a procedure: a from-version, a to-version and the ordered
 * statements between them. Keeping migrations declarative is what lets the
 * migration suite execute the real ladder against a real SQLite engine in CI
 * and lets tools/schemacheck compare it with the Swift ladder statement for
 * statement.
 */

package com.lumos.app.store;

import java.util.Collections;
import java.util.List;

/** One step of the forward-only migration ladder. */
public final class Migration {

    private final int fromVersion;
    private final int toVersion;
    private final List<String> statements;

    /**
     * Declares a migration step.
     *
     * @param fromVersion the version this step upgrades from
     * @param toVersion the version this step produces
     * @param statements the ordered SQL statements
     */
    public Migration(final int fromVersion, final int toVersion, final List<String> statements) {
        this.fromVersion = fromVersion;
        this.toVersion = toVersion;
        this.statements = Collections.unmodifiableList(new java.util.ArrayList<>(statements));
    }

    /**
     * The version this step upgrades from.
     *
     * @return the source version
     */
    public int fromVersion() {
        return fromVersion;
    }

    /**
     * The version this step produces.
     *
     * @return the target version
     */
    public int toVersion() {
        return toVersion;
    }

    /**
     * The ordered statements.
     *
     * @return an unmodifiable list of SQL statements
     */
    public List<String> statements() {
        return statements;
    }
}
