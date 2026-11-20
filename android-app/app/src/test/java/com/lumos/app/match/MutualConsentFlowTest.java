/*
 * Lumos - android-app/app / AT-005's logic half: two people, one loopback link.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-MAT-001, SRS-MAT-002, SRS-MAT-004, SRS-MAT-005, SRS-MAT-006,
 *             SRS-MAT-007, SRS-MAT-008, SRS-MAT-009, SRS-MAT-014, TRS-TST-001
 * ADRs: ADR-002, ADR-008
 * Threading: test-only
 * Notes: Two independent sealed stores, two ledgers, two sessions, and a
 * loopback queue standing in for FrameLink - every byte between them is a
 * canonical frame through the conformance-pinned codecs. The scenes are the
 * acceptance test's script: the request carries exactly the preview; a
 * decline transmits NOTHING (the outbox stays empty) and the asker's view is
 * unchanged; before the match no conversation frame leaves either side or
 * lands on either side, including frames an attacker forged; mutual
 * acceptance gives both sides one identifier and one verification token;
 * replays absorb; and an unmatch closes the door again. What this cannot
 * be is the physical Android-to-iOS run AT-005 names - that remains the
 * owner's, on real phones.
 */

package com.lumos.app.match;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lumos.app.Fixtures;
import com.lumos.protocol.LumosConstants;
import com.lumos.protocol.LumosErrors;
import com.lumos.protocol.MessageClass;
import com.lumos.protocol.match.MatchMachine;
import com.lumos.protocol.match.MatchToken;
import com.lumos.protocol.wire.Frame;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import org.junit.jupiter.api.Test;

/** The mutual-consent flow end to end over canonical frames. */
final class MutualConsentFlowTest {

    private static final long NOW = 1_755_561_600_000L;
    private static final byte[] ASHA_PREVIEW =
            "lumos-profile/1\nalias=Asha\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] RAVI_PREVIEW =
            "lumos-profile/1\nalias=Ravi\n".getBytes(StandardCharsets.UTF_8);

    /** One person: their store, ledger, session and inbox. */
    private static final class Person implements AutoCloseable {
        private final Fixtures.Stack stack;
        private final InterestLedger ledger;
        private final Deque<byte[]> sent = new ArrayDeque<>();
        private InterestSession session;

        Person(final int seed) throws Exception {
            this.stack = Fixtures.openInMemory();
            this.ledger = new InterestLedger(stack.store(),
                    new PersistedBudget(stack.store()), id(seed));
        }

        void talkTo(final int peerSeed, final byte[] preview) {
            this.session = new InterestSession(ledger, hex(id(peerSeed)), preview, sent::add);
        }

        @Override
        public void close() throws Exception {
            stack.close();
        }
    }

    private static byte[] id(final int seed) {
        final byte[] out = new byte[LumosConstants.PEER_ID_LEN_DEFAULT];
        Arrays.fill(out, (byte) seed);
        return out;
    }

    private static String hex(final byte[] bytes) {
        final StringBuilder sb = new StringBuilder();
        for (final byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static byte[] hexBytes(final String hex) {
        final byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    /** Delivers everything one side sent to the other; returns the count. */
    private static int deliver(final Person from, final Person to) throws Exception {
        int n = 0;
        while (!from.sent.isEmpty()) {
            final InterestSession.Inbound inbound = to.session.onFrame(from.sent.poll(), NOW);
            assertTrue(inbound.delivered(), "a legitimate frame must land: " + inbound);
            n++;
        }
        return n;
    }

    private static byte[] forged(final MessageClass mc) throws Exception {
        final Frame.Header h = new Frame.Header();
        h.type = mc.code();
        h.sequence = 99;
        return Frame.encode(h, "hi".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void aDeclineTransmitsNothingAndNoConversationCrossesBeforeAMatch() throws Exception {
        try (Person asha = new Person(0x11); Person ravi = new Person(0x22)) {
            asha.talkTo(0x22, ASHA_PREVIEW);
            ravi.talkTo(0x11, RAVI_PREVIEW);

            asha.session.local(MatchMachine.Event.LOCAL_REQUEST, NOW);
            assertEquals(1, deliver(asha, ravi), "one request frame on the air");
            assertArrayEquals(ASHA_PREVIEW, ravi.session.lastReceivedPreview(),
                    "the request carries exactly the requester-stage preview (SRS-MAT-002)");

            // Pre-match: nothing conversational leaves either side...
            assertFalse(asha.session.sendConversation(MessageClass.TEXT_MESSAGE, new byte[1]));
            assertFalse(ravi.session.sendConversation(MessageClass.OBJECT_META, new byte[1]));
            assertFalse(ravi.session.sendConversation(MessageClass.PROFILE_FULL, new byte[1]));
            assertTrue(asha.sent.isEmpty() && ravi.sent.isEmpty());
            // ...and nothing conversational lands, even forged (SRS-MAT-006).
            for (final MessageClass mc : new MessageClass[] {MessageClass.TEXT_MESSAGE,
                MessageClass.PROFILE_FULL, MessageClass.OBJECT_CHUNK, MessageClass.VOICE_META}) {
                final InterestSession.Inbound in = ravi.session.onFrame(forged(mc), NOW);
                assertFalse(in.delivered(), mc + " must not land before a match");
                assertEquals(LumosErrors.ERR_MATCH_NOT_MATCHED, in.refusalCode());
            }

            // Ravi declines: the outbox stays EMPTY - there is no frame to send.
            ravi.session.local(MatchMachine.Event.LOCAL_DECLINE, NOW + 1);
            assertTrue(ravi.sent.isEmpty(), "a decline transmits nothing (SRS-MAT-004)");
            assertEquals(MatchMachine.State.REQUESTED_OUT, asha.ledger.stateOf(hex(id(0x22))),
                    "Asha's view after a decline is identical to no answer yet");
        }
    }

    @Test
    void mutualAcceptanceOpensTheDoorWithOneIdentifierAndOneToken() throws Exception {
        try (Person asha = new Person(0x11); Person ravi = new Person(0x22)) {
            asha.talkTo(0x22, ASHA_PREVIEW);
            ravi.talkTo(0x11, RAVI_PREVIEW);

            asha.session.local(MatchMachine.Event.LOCAL_REQUEST, NOW);
            deliver(asha, ravi);
            final InterestLedger.Outcome ravis =
                    ravi.session.local(MatchMachine.Event.LOCAL_ACCEPT, NOW + 1);
            assertEquals(1, ravi.sent.size(), "an acceptance is one frame");
            final byte[] acceptFrame = ravi.sent.peek().clone();
            deliver(ravi, asha);

            assertEquals(MatchMachine.State.MATCHED, asha.ledger.stateOf(hex(id(0x22))));
            assertEquals(MatchMachine.State.MATCHED, ravi.ledger.stateOf(hex(id(0x11))));

            final String ashaRecord = asha.ledger.matchRecordOf(hex(id(0x22)));
            assertNotNull(ashaRecord);
            assertTrue(ashaRecord.contains(ravis.matchIdHex()),
                    "both stores hold the same match identifier (SRS-MAT-007)");
            final MatchToken token = MatchToken.derive(hexBytes(ravis.matchIdHex()));
            assertNotNull(token.colorName(), "both screens can show the same token (SRS-MAT-008)");

            // The door is open both ways now.
            assertTrue(asha.session.sendConversation(MessageClass.TEXT_MESSAGE, new byte[1]));
            assertTrue(ravi.session.onFrame(asha.sent.poll(), NOW + 2).delivered());

            // A replayed acceptance absorbs: still one match, still matched.
            assertTrue(asha.session.onFrame(acceptFrame, NOW + 3).delivered());
            assertEquals(1, asha.stack.store().count(
                    com.lumos.app.store.RecordKind.MATCH_RECORD), "SRS-MAT-014");

            // An unmatch is visible, and it closes the door again.
            asha.session.local(MatchMachine.Event.LOCAL_UNMATCH, NOW + 4);
            deliver(asha, ravi);
            assertEquals(MatchMachine.State.UNMATCHED, ravi.ledger.stateOf(hex(id(0x11))));
            assertFalse(ravi.session.sendConversation(MessageClass.TEXT_MESSAGE, new byte[1]),
                    "after an unmatch the conversation path is shut (SRS-MAT-009)");
            assertFalse(ravi.session.onFrame(forged(MessageClass.TEXT_MESSAGE), NOW + 5)
                    .delivered());
        }
    }

    @Test
    void theAcceptAcceptRaceOverTheWireYieldsOneMatch() throws Exception {
        try (Person asha = new Person(0x11); Person ravi = new Person(0x22)) {
            asha.talkTo(0x22, ASHA_PREVIEW);
            ravi.talkTo(0x11, RAVI_PREVIEW);

            asha.session.local(MatchMachine.Event.LOCAL_REQUEST, NOW);
            ravi.session.local(MatchMachine.Event.LOCAL_REQUEST, NOW);
            deliver(asha, ravi);
            deliver(ravi, asha);
            final InterestLedger.Outcome a = asha.session.local(MatchMachine.Event.LOCAL_ACCEPT, NOW + 1);
            final InterestLedger.Outcome r = ravi.session.local(MatchMachine.Event.LOCAL_ACCEPT, NOW + 1);
            deliver(asha, ravi);
            deliver(ravi, asha);

            assertEquals(a.matchIdHex(), r.matchIdHex(), "both crossed acceptances, one identifier");
            assertEquals(MatchMachine.State.MATCHED, asha.ledger.stateOf(hex(id(0x22))));
            assertEquals(MatchMachine.State.MATCHED, ravi.ledger.stateOf(hex(id(0x11))));
        }
    }

    @Test
    void aGarbledFrameIsRefusedWithItsCodeAndChangesNothing() throws Exception {
        try (Person ravi = new Person(0x22)) {
            ravi.talkTo(0x11, RAVI_PREVIEW);
            final Frame.Header h = new Frame.Header();
            h.type = MessageClass.INTEREST_RESPONSE.code();
            final byte[] badVerdict = Frame.encode(h, new byte[] {1, 9});
            final InterestSession.Inbound in = ravi.session.onFrame(badVerdict, NOW);
            assertFalse(in.delivered());
            assertEquals(LumosErrors.ERR_MATCH_BAD_VERDICT, in.refusalCode());
            assertEquals(MatchMachine.State.NONE, in.state(), "a refused frame moves nothing");
        }
    }
}
