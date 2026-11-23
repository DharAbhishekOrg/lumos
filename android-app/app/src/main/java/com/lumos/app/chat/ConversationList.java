/*
 * Lumos - android-app/app / Every conversation on this device, summarised for the Matches and Chats lists.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-RMT-001, SRS-RMT-005
 * ADRs: ADR-003
 * Threading: serial-executor
 * Notes: One read-only pass over the store: each pinned conversation with its
 * newest message and how many of the peer's messages are still unread
 * (delivered here, not yet read; markRead is what turns them read). A
 * conversation with no messages yet is a new match, which the Matches tab
 * shows; one with messages belongs to the Chats tab. Nothing is decrypted
 * beyond what ChatStore already keeps sealed at rest and opens per call, and
 * nothing is cached between calls. Kept out of ChatStore for the TRS-CODE-004
 * class budget.
 */

package com.lumos.app.chat;

import com.lumos.app.store.LocalStore;
import com.lumos.app.store.RecordKind;
import com.lumos.app.store.StoreException;
import com.lumos.protocol.chat.DeliveryMachine;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Summarises the device's conversations. */
public final class ConversationList {

    /**
     * One conversation, as a list row needs it.
     *
     * @param matchId the conversation
     * @param last its newest message, or empty for a new match with nothing said yet
     * @param unread how many of the peer's messages are delivered here and not yet read
     */
    public record Summary(byte[] matchId, Optional<ChatRecords.Message> last, int unread) {

        /**
         * Copies.
         *
         * @param matchId see the record
         * @param last see the record
         * @param unread see the record
         */
        public Summary {
            matchId = matchId.clone();
        }

        /** @return a copy of the match id */
        @Override
        public byte[] matchId() {
            return matchId.clone();
        }

        /** @return when the newest message was sent or received, or 0 for a new match */
        public long lastActivityMillis() {
            return last.map(ChatRecords.Message::createdAtMillis).orElse(0L);
        }
    }

    private final LocalStore store;
    private final ChatStore chat;

    private ConversationList(final LocalStore store, final ChatStore chat) {
        this.store = store;
        this.chat = chat;
    }

    /**
     * Binds the list to the store the chat store lives in.
     *
     * @param store the sealed local store
     * @param chat the chat store
     * @return the list
     */
    public static ConversationList over(final LocalStore store, final ChatStore chat) {
        return new ConversationList(store, chat);
    }

    /**
     * Every conversation, newest activity first. New matches (nothing said
     * yet) come last, in a stable order.
     *
     * @return the summaries
     * @throws StoreException on engine failure
     */
    public List<Summary> all() throws StoreException {
        final List<Summary> out = new ArrayList<>();
        for (final LocalStore.Entry e : store.allOf(RecordKind.CONVERSATION)) {
            final byte[] matchId = ChatRecords.Conversation.decode(e.content()).matchId();
            final List<ChatRecords.Message> messages = chat.messages(matchId);
            int unread = 0;
            for (final ChatRecords.Message m : messages) {
                if (!m.outgoing() && m.state() == DeliveryMachine.State.DELIVERED) {
                    unread++;
                }
            }
            final Optional<ChatRecords.Message> last = messages.isEmpty()
                    ? Optional.empty() : Optional.of(messages.get(messages.size() - 1));
            out.add(new Summary(matchId, last, unread));
        }
        out.sort(Comparator.comparingLong(Summary::lastActivityMillis).reversed()
                .thenComparing(s -> ChatRecords.rowId(s.matchId())));
        return out;
    }
}
