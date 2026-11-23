/*
 * Lumos - android-app/app / What the conversation screen needs from the running app.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-RMT-001, SRS-RMT-003, SRS-RMT-010, SRS-RMT-014
 * ADRs: ADR-012
 * Threading: serial-executor
 * Notes: The seam between the screen and the chat stack, as NearbyHost is for
 * Nearby: the activity never touches ChatStore, so every decision the screen
 * makes is tested on the JVM against a scripted source. Every method is called
 * on the app's one relay executor (the chat store's threading contract), never
 * on the main thread. RuntimeConversationSource is the real implementation.
 */

package com.lumos.app.conversation;

import com.lumos.app.chat.ChatRecords;
import com.lumos.protocol.chat.RemoteText;
import java.util.List;

/** The chat stack, as one conversation screen sees it. */
public interface ConversationSource {

    /** What happened to a send. */
    enum SendOutcome {
        /** Sealed into the outbox; it goes out on the next pass. */
        QUEUED,
        /** No session can start yet: the peer's address or keys have not arrived. Nothing was stored. */
        NOT_READY,
        /** Refused; nothing was stored. */
        FAILED
    }

    /**
     * Whether this conversation may be shown: it exists and the chat stack is running.
     *
     * @param matchId the conversation
     * @return true to open
     */
    boolean entryAllowed(byte[] matchId);

    /**
     * Who the conversation is with.
     *
     * @param matchId the conversation
     * @return the peer as the screen shows them
     */
    PeerSummary peer(byte[] matchId);

    /**
     * The conversation's messages, oldest first.
     *
     * @param matchId the conversation
     * @return the messages
     */
    List<ChatRecords.Message> messages(byte[] matchId);

    /** @return whether the device has a working internet connection right now */
    boolean online();

    /**
     * Sends one event (a text, a reaction, a delete for everyone).
     *
     * @param matchId the conversation
     * @param event the event
     * @return what happened
     */
    SendOutcome send(byte[] matchId, RemoteText.TextEvent event);

    /**
     * Removes a message from this device only.
     *
     * @param messageId the message
     * @return true when removed; false when it is still waiting to go out
     */
    boolean deleteForMe(byte[] messageId);

    /**
     * Marks everything received as read, sending read receipts only if the person allows them.
     *
     * @param matchId the conversation
     */
    void markRead(byte[] matchId);
}
