/*
 * Lumos - android-app/app / Tests: the dedup cache keeps to its id cap.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-NFR-SEC-006, TRS-LOC-005
 * ADRs: -
 * Threading: test-only
 * Notes: Over the cap, the ids closest to expiry go first and the newest
 * stay; at or under it, nothing is touched. Run with a small cap: the rule
 * is the same at the register's 10,000.
 */

package com.lumos.app.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lumos.app.Fixtures;
import com.lumos.app.store.LocalStore;
import com.lumos.app.store.RecordKind;
import org.junit.jupiter.api.Test;

class DedupCapTest {

    private static String put(final LocalStore store, final long expiresAt) throws Exception {
        final String id = ChatRecords.rowId(ChatRecords.newMessageId());
        store.put(RecordKind.MESSAGE_DEDUP, id, ChatRecords.encodeDedup(expiresAt), null);
        return id;
    }

    @Test
    void overTheCapTheOldestIdsGoAndTheNewestStay() throws Exception {
        try (Fixtures.Stack stack = Fixtures.openInMemory()) {
            final LocalStore store = stack.store();
            final String oldest = put(store, 1_000);
            final String older = put(store, 2_000);
            final String newer = put(store, 3_000);
            final String newest = put(store, 4_000);
            assertEquals(0, store.transaction(() -> ChatSessions.trimDedup(store, 4)), "at the cap: untouched");
            assertEquals(2, store.transaction(() -> ChatSessions.trimDedup(store, 2)));
            assertFalse(store.get(RecordKind.MESSAGE_DEDUP, oldest).isPresent());
            assertFalse(store.get(RecordKind.MESSAGE_DEDUP, older).isPresent());
            assertTrue(store.get(RecordKind.MESSAGE_DEDUP, newer).isPresent());
            assertTrue(store.get(RecordKind.MESSAGE_DEDUP, newest).isPresent());
        }
    }
}
