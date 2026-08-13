/*
 * Lumos - android-app/app / The store's single failure type.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-CODE-008, TRS-CRY-013
 * ADRs: -
 * Threading: any
 * Notes: One checked exception, so no store failure can be ignored by accident
 * (TRS-CODE-008). The reason is an enum rather than free text because callers
 * must branch on LOW_STORAGE - refusing a write is a recoverable, user-visible
 * state - while every cryptographic failure collapses into UNREADABLE, which
 * says nothing about WHICH check failed. A message that distinguished a bad tag
 * from a wrong key from a truncated row would be an oracle (TRS-CRY-013).
 */

package com.lumos.app.store;

/** Every way the local store can fail, in the granularity callers may see. */
public final class StoreException extends Exception {

    private static final long serialVersionUID = 1L;

    /** Why the operation failed. */
    public enum Reason {
        /** The engine refused the statement, or the file is unusable. */
        ENGINE,
        /** A row could not be opened: tampered, wrong key, wrong context. */
        UNREADABLE,
        /** Free space is below the floor a write needs (TRS-LOC-006). */
        LOW_STORAGE,
        /** The database is newer than this build; forward-only means refuse. */
        SCHEMA_FROM_THE_FUTURE,
        /** The migration ladder itself is malformed. */
        MIGRATION_LADDER
    }

    /** Not transient: an enum is serializable, and a deserialised failure with
     * a null reason would be a failure nobody can branch on. */
    private final Reason reason;

    /**
     * Creates a failure.
     *
     * @param reason the branchable cause
     * @param detail a message safe to log: never key material, never plaintext
     */
    public StoreException(final Reason reason, final String detail) {
        super(detail);
        this.reason = reason;
    }

    /**
     * Creates a failure wrapping an engine error.
     *
     * @param reason the branchable cause
     * @param detail a message safe to log
     * @param cause the underlying failure
     */
    public StoreException(final Reason reason, final String detail, final Throwable cause) {
        super(detail, cause);
        this.reason = reason;
    }

    /**
     * The branchable cause.
     *
     * @return the reason
     */
    public Reason reason() {
        return reason;
    }
}
