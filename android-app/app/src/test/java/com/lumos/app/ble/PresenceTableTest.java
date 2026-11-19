/*
 * Lumos - android-app/app / Zero-false-positive presence, executed.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-ENV-002, SRS-NBY-006, SRS-NBY-007, SRS-IDN-002, TRS-TST-001
 * ADRs: ADR-002, ADR-008
 * Threading: test-only
 * Notes: The TRS-ENV-002 CI half over the REAL exchange: peers reach the
 * table only through verified HELLO_ACKs (the JCA signs here; libsodium signs
 * the same bytes on a device), a spoofed or replayed exchange throws before
 * any Peer exists so no card can appear, one identity stays one card across
 * EID rotations (SRS-NBY-006), and stale cards age out exactly on the
 * register bound (SRS-NBY-007).
 */

package com.lumos.app.ble;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.lumos.protocol.LumosConstants;
import com.lumos.protocol.hello.Hello;
import com.lumos.protocol.hello.HelloExchange;
import com.lumos.protocol.wire.Frame;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** PresenceTable, fed only by real exchanges. */
final class PresenceTableTest {

    /** A JVM-side identity with a JCA signer seam. */
    private static final class Party {

        private final HelloExchange exchange;
        private final Hello.Fields fields;
        private final PrivateKey edPrivate;

        Party(final byte[] eid) throws Exception {
            final KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
            generator.initialize(255, SecureRandom.getInstanceStrong());
            final KeyPair pair = generator.generateKeyPair();
            this.edPrivate = pair.getPrivate();
            final byte[] edPublic = rawEdPublic(pair);

            // The ADR-008 binding needs an X25519 key; its exact value is
            // irrelevant to presence, so a fixed scalar's public is fine, but
            // the BINDING must be real - parseHello verifies it.
            final byte[] xPublic =
                    com.lumos.protocol.wire.LumosCrypto.x25519PublicFromPrivate(fixedScalar());
            final byte[] binding = sign(com.lumos.protocol.wire.LumosCrypto.concat(
                    LumosConstants.X3DH_IDENTITY_BINDING_INFO_VALUE
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    xPublic));
            this.fields = new Hello.Fields(LumosConstants.PROTOCOL_VERSION_ENCODED, 1, 0L,
                    eid, edPublic, xPublic, binding, new byte[Hello.NONCE_LEN]);
            this.exchange = new HelloExchange(this::sign, fields);
        }

        private byte[] sign(final byte[] message) {
            try {
                final Signature s = Signature.getInstance("Ed25519");
                s.initSign(edPrivate);
                s.update(message);
                return s.sign();
            } catch (final java.security.GeneralSecurityException e) {
                throw new IllegalStateException(e);
            }
        }

        private static byte[] rawEdPublic(final KeyPair pair) {
            // The raw key is the last 32 bytes of the SPKI encoding - the
            // same extraction JcaIdentityCrypto documents.
            final byte[] spki = pair.getPublic().getEncoded();
            return Arrays.copyOfRange(spki, spki.length - 32, spki.length);
        }

        private static byte[] fixedScalar() {
            final byte[] scalar = new byte[32];
            Arrays.fill(scalar, (byte) 7);
            return scalar;
        }
    }

    private final PresenceTable table = new PresenceTable();

    private static byte[] nonce(final int b) {
        final byte[] n = new byte[Hello.NONCE_LEN];
        Arrays.fill(n, (byte) b);
        return n;
    }

    @Test
    void presenceExistsOnlyAfterACompletedExchange() throws Exception {
        final Party alice = new Party(new byte[Hello.EID_LEN]);
        final Party bob = new Party(new byte[Hello.EID_LEN]);

        final byte[] hello = alice.exchange.start(nonce(1));
        final byte[] ack = bob.exchange.onHello(hello);
        final HelloExchange.Peer peer = alice.exchange.onAck(ack);
        table.record(peer, 1000);

        assertEquals(1, table.size());
        assertEquals(1, table.cards().get(0).capabilityBits(),
                "the card carries the AUTHENTICATED capabilities, not hinted ones");
    }

    @Test
    void aReplayedAckThrowsBeforeAnyCardCanExist() throws Exception {
        final Party alice = new Party(new byte[Hello.EID_LEN]);
        final Party bob = new Party(new byte[Hello.EID_LEN]);
        final byte[] hello = alice.exchange.start(nonce(2));
        final byte[] ack = bob.exchange.onHello(hello);
        table.record(alice.exchange.onAck(ack), 1000);

        // The replay: there is no Peer to record, so there is no path to a
        // second card - the exception IS the zero-false-positive property.
        assertThrows(Frame.WireException.class, () -> alice.exchange.onAck(ack));
        assertEquals(1, table.size());
    }

    @Test
    void oneIdentityStaysOneCardAcrossEidRotations() throws Exception {
        final Party alice = new Party(new byte[Hello.EID_LEN]);
        final byte[] eid1 = new byte[Hello.EID_LEN];
        Arrays.fill(eid1, (byte) 1);
        final Party bob = new Party(eid1);

        final byte[] h1 = alice.exchange.start(nonce(3));
        table.record(alice.exchange.onAck(bob.exchange.onHello(h1)), 1000);

        // Bob rotates his EID and the pair exchange again (SRS-NBY-006).
        final byte[] eid2 = new byte[Hello.EID_LEN];
        Arrays.fill(eid2, (byte) 2);
        final byte[] h2 = alice.exchange.start(nonce(4));
        table.record(alice.exchange.onAck(rotatedAck(bob, eid2, h2)), 2000);

        assertEquals(1, table.size(), "the rotating EID must not mint a second card");
        assertEquals(2000, table.cards().get(0).confirmedAtMillis(), "the card refreshed");
    }

    @Test
    void staleCardsAgeOutOnTheRegisterBound() throws Exception {
        final Party alice = new Party(new byte[Hello.EID_LEN]);
        final Party bob = new Party(new byte[Hello.EID_LEN]);
        final byte[] hello = alice.exchange.start(nonce(5));
        table.record(alice.exchange.onAck(bob.exchange.onHello(hello)), 0);

        final long bound = (long) LumosConstants.PRESENCE_AGEOUT_SEC_DEFAULT * 1000;
        assertEquals(0, table.prune(bound), "exactly at the bound is still present");
        assertEquals(1, table.prune(bound + 1), "past the bound, the card goes");
        assertEquals(0, table.size());
    }

    /** Builds Bob's ACK under a rotated EID, signed by his real identity. */
    private static byte[] rotatedAck(final Party bob, final byte[] eid, final byte[] hello)
            throws Exception {
        final Hello.Fields rotated = new Hello.Fields(
                bob.fields.protocolVersion(), bob.fields.capabilityBits(),
                bob.fields.venueHint(), eid, bob.fields.ed25519Public(),
                bob.fields.x25519Public(), bob.fields.bindingSignature(),
                new byte[Hello.NONCE_LEN]);
        return Hello.buildAck(rotated, hello, bob::sign);
    }
}
