/*
 * Lumos - android-app/app / Which text events are new messages, and how the others change an earlier one.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-RMT-011, SRS-RMT-009, SRS-UX-011
 * ADRs: ADR-003
 * Threading: any
 * Notes: TEXT and REPLY (CR-009) add a message; EDIT, DELETE_FOR_EVERYONE
 * and REACTION change the message they target. Each kind is named
 * explicitly: before CR-009 the last branch was a bare default that read
 * every other kind as a reaction, so a reply would have been applied to the
 * message it answers. A reply is stored as a new message carrying the id it
 * answers (replyTo); the quote is drawn from that id when shown, never from
 * anything sent. Kept out of ChatStore for the TRS-CODE-004 class budget.
 */

package com.lumos.app.chat;

import com.lumos.protocol.TextEventKind;
import com.lumos.protocol.chat.DeliveryMachine;
import com.lumos.protocol.chat.RemoteText;

/** The text-event rules ChatStore applies. */
final class ChatEvents {

    private ChatEvents() {
        throw new AssertionError("no instances");
    }

    /**
     * @param event an event
     * @return true for TEXT and REPLY, which add a message
     */
    static boolean isNewMessage(final RemoteText.TextEvent event) {
        return event.kind() == TextEventKind.TEXT || event.kind() == TextEventKind.REPLY;
    }

    /**
     * The message a TEXT or REPLY adds.
     *
     * @param id its id
     * @param matchId the conversation
     * @param mine true when this device sent it
     * @param event the TEXT or REPLY
     * @param createdAtMillis now
     * @param sequence its position in the conversation
     * @return the new message, QUEUED when mine and DELIVERED when the peer's
     */
    static ChatRecords.Message newMessage(final byte[] id, final byte[] matchId, final boolean mine,
            final RemoteText.TextEvent event, final long createdAtMillis, final long sequence) {
        final byte[] replyTo = event.kind() == TextEventKind.REPLY ? event.target() : new byte[0];
        return new ChatRecords.Message(id, matchId, mine, event.body(), createdAtMillis, sequence,
                mine ? DeliveryMachine.State.QUEUED : DeliveryMachine.State.DELIVERED, false, false, new byte[0],
                new byte[0], replyTo);
    }

    /**
     * Applies an event over the message it targets.
     *
     * @param t the target
     * @param event an EDIT, DELETE_FOR_EVERYONE or REACTION
     * @param mine true when this device sent the event
     * @return the changed target
     */
    static ChatRecords.Message applied(final ChatRecords.Message t, final RemoteText.TextEvent event,
            final boolean mine) {
        switch (event.kind()) {
            case EDIT:
                return t.withEdit(event.body());
            case DELETE_FOR_EVERYONE:
                return t.withDeleted();
            case REACTION:
                return t.withReaction(mine, event.body());
            default:
                throw new IllegalArgumentException("not an event over an earlier message: " + event.kind());
        }
    }
}
