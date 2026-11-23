/*
 * Lumos - android-app/app / Tests: the conversation screen's rules.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-RMT-003, SRS-RMT-010, SRS-RMT-011, SRS-UX-012
 * ADRs: -
 * Threading: test-only
 * Notes: Against a scripted source: what may be sent, what each bubble
 * offers, what reaches the chat stack, and that a failed or not-ready send
 * is reported so the composer keeps the person's words.
 */

package com.lumos.app.conversation;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lumos.app.chat.ChatRecords;
import com.lumos.protocol.MessageClass;
import com.lumos.protocol.MessageClassLimits;
import com.lumos.protocol.TextEventKind;
import com.lumos.protocol.chat.DeliveryMachine;
import com.lumos.protocol.chat.RemoteText;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class ConversationPresenterTest {

    private static final byte[] MATCH = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};

    /** A chat stack that records what reaches it. */
    private static final class Scripted implements ConversationSource {
        boolean allowed = true;
        ConversationSource.SendOutcome outcome = SendOutcome.QUEUED;
        boolean deleteOk = true;
        int reads;
        int messageReads;
        final List<RemoteText.TextEvent> sent = new ArrayList<>();
        final List<ChatRecords.Message> messages = new ArrayList<>();

        @Override
        public boolean entryAllowed(final byte[] matchId) {
            return allowed;
        }

        @Override
        public PeerSummary peer(final byte[] matchId) {
            return new PeerSummary("  Aanya ", PeerSummary.Reach.REMOTE);
        }

        @Override
        public List<ChatRecords.Message> messages(final byte[] matchId) {
            messageReads++;
            return messages;
        }

        @Override
        public boolean online() {
            return false;
        }

        @Override
        public SendOutcome send(final byte[] matchId, final RemoteText.TextEvent event) {
            assertArrayEquals(MATCH, matchId);
            sent.add(event);
            return outcome;
        }

        @Override
        public boolean deleteForMe(final byte[] messageId) {
            return deleteOk;
        }

        @Override
        public void markRead(final byte[] matchId) {
            reads++;
        }
    }

    private static ConversationRows.Bubble bubble(final boolean mine, final DeliveryMachine.State state,
            final boolean deleted, final String text) {
        return new ConversationRows.Bubble("k", new byte[16], mine, text, deleted, false, "9:00", state, "", "",
                ConversationRows.RunPosition.SINGLE);
    }

    @Test
    void onlyADraftWithContentThatFitsOneMessageMaySend() {
        assertFalse(ConversationPresenter.canSend(null));
        assertFalse(ConversationPresenter.canSend(""));
        assertFalse(ConversationPresenter.canSend(" \n\t "), "whitespace is not a message");
        assertTrue(ConversationPresenter.canSend("hi"));
        final int max = ConversationPresenter.MAX_TEXT_BYTES;
        assertEquals(MessageClassLimits.maxPayloadBytes(MessageClass.TEXT_MESSAGE) - 512, max,
                "the limit follows the register's class limit");
        assertTrue(ConversationPresenter.canSend("a".repeat(max)));
        assertFalse(ConversationPresenter.canSend("a".repeat(max + 1)));
        // Counted in bytes, not characters: a four-byte emoji uses four.
        assertFalse(ConversationPresenter.canSend("🙂".repeat(max / 4 + 1)));
        assertTrue(ConversationPresenter.canSend("  " + "a".repeat(max) + "  "), "edges are trimmed before counting");
    }

    @Test
    void aSendCarriesTheTrimmedTextAndReportsEveryOutcome() {
        final Scripted s = new Scripted();
        final ConversationPresenter p = new ConversationPresenter(s, MATCH);
        assertEquals(ConversationPresenter.SendResult.SENT, p.sendText("  Coffee on Saturday?  "));
        assertEquals(TextEventKind.TEXT, s.sent.get(0).kind());
        assertArrayEquals("Coffee on Saturday?".getBytes(StandardCharsets.UTF_8), s.sent.get(0).body());
        s.outcome = ConversationSource.SendOutcome.NOT_READY;
        assertEquals(ConversationPresenter.SendResult.NOT_READY, p.sendText("again"));
        s.outcome = ConversationSource.SendOutcome.FAILED;
        assertEquals(ConversationPresenter.SendResult.FAILED, p.sendText("again"));
        assertEquals(ConversationPresenter.SendResult.EMPTY, p.sendText("   "));
        assertEquals(ConversationPresenter.SendResult.TOO_LONG, p.sendText("a".repeat(ConversationPresenter.MAX_TEXT_BYTES + 1)));
        assertEquals(3, s.sent.size(), "empty and over-long drafts never reach the chat stack");
    }

    @Test
    void eachBubbleOffersOnlyWhatItCanDo() {
        assertEquals(EnumSet.allOf(ConversationPresenter.Action.class),
                ConversationPresenter.actionsFor(bubble(true, DeliveryMachine.State.READ, false, "mine")));
        assertEquals(EnumSet.of(ConversationPresenter.Action.REACT, ConversationPresenter.Action.COPY,
                ConversationPresenter.Action.DELETE_FOR_ME),
                ConversationPresenter.actionsFor(bubble(false, DeliveryMachine.State.DELIVERED, false, "theirs")),
                "never delete the other person's message for everyone");
        for (final DeliveryMachine.State waiting : List.of(DeliveryMachine.State.QUEUED, DeliveryMachine.State.SENDING)) {
            assertFalse(ConversationPresenter.actionsFor(bubble(true, waiting, false, "going"))
                    .contains(ConversationPresenter.Action.DELETE_FOR_ME),
                    "its sealed envelope would still go, so 'delete for me' would be a lie");
        }
        assertTrue(ConversationPresenter.actionsFor(bubble(true, DeliveryMachine.State.SENT, true, "")).isEmpty(),
                "a deleted message offers nothing");
    }

    @Test
    void reactionsAndDeletesReachTheStackOnlyWhenAllowed() {
        final Scripted s = new Scripted();
        final ConversationPresenter p = new ConversationPresenter(s, MATCH);
        final ConversationRows.Bubble theirs = bubble(false, DeliveryMachine.State.DELIVERED, false, "hey");
        assertEquals(ConversationPresenter.SendResult.SENT, p.react(theirs, "👍"));
        assertEquals(TextEventKind.REACTION, s.sent.get(0).kind());
        assertEquals(ConversationPresenter.SendResult.FAILED, p.react(theirs, "x"), "only the offered reactions");
        assertEquals(ConversationPresenter.SendResult.FAILED, p.deleteForEveryone(theirs));
        assertEquals(ConversationPresenter.SendResult.SENT,
                p.deleteForEveryone(bubble(true, DeliveryMachine.State.READ, false, "mine")));
        assertEquals(TextEventKind.DELETE_FOR_EVERYONE, s.sent.get(1).kind());
        assertEquals(2, s.sent.size());
        assertFalse(p.deleteForMe(bubble(true, DeliveryMachine.State.QUEUED, false, "going")));
        assertTrue(p.deleteForMe(theirs));
    }

    @Test
    void aConversationTheStackDoesNotHaveDoesNotOpenAndReadsNothing() {
        final Scripted s = new Scripted();
        s.allowed = false;
        final ConversationPresenter.Screen screen = new ConversationPresenter(s, MATCH)
                .load(0, ZoneId.of("UTC"), Locale.UK);
        assertFalse(screen.open());
        assertEquals(0, s.messageReads);
        s.allowed = true;
        final ConversationPresenter.Screen open = new ConversationPresenter(s, MATCH).load(0, ZoneId.of("UTC"), Locale.UK);
        assertTrue(open.open());
        assertEquals("Aanya", open.peer().displayName(), "names are trimmed");
        assertFalse(open.online());
    }
}
