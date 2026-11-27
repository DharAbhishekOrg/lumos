/*
 * Lumos - android-app/app / Authenticated peers for nearby-feed tests.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-TST-001
 * ADRs: ADR-008
 * Threading: test-only
 * Notes: The feed's input type is PresenceTable.Card, and a Card exists only
 * through a completed HelloExchange - the TRS-ENV-002 type enforcement. So
 * the fixture does what a venue does: real JCA-signed dual exchanges, one
 * identity per peer, fresh exchange objects per link. Shared here because
 * three suites in this package need venues and one copy of the ceremony is
 * one place to fix.
 */

package com.lumos.app.nearby;

import com.lumos.app.ble.PresenceTable;
import com.lumos.protocol.LumosConstants;
import com.lumos.protocol.hello.Hello;
import com.lumos.protocol.hello.HelloExchange;
import com.lumos.protocol.wire.LumosCrypto;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.util.Arrays;

/** Builds authenticated peers the type system will accept. */
public final class PeerFixtures {

    /** One identity; fresh exchange objects per link. */
    public static final class Party {

        private final Hello.Fields fields;
        private final PrivateKey edPrivate;

        Party(final KeyPairGenerator generator, final int index, final int capabilityBits)
                throws Exception {
            final KeyPair pair = generator.generateKeyPair();
            this.edPrivate = pair.getPrivate();
            final byte[] spki = pair.getPublic().getEncoded();
            final byte[] edPublic = Arrays.copyOfRange(spki, spki.length - 32, spki.length);
            final byte[] scalar = new byte[32];
            Arrays.fill(scalar, (byte) (1 + (index % 250)));
            final byte[] xPublic = LumosCrypto.x25519PublicFromPrivate(scalar);
            final byte[] binding = sign(LumosCrypto.concat(
                    LumosConstants.X3DH_IDENTITY_BINDING_INFO_VALUE
                            .getBytes(StandardCharsets.UTF_8),
                    xPublic));
            final byte[] eid = new byte[Hello.EID_LEN];
            eid[0] = (byte) index;
            eid[1] = (byte) (index >> 8);
            this.fields = new Hello.Fields(LumosConstants.PROTOCOL_VERSION_ENCODED,
                    capabilityBits, 0L, eid, edPublic, xPublic, binding,
                    new byte[Hello.NONCE_LEN]);
        }

        /** @return this party's Ed25519 identity public key */
        public byte[] ed25519Public() {
            return fields.ed25519Public().clone();
        }

        /**
         * A fresh exchange for one link, as a device makes per connection.
         *
         * @return the exchange
         */
        public HelloExchange freshExchange() {
            return new HelloExchange(this::sign, fields);
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
    }

    private final KeyPairGenerator generator;
    private final Party me;
    private int nonceCounter;

    /**
     * Stands up the local identity.
     *
     * @throws Exception when the JCA refuses, which is a test failure
     */
    public PeerFixtures() throws Exception {
        this.generator = KeyPairGenerator.getInstance("Ed25519");
        this.generator.initialize(255, new SecureRandom());
        this.me = new Party(generator, 9999, 1);
    }

    /**
     * Mints a peer identity.
     *
     * @param index a distinct small number
     * @param capabilityBits the bits the peer will authenticate
     * @return the peer
     * @throws Exception when the JCA refuses
     */
    public Party peer(final int index, final int capabilityBits) throws Exception {
        return new Party(generator, index, capabilityBits);
    }

    /**
     * Runs one real dual exchange and records the peer as present.
     *
     * @param table the presence table
     * @param peer the peer to authenticate
     * @param nowMillis the recording clock
     * @return the card's peer key
     * @throws Exception on any protocol refusal, which is a test failure
     */
    public String authenticate(final PresenceTable table, final Party peer, final long nowMillis)
            throws Exception {
        final byte[] nonce = new byte[Hello.NONCE_LEN];
        nonceCounter++;
        nonce[0] = (byte) nonceCounter;
        nonce[1] = (byte) (nonceCounter >> 8);
        nonce[2] = (byte) (nonceCounter >> 16);
        final HelloExchange mine = me.freshExchange();
        final byte[] hello = mine.start(nonce);
        final byte[] ack = peer.freshExchange().onHello(hello);
        return table.record(mine.onAck(ack), nowMillis).peerKey();
    }
}
