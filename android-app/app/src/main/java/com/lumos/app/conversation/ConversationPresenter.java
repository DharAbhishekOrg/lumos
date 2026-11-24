/*
 * Lumos - android-app/app / Every decision the conversation screen makes.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-RMT-003, SRS-RMT-005, SRS-RMT-010, SRS-RMT-011, SRS-SAF-001, SRS-UX-011, SRS-UX-012
 * ADRs: -
 * Threading: serial-executor
 * Notes: The activity only draws what this returns, so the rules are tested
 * on the JVM: a message may be sent when it has something besides
 * whitespace and fits one envelope; which actions a bubble offers (react
 * and copy unless deleted; delete for me unless it is still waiting to go,
 * because its sealed envelope would still be sent; delete for everyone only
 * for your own); and what a send's outcome means for the composer. Reactions
 * are a short fixed set, as in the large messengers, so a long-press is one
 * decision, not a keyboard.
 */

package com.lumos.app.conversation;

import com.lumos.app.chat.ChatRecords;
import com.lumos.app.relay.ConversationSafety;
import com.lumos.protocol.MessageClass;
import com.lumos.protocol.MessageClassLimits;
import com.lumos.protocol.TextEventKind;
import com.lumos.protocol.chat.DeliveryMachine;
import com.lumos.protocol.chat.RemoteText;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** The conversation screen's logic. */
public final class ConversationPresenter {

    /**
     * Bytes reserved for everything in an envelope that is not the text: the
     * message id, the event header, the ratchet header, the AEAD tag and, on
     * a first message, the PREKEY fields. Generous on purpose; a test seals a
     * maximum-length message as a first contact and checks it fits.
     */
    static final int FRAMING_ALLOWANCE_BYTES = 512;

    /** The most UTF-8 bytes one message's text may have. */
    public static final int MAX_TEXT_BYTES =
            (int) (MessageClassLimits.maxPayloadBytes(MessageClass.TEXT_MESSAGE) - FRAMING_ALLOWANCE_BYTES);

    /** The quick reactions, in the order they are offered. */
    public static final List<String> REACTIONS = List.of("❤️", "😂", "😮", "😢", "👍", "🙏");

    /** What a bubble's long-press offers. */
    public enum Action {
        /** Answer this message (CR-009). */
        REPLY,
        /** Add a reaction. */
        REACT,
        /** Copy the text. */
        COPY,
        /** Remove from this device only. */
        DELETE_FOR_ME,
        /** Remove for both people. */
        DELETE_FOR_EVERYONE
    }

    /** Why a send did or did not happen. */
    public enum SendResult {
        /** Queued; clear the composer. */
        SENT,
        /** Nothing but whitespace: nothing to send. */
        EMPTY,
        /** Over the limit: keep the text so nothing is lost. */
        TOO_LONG,
        /** The chat is not set up yet: keep the text and say it will be possible once set up. */
        NOT_READY,
        /** Refused: keep the text. */
        FAILED
    }

    /**
     * Everything the screen shows.
     *
     * @param open whether the conversation may be shown at all
     * @param peer who it is with
     * @param online whether the device is online
     * @param rows the conversation
     */
    public record Screen(boolean open, PeerSummary peer, boolean online, List<ConversationRows.Row> rows) {

        /**
         * @param open see the record
         * @param peer see the record
         * @param online see the record
         * @param rows see the record; copied, unmodifiable
         */
        public Screen {
            rows = List.copyOf(rows);
        }
    }

    private final ConversationSource source;
    private final byte[] matchId;

    /**
     * @param source the chat stack
     * @param matchId the conversation
     */
    public ConversationPresenter(final ConversationSource source, final byte[] matchId) {
        this.source = source;
        this.matchId = matchId.clone();
    }

    /**
     * Reads the conversation.
     *
     * @param nowMillis the current UTC time
     * @param zone the person's zone
     * @param locale the person's locale
     * @return the screen
     */
    public Screen load(final long nowMillis, final ZoneId zone, final Locale locale) {
        if (!source.entryAllowed(matchId)) {
            return new Screen(false, new PeerSummary("", PeerSummary.Reach.WAITS_UNTIL_NEARBY), false, List.of());
        }
        final List<ChatRecords.Message> messages = source.messages(matchId);
        return new Screen(true, source.peer(matchId), source.online(),
                ConversationRows.build(messages, nowMillis, zone, locale));
    }

    /**
     * Whether a draft may be sent.
     *
     * @param draft the composer's text
     * @return true when it has content and fits one message
     */
    public static boolean canSend(final String draft) {
        return check(draft) == SendResult.SENT;
    }

    private static SendResult check(final String draft) {
        if (draft == null || draft.isBlank()) {
            return SendResult.EMPTY;
        }
        return draft.strip().getBytes(StandardCharsets.UTF_8).length > MAX_TEXT_BYTES
                ? SendResult.TOO_LONG : SendResult.SENT;
    }

    /**
     * Sends a text.
     *
     * @param draft the composer's text; leading and trailing whitespace is dropped
     * @return what happened
     */
    public SendResult sendText(final String draft) {
        final SendResult ok = check(draft);
        if (ok != SendResult.SENT) {
            return ok;
        }
        return map(source.send(matchId, new RemoteText.TextEvent(TextEventKind.TEXT, null,
                draft.strip().getBytes(StandardCharsets.UTF_8))));
    }

    /**
     * Sends a reply to a message (CR-009). Only the id of the answered
     * message travels; its words are never sent again.
     *
     * @param draft the composer text
     * @param answered the message being answered
     * @return what happened
     */
    public SendResult sendReply(final String draft, final ConversationRows.Bubble answered) {
        final SendResult ok = check(draft);
        if (ok != SendResult.SENT) {
            return ok;
        }
        if (!actionsFor(answered).contains(Action.REPLY)) {
            return SendResult.FAILED;
        }
        return map(source.send(matchId, new RemoteText.TextEvent(TextEventKind.REPLY, answered.messageId(),
                draft.strip().getBytes(StandardCharsets.UTF_8))));
    }

    /**
     * The actions a bubble offers.
     *
     * @param b the bubble
     * @return its actions, possibly none
     */
    public static Set<Action> actionsFor(final ConversationRows.Bubble b) {
        final Set<Action> out = EnumSet.noneOf(Action.class);
        if (b.deleted()) {
            return out;
        }
        out.add(Action.REPLY);
        out.add(Action.REACT);
        if (!b.text().isEmpty()) {
            out.add(Action.COPY);
        }
        final boolean waiting = b.outgoing()
                && (b.state() == DeliveryMachine.State.QUEUED || b.state() == DeliveryMachine.State.SENDING);
        if (!waiting) {
            out.add(Action.DELETE_FOR_ME);
        }
        if (b.outgoing()) {
            out.add(Action.DELETE_FOR_EVERYONE);
        }
        return out;
    }

    /**
     * Reacts to a message.
     *
     * @param b the bubble
     * @param reaction one of REACTIONS
     * @return what happened
     */
    public SendResult react(final ConversationRows.Bubble b, final String reaction) {
        if (!REACTIONS.contains(reaction) || !actionsFor(b).contains(Action.REACT)) {
            return SendResult.FAILED;
        }
        return map(source.send(matchId, new RemoteText.TextEvent(TextEventKind.REACTION, b.messageId(),
                reaction.getBytes(StandardCharsets.UTF_8))));
    }

    /**
     * Deletes one of your own messages for both people.
     *
     * @param b the bubble
     * @return what happened
     */
    public SendResult deleteForEveryone(final ConversationRows.Bubble b) {
        if (!actionsFor(b).contains(Action.DELETE_FOR_EVERYONE)) {
            return SendResult.FAILED;
        }
        return map(source.send(matchId, new RemoteText.TextEvent(TextEventKind.DELETE_FOR_EVERYONE, b.messageId(),
                null)));
    }

    /**
     * Deletes a message from this device only.
     *
     * @param b the bubble
     * @return true when removed
     */
    public boolean deleteForMe(final ConversationRows.Bubble b) {
        return actionsFor(b).contains(Action.DELETE_FOR_ME) && source.deleteForMe(b.messageId());
    }

    /**
     * Ends the conversation: unmatch or block. The screen closes after.
     *
     * @param how unmatch or block
     * @return true when ended
     */
    public boolean end(final ConversationSafety.Ending how) {
        return source.end(matchId, how);
    }

    /** Marks the conversation read. */
    public void markRead() {
        source.markRead(matchId);
    }

    private static SendResult map(final ConversationSource.SendOutcome o) {
        switch (o) {
            case QUEUED:
                return SendResult.SENT;
            case NOT_READY:
                return SendResult.NOT_READY;
            default:
                return SendResult.FAILED;
        }
    }
}
