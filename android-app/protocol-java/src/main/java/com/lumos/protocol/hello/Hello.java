/*
 * Lumos - android-app/protocol-java / The canonical trust point: HELLO and HELLO_ACK.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-PROTO-017, TRS-PROTO-018, TRS-ENV-002, SRS-IDN-002
 * ADRs: ADR-002, ADR-008
 * Threading: serial-per-link
 * Notes: The Java port of protocol/hello, held to
 * test-vectors/session/hello_exchange.json. Everything the advertisement
 * hinted - EID, capabilities, protocol version, venue context - is
 * re-established HERE, authenticated, before any presence card exists
 * (TRS-PROTO-017); a hintless peer walks the same path with a zero EID
 * (TRS-PROTO-018). Verification is pure JVM; SIGNING goes through the Signer
 * seam, because Android cannot make an Ed25519 signature through the JCA at
 * any API level (WP-02 closeout 5.1) - on a device the seam is the libsodium
 * identity backend, in JVM tests it is the JCA, and the vectors hold the two
 * to the same bytes since RFC 8032 signing is deterministic.
 */

package com.lumos.protocol.hello;

import com.lumos.protocol.LumosConstants;
import com.lumos.protocol.LumosErrors;
import com.lumos.protocol.wire.Frame;
import com.lumos.protocol.wire.LumosCrypto;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;

/** The hello_v1 codec: build, parse and authenticate (TRS-PROTO-017). */
public final class Hello {

    /** The hello_v1 payload length. */
    public static final int HELLO_LEN = LumosConstants.HELLO_V1_LAYOUT_TOTAL_LEN;

    /** The hello_ack_v1 payload length. */
    public static final int ACK_LEN = LumosConstants.HELLO_ACK_V1_LAYOUT_TOTAL_LEN;

    /** The rotating EID width inside the payload. */
    public static final int EID_LEN = LumosConstants.HELLO_V1_LAYOUT_EID_LEN;

    /** The freshness nonce width. */
    public static final int NONCE_LEN = LumosConstants.HELLO_V1_LAYOUT_NONCE_LEN;

    /** The derived peer identifier width. */
    public static final int PEER_ID_LEN = LumosConstants.PEER_ID_LEN_DEFAULT;

    private static final int HELLO_V1 = 1;

    private Hello() {
        throw new AssertionError("no instances");
    }

    /** Signs an exact message with this device's Ed25519 identity. */
    public interface Signer {

        /**
         * @param message the exact bytes to sign
         * @return the 64-byte RFC 8032 signature
         * @throws GeneralSecurityException when the backend fails
         */
        byte[] sign(byte[] message) throws GeneralSecurityException;
    }

    /** The hello_v1 content HELLO and HELLO_ACK share. */
    public static final class Fields {

        private final int protocolVersion;
        private final int capabilityBits;
        private final long venueHint;
        private final byte[] eid;
        private final byte[] ed25519Public;
        private final byte[] x25519Public;
        private final byte[] bindingSignature;
        private final byte[] nonce;

        /**
         * @param protocolVersion the encoded protocol version byte
         * @param capabilityBits the capability bits
         * @param venueHint the venue hint
         * @param eid the current rotating EID; zeros when hintless
         * @param ed25519Public the raw Ed25519 identity public key
         * @param x25519Public the raw X25519 identity-DH public key
         * @param bindingSignature the ADR-008 binding signature
         * @param nonce the freshness nonce
         */
        public Fields(final int protocolVersion, final int capabilityBits, final long venueHint,
                final byte[] eid, final byte[] ed25519Public, final byte[] x25519Public,
                final byte[] bindingSignature, final byte[] nonce) {
            this.protocolVersion = protocolVersion;
            this.capabilityBits = capabilityBits;
            this.venueHint = venueHint;
            this.eid = eid.clone();
            this.ed25519Public = ed25519Public.clone();
            this.x25519Public = x25519Public.clone();
            this.bindingSignature = bindingSignature.clone();
            this.nonce = nonce.clone();
        }

        /** @return the encoded protocol version byte */
        public int protocolVersion() {
            return protocolVersion;
        }

        /** @return the capability bits */
        public int capabilityBits() {
            return capabilityBits;
        }

        /** @return the venue hint */
        public long venueHint() {
            return venueHint;
        }

        /** @return a copy of the rotating EID */
        public byte[] eid() {
            return eid.clone();
        }

        /** @return a copy of the raw Ed25519 identity public key */
        public byte[] ed25519Public() {
            return ed25519Public.clone();
        }

        /** @return a copy of the raw X25519 identity-DH public key */
        public byte[] x25519Public() {
            return x25519Public.clone();
        }

        /** @return a copy of the ADR-008 binding signature */
        public byte[] bindingSignature() {
            return bindingSignature.clone();
        }

        /** @return a copy of the freshness nonce */
        public byte[] nonce() {
            return nonce.clone();
        }

        /**
         * Returns this content with a different nonce - how an exchange stamps
         * freshness without mutating the template.
         *
         * @param freshNonce the fresh nonce
         * @return a copy carrying freshNonce
         */
        public Fields withNonce(final byte[] freshNonce) {
            return new Fields(protocolVersion, capabilityBits, venueHint, eid,
                    ed25519Public, x25519Public, bindingSignature, freshNonce);
        }

        /**
         * Serialises the fields in register layout order.
         *
         * @return the hello_v1 bytes
         */
        public byte[] encode() {
            final byte[] out = new byte[HELLO_LEN];
            out[0] = (byte) protocolVersion;
            out[1] = (byte) HELLO_V1;
            out[2] = (byte) (capabilityBits >>> 8);
            out[3] = (byte) capabilityBits;
            out[4] = (byte) (venueHint >>> 24);
            out[5] = (byte) (venueHint >>> 16);
            out[6] = (byte) (venueHint >>> 8);
            out[7] = (byte) venueHint;
            System.arraycopy(eid, 0, out, 8, EID_LEN);
            System.arraycopy(ed25519Public, 0, out, 20, 32);
            System.arraycopy(x25519Public, 0, out, 52, 32);
            System.arraycopy(bindingSignature, 0, out, 84, 64);
            System.arraycopy(nonce, 0, out, 148, NONCE_LEN);
            return out;
        }
    }

    /**
     * Derives the public peer identifier from identity material alone
     * (SRS-IDN-002): PEER_ID_LEN bytes of SHA-256(PEER_ID_KDF_INFO | edPublic).
     *
     * @param ed25519Public the raw Ed25519 identity public key
     * @return the identifier, stable across EID rotations (SRS-NBY-006)
     */
    public static byte[] peerId(final byte[] ed25519Public) {
        return Arrays.copyOf(LumosCrypto.sha256(LumosCrypto.concat(
                LumosConstants.PEER_ID_KDF_INFO_VALUE.getBytes(StandardCharsets.UTF_8),
                ed25519Public)), PEER_ID_LEN);
    }

    /**
     * Decodes and authenticates a received HELLO: structure, version and the
     * ADR-008 binding. A valid HELLO proves an identity EXISTS, never that it
     * is live on this link - presence needs a verified ACK to one's own HELLO.
     *
     * @param b the payload bytes
     * @return the decoded fields
     * @throws Frame.WireException with the exact stable code
     */
    public static Fields parseHello(final byte[] b) throws Frame.WireException {
        if (b.length != HELLO_LEN) {
            throw new Frame.WireException(LumosErrors.ERR_HELLO_MALFORMED, "hello length");
        }
        final Fields f = parseFields(b);
        verifyBinding(f);
        return f;
    }

    /**
     * The exact byte string the ACK signature covers. Exposed because every
     * port and the vector generator must build the SAME transcript.
     *
     * @param helloBytes the initiator's exact HELLO payload bytes
     * @param ackFields the ACK's field bytes, before the signature
     * @return the message to sign or verify
     */
    public static byte[] ackAuthMessage(final byte[] helloBytes, final byte[] ackFields) {
        return LumosCrypto.concat(
                LumosConstants.HELLO_AUTH_INFO_VALUE.getBytes(StandardCharsets.UTF_8),
                LumosCrypto.sha256(helloBytes), ackFields);
    }

    /**
     * Answers a received HELLO with this side's fields, signed by its identity.
     *
     * @param responder this side's fields
     * @param helloBytes the initiator's exact HELLO payload bytes
     * @param signer the Ed25519 identity signer seam
     * @return the hello_ack_v1 bytes
     * @throws GeneralSecurityException when the signing backend fails
     */
    public static byte[] buildAck(final Fields responder, final byte[] helloBytes,
            final Signer signer) throws GeneralSecurityException {
        final byte[] fields = responder.encode();
        return LumosCrypto.concat(fields, signer.sign(ackAuthMessage(helloBytes, fields)));
    }

    /**
     * Authenticates a received HELLO_ACK against one's OWN sent HELLO bytes.
     * On success the returned fields are an authenticated, LIVE peer - the
     * only thing presence may be built from (TRS-ENV-002).
     *
     * @param ownHelloBytes the HELLO this side actually sent
     * @param ackBytes the received ACK
     * @return the authenticated fields
     * @throws Frame.WireException with the exact stable code
     */
    public static Fields verifyAck(final byte[] ownHelloBytes, final byte[] ackBytes)
            throws Frame.WireException {
        if (ackBytes.length != ACK_LEN) {
            throw new Frame.WireException(LumosErrors.ERR_HELLO_MALFORMED, "ack length");
        }
        final byte[] fieldBytes = Arrays.copyOfRange(ackBytes, 0, HELLO_LEN);
        final Fields f = parseFields(fieldBytes);
        verifyBinding(f);
        if (!LumosCrypto.ed25519Verify(f.ed25519Public,
                ackAuthMessage(ownHelloBytes, fieldBytes),
                Arrays.copyOfRange(ackBytes, HELLO_LEN, ACK_LEN))) {
            throw new Frame.WireException(LumosErrors.ERR_HELLO_SIGNATURE_INVALID,
                    "ack auth signature");
        }
        return f;
    }

    private static Fields parseFields(final byte[] b) throws Frame.WireException {
        if ((b[1] & 0xFF) != HELLO_V1) {
            throw new Frame.WireException(LumosErrors.ERR_HELLO_MALFORMED,
                    "unsupported hello version");
        }
        final int protocolVersion = b[0] & 0xFF;
        if ((protocolVersion >>> 4) != LumosConstants.PROTOCOL_VERSION_MAJOR) {
            // The TRS-PROTO-017 in-band re-confirmation, same rule as the
            // frame header (ADR-007): a differing MAJOR nibble fails closed.
            throw new Frame.WireException(LumosErrors.ERR_HELLO_VERSION_UNSUPPORTED,
                    "major version");
        }
        return new Fields(protocolVersion,
                ((b[2] & 0xFF) << 8) | (b[3] & 0xFF),
                ((long) (b[4] & 0xFF) << 24) | ((long) (b[5] & 0xFF) << 16)
                        | ((long) (b[6] & 0xFF) << 8) | (b[7] & 0xFF),
                Arrays.copyOfRange(b, 8, 20),
                Arrays.copyOfRange(b, 20, 52),
                Arrays.copyOfRange(b, 52, 84),
                Arrays.copyOfRange(b, 84, 148),
                Arrays.copyOfRange(b, 148, HELLO_LEN));
    }

    private static void verifyBinding(final Fields f) throws Frame.WireException {
        final byte[] message = LumosCrypto.concat(
                LumosConstants.X3DH_IDENTITY_BINDING_INFO_VALUE.getBytes(StandardCharsets.UTF_8),
                f.x25519Public);
        if (!LumosCrypto.ed25519Verify(f.ed25519Public, message, f.bindingSignature)) {
            throw new Frame.WireException(LumosErrors.ERR_HELLO_BINDING_INVALID,
                    "identity binding");
        }
    }
}
