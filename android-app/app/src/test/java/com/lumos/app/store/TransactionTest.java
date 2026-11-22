/*
 * Lumos - android-app/app / Several record writes are one transaction, with Android's nesting semantics.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-LOC-004, TRS-PAT-004, TRS-TST-001
 * ADRs: ADR-003
 * Threading: test-only
 * Notes: TRS Sec. 8.3 needs a message and its outbox item to land together.
 * LocalStore.transaction makes several puts one transaction; each put's own
 * transaction joins it. The rule these tests pin is Android SQLite's: a
 * failure in ANY nested write rolls back EVERYTHING, even when the outer body
 * catches the exception and carries on - so the JVM test engine and the
 * device engine agree.
 */

package com.lumos.app.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.lumos.app.Fixtures;
import org.junit.jupiter.api.Test;

/** LocalStore.transaction. */
public class TransactionTest {

    private static final byte[] TAG = {1, 2, 3};

    @Test
    void everyWriteInsideLandsTogether() throws Exception {
        try (Fixtures.Stack s = Fixtures.openInMemory()) {
            s.store().transaction(() -> {
                s.store().put(RecordKind.CHAT_MESSAGE, null, new byte[] {1}, TAG);
                s.store().put(RecordKind.OUTBOX_ITEM, null, new byte[] {2}, null);
                return null;
            });
            assertEquals(1, s.store().count(RecordKind.CHAT_MESSAGE));
            assertEquals(1, s.store().count(RecordKind.OUTBOX_ITEM));
        }
    }

    @Test
    void aFailureAfterTheFirstWriteRollsBackTheFirstWriteToo() throws Exception {
        try (Fixtures.Stack s = Fixtures.openInMemory()) {
            assertThrows(StoreException.class, () -> s.store().transaction(() -> {
                s.store().put(RecordKind.CHAT_MESSAGE, null, new byte[] {1}, TAG);
                // A tag-keyed kind without its tag: refused by put.
                s.store().put(RecordKind.RATCHET_SESSION, null, new byte[] {2}, null);
                return null;
            }));
            assertEquals(0, s.store().count(RecordKind.CHAT_MESSAGE), "the message must not outlive its failed sibling");
        }
    }

    @Test
    void aNestedFailureCaughtByTheOuterBodyStillRollsEverythingBack() throws Exception {
        try (Fixtures.Stack s = Fixtures.openInMemory()) {
            assertThrows(StoreException.class, () -> s.store().transaction(() -> {
                s.store().put(RecordKind.CHAT_MESSAGE, null, new byte[] {1}, TAG);
                try {
                    s.store().transaction(() -> {
                        throw new StoreException(StoreException.Reason.ENGINE, "inner failure");
                    });
                } catch (final StoreException swallowed) {
                    // The outer body carries on, as careless code might.
                }
                s.store().put(RecordKind.OUTBOX_ITEM, null, new byte[] {2}, null);
                return null;
            }));
            assertEquals(0, s.store().count(RecordKind.CHAT_MESSAGE));
            assertEquals(0, s.store().count(RecordKind.OUTBOX_ITEM));
        }
    }
}
