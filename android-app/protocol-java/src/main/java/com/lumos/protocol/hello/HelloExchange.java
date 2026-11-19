/*
 * Lumos - android-app/protocol-java / One link direction's HELLO exchange.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-PROTO-017, TRS-ENV-002, SRS-IDN-002
 * ADRs: ADR-002, ADR-008
 * Threading: serial-per-link
 * Notes: The stateful half of the port, mirroring Go's Exchange: presence is
 * granted ONLY to a peer whose ACK answers one's OWN outstanding HELLO, so a
 * replayed message grants nothing and an ACK with nothing outstanding fails
 * with the nonce code BEFORE any cryptography runs (TRS-ENV-002). Both sides
 * of a link each run their own exchange once per direction. Not safe for
 * concurrent use: the serialized link is its caller.
 */

package com.lumos.protocol.hello;

import com.lumos.protocol.LumosErrors;
import com.lumos.protocol.wire.Frame;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;

/** Runs one link direction's HELLO/HELLO_ACK (TRS-PROTO-017). */
public final class HelloExchange {

    /** An authenticated, live peer: the only product of a completed exchange. */
    public static final class Peer {

        private final Hello.Fields fields;
        private final byte[] id;

        private Peer(final Hello.Fields fields, final byte[] id) {
            this.fields = fields;
            this.id = id;
        }

        /** @return the authenticated hello fields */
        public Hello.Fields fields() {
            return fields;
        }

        /** @return a copy of the SRS-IDN-002 identifier, stable across EID rotations */
        public byte[] id() {
            return id.clone();
        }
    }

    private final Hello.Signer signer;
    private final Hello.Fields own;

    private byte[] outstanding;

    /**
     * @param signer this device's Ed25519 identity signer seam
     * @param own this device's hello fields template
     */
    public HelloExchange(final Hello.Signer signer, final Hello.Fields own) {
        this.signer = signer;
        this.own = own;
    }

    /**
     * Emits this side's HELLO with a fresh nonce and remembers it; the nonce
     * source is the caller's, so tests and vectors script it.
     *
     * @param nonce the fresh nonce
     * @return the HELLO payload bytes
     */
    public byte[] start(final byte[] nonce) {
        outstanding = own.withNonce(nonce).encode();
        return outstanding.clone();
    }

    /**
     * Answers the peer's HELLO as the responder. The returned ACK proves this
     * side's identity and liveness to THEM; it grants nothing here.
     *
     * @param helloBytes the received HELLO payload
     * @return the ACK payload bytes
     * @throws Frame.WireException when the HELLO does not authenticate
     * @throws GeneralSecurityException when the signing backend fails
     */
    public byte[] onHello(final byte[] helloBytes)
            throws Frame.WireException, GeneralSecurityException {
        Hello.parseHello(helloBytes);
        return Hello.buildAck(own, helloBytes, signer);
    }

    /**
     * Completes this side's exchange: the ACK must answer the HELLO this side
     * actually has outstanding.
     *
     * @param ackBytes the received ACK payload
     * @return the authenticated, live peer
     * @throws Frame.WireException with the exact stable code; an ACK with
     *     nothing outstanding is the replay shape and fails with the nonce
     *     code before any cryptography runs (TRS-ENV-002)
     */
    public Peer onAck(final byte[] ackBytes) throws Frame.WireException {
        if (outstanding == null) {
            throw new Frame.WireException(LumosErrors.ERR_HELLO_NONCE_MISMATCH,
                    "no outstanding hello");
        }
        final Hello.Fields f = Hello.verifyAck(outstanding, ackBytes);
        if (MessageDigest.isEqual(f.ed25519Public(), own.ed25519Public())) {
            // A mirror of our own identity is not a peer.
            throw new Frame.WireException(LumosErrors.ERR_HELLO_SIGNATURE_INVALID,
                    "own identity echoed");
        }
        outstanding = null;
        return new Peer(f, Hello.peerId(f.ed25519Public()));
    }
}
