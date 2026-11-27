/*
 * Lumos - android-app/app / One discovery link's handshake, both ends, over real frames.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-PROTO-017, TRS-ENV-002, SRS-IDN-002, TRS-TST-003
 * ADRs: ADR-002, ADR-008, ADR-013
 * Threading: test-only
 * Notes: Two LinkSessions joined back to back, each Outbox delivering to the
 * other, with real JCA-signed identities and real frame encoding: the ADR-013
 * link minus the radio. Every way a link can go wrong ends FAILED with no
 * peer - the zero-false-positive rule (TRS-ENV-002) at the link's own level.
 */

package com.lumos.app.ble;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lumos.app.nearby.PeerFixtures;
import com.lumos.protocol.LumosErrors;
import com.lumos.protocol.MessageClass;
import com.lumos.protocol.hello.Hello;
import com.lumos.protocol.hello.HelloExchange;
import com.lumos.protocol.wire.Frame;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.Test;

/** LinkSession. */
final class LinkSessionTest {

    private static final long TIMEOUT = 10_000;
    private static final long T0 = 1_000_000;

    private final PeerFixtures fixtures = new PeerFixtures();
    private final PeerFixtures.Party alice = fixtures.peer(1, 1);
    private final PeerFixtures.Party bob = fixtures.peer(2, 3);
    private int nonce;

    LinkSessionTest() throws Exception {
    }

    private LinkSession.Nonces nonces() {
        return () -> {
            final byte[] n = new byte[Hello.NONCE_LEN];
            nonce++;
            n[0] = (byte) nonce;
            n[1] = (byte) (nonce >> 8);
            return n;
        };
    }

    /** Frames each side has sent and the other has not yet heard. */
    private final Deque<byte[]> toPeripheral = new ArrayDeque<>();
    private final Deque<byte[]> toCentral = new ArrayDeque<>();

    private LinkSession central(final PeerFixtures.Party who) {
        return new LinkSession(LinkSession.Role.CENTRAL, who.freshExchange(), toPeripheral::add, nonces(), TIMEOUT);
    }

    private LinkSession peripheral(final PeerFixtures.Party who) {
        return new LinkSession(LinkSession.Role.PERIPHERAL, who.freshExchange(), toCentral::add, nonces(), TIMEOUT);
    }

    private void pump(final LinkSession central, final LinkSession peripheral, final long now) {
        while (!toPeripheral.isEmpty() || !toCentral.isEmpty()) {
            if (!toPeripheral.isEmpty()) {
                peripheral.onFrame(toPeripheral.poll(), now);
            }
            if (!toCentral.isEmpty()) {
                central.onFrame(toCentral.poll(), now);
            }
        }
    }

    private static byte[] frame(final MessageClass kind, final byte[] payload) throws Exception {
        final Frame.Header header = new Frame.Header();
        header.type = kind.code();
        return Frame.encode(header, payload);
    }

    @Test
    void bothEndsFindEachOtherWhoeverOpensFirst() {
        for (final boolean centralFirst : new boolean[] {true, false}) {
            final LinkSession c = central(alice);
            final LinkSession p = peripheral(bob);
            if (centralFirst) {
                c.open(T0);
                pump(c, p, T0);
            } else {
                p.open(T0);
                c.open(T0);
                pump(c, p, T0);
            }
            assertEquals(LinkSession.State.DONE, c.state());
            assertEquals(LinkSession.State.DONE, p.state());
            assertArrayEquals(Hello.peerId(bobEd()), c.peer().id(), "the central found the peripheral");
            assertArrayEquals(Hello.peerId(aliceEd()), p.peer().id(), "and the peripheral found the central");
            assertEquals(3, c.peer().fields().capabilityBits(), "authenticated capabilities, not hinted ones");
        }
    }

    private byte[] aliceEd() {
        return edOf(alice);
    }

    private byte[] bobEd() {
        return edOf(bob);
    }

    private static byte[] edOf(final PeerFixtures.Party party) {
        final HelloExchange probe = party.freshExchange();
        try {
            return Hello.parseHello(probe.start(new byte[Hello.NONCE_LEN])).ed25519Public();
        } catch (Frame.WireException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void aSilentPeerEndsTheLinkOnTheTimerWithNothingGranted() {
        final LinkSession c = central(alice);
        c.open(T0);
        c.tick(T0 + TIMEOUT - 1);
        assertEquals(LinkSession.State.EXCHANGING, c.state());
        c.tick(T0 + TIMEOUT);
        assertEquals(LinkSession.State.FAILED, c.state());
        assertTrue(c.timedOut());
        assertNull(c.peer());
    }

    @Test
    void aForgedAckGrantsNothing() throws Exception {
        final LinkSession c = central(alice);
        c.open(T0);
        final byte[] myHello = Frame.decode(toPeripheral.poll()).payload();
        final byte[] ack = bob.freshExchange().onHello(myHello);
        ack[ack.length - 1] ^= 1;
        c.onFrame(frame(MessageClass.HELLO_ACK, ack), T0);
        assertEquals(LinkSession.State.FAILED, c.state());
        assertNull(c.peer());
        assertFalse(c.timedOut());
    }

    @Test
    void anAckToSomeoneElsesHelloGrantsNothing() throws Exception {
        final LinkSession c = central(alice);
        c.open(T0);
        final byte[] otherHello = alice.freshExchange().start(new byte[Hello.NONCE_LEN]);
        c.onFrame(frame(MessageClass.HELLO_ACK, bob.freshExchange().onHello(otherHello)), T0);
        assertEquals(LinkSession.State.FAILED, c.state(), "a replayed ACK answers a hello we did not send");
        assertEquals(LumosErrors.ERR_HELLO_SIGNATURE_INVALID, c.failure(),
                "its signature covers that other hello, so it cannot verify against ours");
        assertNull(c.peer());
    }

    @Test
    void aSecondHelloOnOneLinkIsAReplay() throws Exception {
        final LinkSession p = peripheral(bob);
        final byte[] hello = alice.freshExchange().start(new byte[Hello.NONCE_LEN]);
        p.onFrame(frame(MessageClass.HELLO, hello), T0);
        assertEquals(LinkSession.State.EXCHANGING, p.state(), "the first opens and is answered");
        p.onFrame(frame(MessageClass.HELLO, hello), T0);
        assertEquals(LinkSession.State.FAILED, p.state());
        assertEquals(LumosErrors.ERR_CRYPTO_REPLAY, p.failure());
    }

    @Test
    void anythingButTheHandshakeEndsADiscoveryLink() throws Exception {
        final LinkSession c = central(alice);
        c.open(T0);
        c.onFrame(frame(MessageClass.TEXT_MESSAGE, new byte[] {1, 2, 3}), T0);
        assertEquals(LinkSession.State.FAILED, c.state());
        assertEquals(LumosErrors.ERR_FRAME_UNKNOWN_TYPE, c.failure());

        final LinkSession garbled = central(alice);
        garbled.open(T0);
        garbled.onFrame(new byte[] {0x13, 0x37, 0, 0}, T0);
        assertEquals(LinkSession.State.FAILED, garbled.state());
        assertEquals(LumosErrors.ERR_FRAME_BAD_MAGIC, garbled.failure());
    }

    @Test
    void aMirrorOfOurOwnIdentityIsNotAPeer() {
        final LinkSession c = central(alice);
        final LinkSession p = peripheral(alice);
        c.open(T0);
        pump(c, p, T0);
        assertEquals(LinkSession.State.FAILED, c.state(), "our own identity echoed back is refused");
        assertNull(c.peer());
    }

    @Test
    void aFinishedLinkIgnoresWhateverArrivesAfter() throws Exception {
        final LinkSession c = central(alice);
        final LinkSession p = peripheral(bob);
        c.open(T0);
        pump(c, p, T0);
        final byte[] id = c.peer().id();
        c.onFrame(frame(MessageClass.TEXT_MESSAGE, new byte[] {9}), T0 + 1);
        c.tick(T0 + 10 * TIMEOUT);
        assertEquals(LinkSession.State.DONE, c.state(), "a finished link stays finished");
        assertArrayEquals(id, c.peer().id());
        final List<byte[]> none = new ArrayList<>(toPeripheral);
        assertTrue(none.isEmpty(), "and sends nothing more");
        assertEquals(0, Arrays.compare(id, c.peer().id()));
    }
}
