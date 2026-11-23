/*
 * Lumos - android-app/app / Removes everything one conversation left on this device.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-MAT-010, SRS-RMT-016, SRS-DAT-002
 * ADRs: ADR-001, ADR-003
 * Threading: serial-executor
 * Notes: Unmatch and block end a conversation; this is the part that forgets
 * it. It deletes the pinned conversation, every message, the ratchet session
 * and every send still waiting in the outbox, so nothing more goes to the
 * peer and nothing of theirs stays readable here (SRS-MAT-010: evidence is
 * kept only when a reporting user chooses it, and reporting is CR-007). It
 * opens no transaction of its own: the caller's transaction also marks the
 * relay route ending, so the conversation and its route end together or not
 * at all. The message-dedup ids stay until they expire: they hold no content,
 * only an id and an expiry, and they keep a replayed envelope from showing.
 * Kept out of ChatStore for the TRS-CODE-004 class budget.
 */

package com.lumos.app.chat;

import com.lumos.app.store.LocalStore;
import com.lumos.app.store.RecordKind;
import com.lumos.app.store.StoreException;
import java.util.Arrays;

/** Forgets one conversation on this device. */
public final class ConversationRemoval {

    private ConversationRemoval() {
        throw new AssertionError("no instances");
    }

    /**
     * Deletes the conversation, its messages, its session and its queued
     * sends. Call inside the caller's transaction.
     *
     * @param store the sealed local store
     * @param matchId the conversation
     * @return how many records were deleted
     * @throws StoreException on engine failure
     */
    public static int remove(final LocalStore store, final byte[] matchId) throws StoreException {
        int n = 0;
        for (final RecordKind kind : new RecordKind[] {RecordKind.CONVERSATION, RecordKind.CHAT_MESSAGE,
            RecordKind.RATCHET_SESSION}) {
            for (final LocalStore.Entry e : store.findEntriesByNaturalKey(kind, matchId)) {
                store.delete(kind, e.id());
                n++;
            }
        }
        for (final LocalStore.Entry e : store.allOf(RecordKind.OUTBOX_ITEM)) {
            if (Arrays.equals(ChatRecords.OutboxItem.decode(e.content()).matchId(), matchId)) {
                store.delete(RecordKind.OUTBOX_ITEM, e.id());
                n++;
            }
        }
        return n;
    }
}
