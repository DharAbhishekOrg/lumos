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
 * message it answers. A reply is stored as a text for now; the stored link
 * to the message it answers, and the quote drawn from it, arrive with the
 * reply screens. Kept out of ChatStore for the TRS-CODE-004 class budget.
 */

package com.lumos.app.chat;

import com.lumos.protocol.TextEventKind;
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
