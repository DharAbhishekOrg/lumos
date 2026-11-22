/*
 * Lumos - android-app/protocol-java / The remote-text wire: AMD-005 events, the ratchet plaintext and the envelope.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-RMT-011, SRS-RMT-004, SRS-RMT-009, TRS-PROTO-007
 * ADRs: ADR-001, ADR-008
 * Threading: any
 * Notes: The byte layouts of protocol/chat/remote.go, proven against
 * test-vectors/chat/remote_text.json:
 *   text event  = kind u8 | target[16] (edit, delete, reaction)
 *                 | body_len u32 BE | body (text, edit, reaction)
 *   plaintext   = message_id[16] | text event
 *   envelope    = 0x01 PREKEY | identity_dh[32] | identity_dh_sig[64]
 *                 | ephemeral[32] | spk_id u32 | opk_id u32 | header[40] | ciphertext
 *               | 0x02 NORMAL | header[40] | ciphertext
 * Every declared length is checked against the bytes that remain before
 * anything is copied (TRS-PROTO-007). An unknown event kind is reported as
 * ERR_TEXT_UNKNOWN_EVENT_KIND so the caller can ignore it (SRS-RMT-011).
 */

package com.lumos.protocol.chat;

import com.lumos.protocol.LumosConstants;
import com.lumos.protocol.LumosErrors;
import com.lumos.protocol.TextEventKind;
import com.lumos.protocol.ratchet.DoubleRatchet;
import com.lumos.protocol.wire.Frame;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;

/** Encoders and decoders for the remote-text wire. */
public final class RemoteText {

    /** MESSAGE_ID_LEN_BYTES. */
    public static final int MESSAGE_ID_SIZE = LumosConstants.MESSAGE_ID_LEN_BYTES_DEFAULT;
    /** The PREKEY envelope format byte. */
    public static final int FORMAT_PREKEY = 0x01;
    /** The NORMAL envelope format byte. */
    public static final int FORMAT_NORMAL = 0x02;

    private static final int KEY = 32;
    private static final int SIG = 64;
    private static final int U32 = 4;
    private static final int PREKEY_FIELDS = KEY + SIG + KEY + U32 + U32;
    private static final long U32_MASK = 0xFFFFFFFFL;

    private RemoteText() {
        throw new AssertionError("no instances");
    }

    private static Frame.WireException malformed(final String detail) {
        return new Frame.WireException(LumosErrors.ERR_PAYLOAD_MALFORMED, detail);
    }

    private static boolean hasTarget(final TextEventKind k) {
        return k != TextEventKind.TEXT;
    }

    private static boolean hasBody(final TextEventKind k) {
        return k != TextEventKind.DELETE_FOR_EVERYONE;
    }

    /**
     * One AMD-005 inner event.
     *
     * @param kind the event kind
     * @param target the target message id for edit, delete and reaction; null for text
     * @param body the body for text, edit and reaction; null for delete
     */
    public record TextEvent(TextEventKind kind, byte[] target, byte[] body) {

        /**
         * Validates the shape and copies the arrays.
         *
         * @param kind see the record
         * @param target see the record
         * @param body see the record
         */
        public TextEvent {
            if (hasTarget(kind) != (target != null) || target != null && target.length != MESSAGE_ID_SIZE) {
                throw new IllegalArgumentException(kind + " target is wrong for its kind");
            }
            if (hasBody(kind) != (body != null)) {
                throw new IllegalArgumentException(kind + " body is wrong for its kind");
            }
            target = target == null ? null : target.clone();
            body = body == null ? null : body.clone();
        }

        /** @return a copy of the target, or null */
        @Override
        public byte[] target() {
            return target == null ? null : target.clone();
        }

        /** @return a copy of the body, or null */
        @Override
        public byte[] body() {
            return body == null ? null : body.clone();
        }

        /** @return the wire bytes */
        public byte[] encode() {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(kind.code());
            if (target != null) {
                out.writeBytes(target);
            }
            if (body != null) {
                out.writeBytes(ByteBuffer.allocate(U32).putInt(body.length).array());
                out.writeBytes(body);
            }
            return out.toByteArray();
        }
    }

    /**
     * Parses an event; trailing bytes are refused.
     *
     * @param b the bytes
     * @return the event
     * @throws Frame.WireException with the stable code for what is wrong
     */
    public static TextEvent decodeEvent(final byte[] b) throws Frame.WireException {
        if (b.length < 1) {
            throw malformed("empty event");
        }
        final TextEventKind kind = TextEventKind.fromCode(b[0] & 0xFF);
        if (kind == null) {
            throw new Frame.WireException(LumosErrors.ERR_TEXT_UNKNOWN_EVENT_KIND, "unknown event kind");
        }
        int at = 1;
        byte[] target = null;
        if (hasTarget(kind)) {
            if (b.length - at < MESSAGE_ID_SIZE) {
                throw new Frame.WireException(LumosErrors.ERR_TEXT_MISSING_TARGET, "target_message_id missing");
            }
            target = Arrays.copyOfRange(b, at, at + MESSAGE_ID_SIZE);
            at += MESSAGE_ID_SIZE;
        }
        byte[] body = null;
        if (hasBody(kind)) {
            if (b.length - at < U32) {
                throw malformed("body length missing");
            }
            final long n = ByteBuffer.wrap(b, at, U32).getInt() & U32_MASK;
            at += U32;
            if (n != b.length - at) { // checked before the body is copied
                throw malformed("body length disagrees with the event");
            }
            body = Arrays.copyOfRange(b, at, b.length);
            at = b.length;
        }
        if (at != b.length) {
            throw malformed("trailing bytes after the event");
        }
        return new TextEvent(kind, target, body);
    }

    /**
     * What one ratchet message encrypts.
     *
     * @param messageId the authenticated message id
     * @param event the event
     */
    public record Plaintext(byte[] messageId, TextEvent event) {

        /**
         * Validates and copies.
         *
         * @param messageId see the record
         * @param event see the record
         */
        public Plaintext {
            if (messageId.length != MESSAGE_ID_SIZE) {
                throw new IllegalArgumentException("message id must be " + MESSAGE_ID_SIZE + " bytes");
            }
            messageId = messageId.clone();
        }

        /** @return a copy of the message id */
        @Override
        public byte[] messageId() {
            return messageId.clone();
        }

        /** @return message_id | event */
        public byte[] encode() {
            final byte[] ev = event.encode();
            final byte[] out = Arrays.copyOf(messageId, MESSAGE_ID_SIZE + ev.length);
            System.arraycopy(ev, 0, out, MESSAGE_ID_SIZE, ev.length);
            return out;
        }
    }

    /**
     * Parses message_id | event.
     *
     * @param b the bytes
     * @return the plaintext
     * @throws Frame.WireException with the stable code for what is wrong
     */
    public static Plaintext decodePlaintext(final byte[] b) throws Frame.WireException {
        if (b.length < MESSAGE_ID_SIZE) {
            throw malformed("plaintext shorter than a message id");
        }
        return new Plaintext(Arrays.copyOf(b, MESSAGE_ID_SIZE),
                decodeEvent(Arrays.copyOfRange(b, MESSAGE_ID_SIZE, b.length)));
    }

    /**
     * The X3DH initial-message fields of a PREKEY envelope.
     *
     * @param initiatorIdentityDh the initiator's identity-DH public key
     * @param initiatorIdentityDhSig its Ed25519 binding signature
     * @param ephemeral the initiator's ephemeral public key
     * @param spkId the responder's signed-prekey id
     * @param opkId the one-time prekey id, or X3DH_OPK_ID_NONE
     */
    public record PrekeyFields(byte[] initiatorIdentityDh, byte[] initiatorIdentityDhSig, byte[] ephemeral,
            long spkId, long opkId) {

        /**
         * Validates widths and copies.
         *
         * @param initiatorIdentityDh see the record
         * @param initiatorIdentityDhSig see the record
         * @param ephemeral see the record
         * @param spkId see the record
         * @param opkId see the record
         */
        public PrekeyFields {
            if (initiatorIdentityDh.length != KEY || initiatorIdentityDhSig.length != SIG || ephemeral.length != KEY) {
                throw new IllegalArgumentException("prekey field widths");
            }
            initiatorIdentityDh = initiatorIdentityDh.clone();
            initiatorIdentityDhSig = initiatorIdentityDhSig.clone();
            ephemeral = ephemeral.clone();
        }

        /** @return a copy of the identity-DH key */
        @Override
        public byte[] initiatorIdentityDh() {
            return initiatorIdentityDh.clone();
        }

        /** @return a copy of the binding signature */
        @Override
        public byte[] initiatorIdentityDhSig() {
            return initiatorIdentityDhSig.clone();
        }

        /** @return a copy of the ephemeral key */
        @Override
        public byte[] ephemeral() {
            return ephemeral.clone();
        }
    }

    /**
     * One TEXT_MESSAGE relay payload.
     *
     * @param prekey the PREKEY fields, or null for NORMAL
     * @param header the ratchet header
     * @param ciphertext the AEAD output
     */
    public record Envelope(PrekeyFields prekey, DoubleRatchet.Header header, byte[] ciphertext) {

        /**
         * Copies the ciphertext.
         *
         * @param prekey see the record
         * @param header see the record
         * @param ciphertext see the record
         */
        public Envelope {
            ciphertext = ciphertext.clone();
        }

        /** @return a copy of the ciphertext */
        @Override
        public byte[] ciphertext() {
            return ciphertext.clone();
        }

        /** @return the wire bytes */
        public byte[] encode() {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (prekey != null) {
                out.write(FORMAT_PREKEY);
                out.writeBytes(prekey.initiatorIdentityDh);
                out.writeBytes(prekey.initiatorIdentityDhSig);
                out.writeBytes(prekey.ephemeral);
                out.writeBytes(ByteBuffer.allocate(2 * U32).putInt((int) prekey.spkId).putInt((int) prekey.opkId).array());
            } else {
                out.write(FORMAT_NORMAL);
            }
            out.writeBytes(header.bytes());
            out.writeBytes(ciphertext);
            return out.toByteArray();
        }
    }

    /**
     * Parses an envelope. The ciphertext must be non-empty.
     *
     * @param b the bytes
     * @return the envelope
     * @throws Frame.WireException ERR_PAYLOAD_MALFORMED when the bytes are not an envelope
     */
    public static Envelope decodeEnvelope(final byte[] b) throws Frame.WireException {
        if (b.length < 1) {
            throw malformed("empty envelope");
        }
        int at = 1;
        PrekeyFields prekey = null;
        if ((b[0] & 0xFF) == FORMAT_PREKEY) {
            if (b.length - at < PREKEY_FIELDS) {
                throw malformed("prekey fields truncated");
            }
            final ByteBuffer ids = ByteBuffer.wrap(b, at + 2 * KEY + SIG, 2 * U32);
            prekey = new PrekeyFields(Arrays.copyOfRange(b, at, at + KEY),
                    Arrays.copyOfRange(b, at + KEY, at + KEY + SIG),
                    Arrays.copyOfRange(b, at + KEY + SIG, at + 2 * KEY + SIG),
                    ids.getInt() & U32_MASK, ids.getInt() & U32_MASK);
            at += PREKEY_FIELDS;
        } else if ((b[0] & 0xFF) != FORMAT_NORMAL) {
            throw malformed("unknown envelope format");
        }
        if (b.length - at <= DoubleRatchet.HEADER_SIZE) {
            throw malformed("ratchet header or ciphertext missing");
        }
        final DoubleRatchet.Header header;
        try {
            header = DoubleRatchet.Header.parse(Arrays.copyOfRange(b, at, at + DoubleRatchet.HEADER_SIZE));
        } catch (final Frame.WireException e) {
            throw malformed("ratchet header invalid");
        }
        return new Envelope(prekey, header, Arrays.copyOfRange(b, at + DoubleRatchet.HEADER_SIZE, b.length));
    }
}
