/*
 * Lumos - android-app/app / Low-storage handling for writes.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-LOC-006, SRS-NFR-REL-002
 * ADRs: -
 * Threading: any
 * Notes: TRS-LOC-006 requires low-storage handling. The handling that matters
 * for a database is to REFUSE A WRITE EARLY rather than let SQLite discover it
 * has no room mid-transaction: a full disk during a commit is how a database
 * ends up wedged, and a user whose phone is full is exactly the user who cannot
 * afford to lose their profile recovering from it.
 *
 * A seam rather than a direct File.getUsableSpace call, so the refusal path is
 * TESTABLE. A low-storage branch nobody can exercise is a branch nobody knows
 * works, and this one only runs on the day it matters.
 *
 * The floor is a named local constant with its rationale, which TRS-CODE-005
 * permits for a value with no Sec. 23 register entry. It is deliberately NOT
 * invented into the register: the register is the protocol's frozen surface,
 * and a device-local storage floor is neither protocol nor signed-config
 * material. If calibration ever wants it (TRS-CONST-004), it moves there
 * through the change-control path rather than by being assumed there now.
 */

package com.lumos.app.store;

/** Decides whether there is room to write. */
public interface StorageCapacity {

    /**
     * The free-space floor below which writes are refused, in bytes.
     *
     * <p>16 MiB: comfortably more than the largest single transaction this
     * schema can produce (a profile with its media metadata is kilobytes), and
     * enough headroom for SQLite's journal and the OS's own needs during a
     * commit. Small enough that the app is not refusing writes on a device the
     * user still considers usable.
     */
    long WRITE_FLOOR_BYTES = 16L * 1024L * 1024L;

    /**
     * Usable space at the database's location.
     *
     * @return free bytes available to this application
     */
    long usableSpaceBytes();

    /**
     * Refuses the caller when free space is below {@link #WRITE_FLOOR_BYTES}.
     *
     * @throws StoreException with {@link StoreException.Reason#LOW_STORAGE}
     */
    default void requireRoomToWrite() throws StoreException {
        final long usable = usableSpaceBytes();
        if (usable < WRITE_FLOOR_BYTES) {
            throw new StoreException(StoreException.Reason.LOW_STORAGE,
                    "refusing to write: " + usable + " bytes free, floor is " + WRITE_FLOOR_BYTES);
        }
    }
}
