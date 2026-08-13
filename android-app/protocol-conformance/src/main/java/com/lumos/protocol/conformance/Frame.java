/*
 * Lumos - protocol-conformance / Java frame codec per TRS Sec. 9.1.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-PROTO-001, TRS-PROTO-002, TRS-PROTO-004, TRS-PROTO-006, TRS-PROTO-007, TRS-PROTO-013
 * ADRs: ADR-004, ADR-007
 * Threading: any
 * Notes: An INDEPENDENT reimplementation of the canonical frame, written against
 * TRS Sec. 9.1 and the generated constants - not a translation of the Go codec.
 * That independence is the whole point: if this and the Go encoder agree on
 * every committed vector byte, the wire format is genuinely specified rather
 * than merely implemented once. The decode validation order matches the wire
 * contract because the negative vectors assert exact codes. Bounds are checked
 * before any payload allocation (TRS-PROTO-007).
 */
package com.lumos.protocol.conformance;

import com.lumos.protocol.LumosConstants;
import com.lumos.protocol.LumosErrors;
import com.lumos.protocol.MessageClass;
import com.lumos.protocol.MessageClassLimits;
import java.util.Arrays;

/** Canonical Lumos frame: encode, decode and the authenticated header. */
public final class Frame {

    /** Fixed authenticated header length for protocol v1 frames. */
    public static final int HEADER_LEN = LumosConstants.FRAME_HEADER_LEN_BYTES_DEFAULT;

    /** A protocol failure carrying a stable machine-readable code. */
    public static final class WireException extends Exception {
        private static final long serialVersionUID = 1L;

        private final String code;

        /**
         * @param code a stable code from the generated registry
         * @param detail non-oracle detail for humans
         */
        public WireException(final String code, final String detail) {
            super(code + ": " + detail);
            this.code = code;
        }

        /** @return the stable machine-readable code */
        public String code() {
            return code;
        }
    }

    /** Decoded frame header fields. */
    public static final class Header {
        /** Protocol version byte: high nibble major, low nibble minor. */
        public int version = LumosConstants.PROTOCOL_VERSION_ENCODED;
        /** Canonical message class byte. */
        public int type;
        /** Frame flags. */
        public int flags;
        /** 16-byte session identifier. */
        public byte[] sessionId = new byte[16];
        /** 16-byte message identifier. */
        public byte[] messageId = new byte[16];
        /** Fragment/message sequence. */
        public long sequence;
        /** Selective acknowledgement base. */
        public long ackBase;
        /** Relative or canonical expiry policy. */
        public long expirySeconds;
        /** Mesh forwarding ceiling. */
        public int hopLimit;

        /** @return whether the critical flag demands fail-closed handling */
        public boolean critical() {
            return (flags & LumosConstants.FRAME_FLAG_CRITICAL_DEFAULT) != 0;
        }

        /** @return the resolved message class, or null when undefined here */
        public MessageClass messageClass() {
            return MessageClass.fromCode(type);
        }
    }

    private final Header header;
    private final byte[] payload;

    /**
     * Creates a frame, copying the payload in.
     *
     * <p>The copy is not ceremony. A frame is the unit the conformance runner
     * compares against a golden vector, and a caller that kept a reference to
     * the array it passed could mutate the frame's bytes after the comparison
     * had been set up - which would show as an unreproducible vector failure
     * rather than as the aliasing bug it is.
     *
     * @param header decoded header
     * @param payload frame payload; copied, never retained
     */
    public Frame(final Header header, final byte[] payload) {
        this.header = header;
        this.payload = payload.clone();
    }

    /** @return the header */
    public Header header() {
        return header;
    }

    /** @return a copy of the payload; mutating it does not affect this frame */
    public byte[] payload() {
        return payload.clone();
    }

    private static void putU16(final byte[] out, final int at, final int v) {
        out[at] = (byte) ((v >>> 8) & 0xFF);
        out[at + 1] = (byte) (v & 0xFF);
    }

    private static void putU32(final byte[] out, final int at, final long v) {
        out[at] = (byte) ((v >>> 24) & 0xFF);
        out[at + 1] = (byte) ((v >>> 16) & 0xFF);
        out[at + 2] = (byte) ((v >>> 8) & 0xFF);
        out[at + 3] = (byte) (v & 0xFF);
    }

    private static int u16(final byte[] b, final int at) {
        return ((b[at] & 0xFF) << 8) | (b[at + 1] & 0xFF);
    }

    private static long u32(final byte[] b, final int at) {
        return ((long) (b[at] & 0xFF) << 24)
                | ((long) (b[at + 1] & 0xFF) << 16)
                | ((long) (b[at + 2] & 0xFF) << 8)
                | (b[at + 3] & 0xFF);
    }

    /**
     * Serialises a frame in network byte order.
     *
     * @param h header
     * @param payload payload bytes
     * @return the encoded frame
     * @throws WireException when the class is undefined or the payload exceeds its bound
     */
    public static byte[] encode(final Header h, final byte[] payload) throws WireException {
        final MessageClass mc = MessageClass.fromCode(h.type);
        if (mc == null) {
            throw new WireException(LumosErrors.ERR_FRAME_UNKNOWN_TYPE,
                    "cannot encode unknown message class");
        }
        final Long max = MessageClassLimits.maxPayloadBytes(mc);
        if (max == null) {
            throw new WireException(LumosErrors.ERR_FRAME_UNKNOWN_TYPE, "no payload bound");
        }
        if (payload.length > max) {
            throw new WireException(LumosErrors.ERR_FRAME_PAYLOAD_TOO_LARGE, "payload exceeds class bound");
        }

        final byte[] out = new byte[HEADER_LEN + payload.length];
        putU16(out, 0, LumosConstants.FRAME_MAGIC_DEFAULT);
        out[2] = (byte) h.version;
        out[3] = (byte) h.type;
        putU16(out, 4, h.flags);
        putU16(out, 6, HEADER_LEN);
        System.arraycopy(h.sessionId, 0, out, 8, 16);
        System.arraycopy(h.messageId, 0, out, 24, 16);
        putU32(out, 40, h.sequence);
        putU32(out, 44, h.ackBase);
        putU32(out, 48, payload.length);
        putU32(out, 52, h.expirySeconds);
        out[56] = (byte) h.hopLimit;
        out[57] = 0;
        System.arraycopy(payload, 0, out, HEADER_LEN, payload.length);
        return out;
    }

    /**
     * Returns the complete authenticated header - exactly the AEAD associated
     * data of TRS-PROTO-003.
     *
     * @param encoded a full encoded frame
     * @return the header bytes
     * @throws WireException when the input is shorter than a header
     */
    public static byte[] headerBytes(final byte[] encoded) throws WireException {
        if (encoded.length < HEADER_LEN) {
            throw new WireException(LumosErrors.ERR_FRAME_TRUNCATED, "shorter than a header");
        }
        return Arrays.copyOfRange(encoded, 0, HEADER_LEN);
    }

    /**
     * Applies the header rules that do not depend on the payload, in the order
     * the negative golden vectors assert: version major, header_length, then the
     * three conditions that are fatal ONLY on a critical frame. That ordering is
     * part of the wire contract, because each vector pins the exact error code
     * for a frame that violates several rules at once. The Go codec's
     * validateHeader is the same sequence; keeping the two structurally parallel
     * is what makes a divergence visible in review rather than only in a vector.
     *
     * @param h the parsed header
     * @param declaredHeaderLen the header_length field as read off the wire
     * @param reserved the reserved byte as read off the wire
     * @throws WireException with the stable code for the first rule violated
     */
    private static void validateHeader(final Header h, final int declaredHeaderLen, final int reserved)
            throws WireException {
        if ((h.version >>> 4) != LumosConstants.PROTOCOL_VERSION_MAJOR) {
            throw new WireException(LumosErrors.ERR_FRAME_UNSUPPORTED_VERSION, "major version");
        }
        if (declaredHeaderLen != HEADER_LEN) {
            throw new WireException(LumosErrors.ERR_FRAME_BAD_HEADER_LENGTH, "header length");
        }
        if (!h.critical()) {
            return;
        }
        if (MessageClass.fromCode(h.type) == null) {
            throw new WireException(LumosErrors.ERR_FRAME_UNKNOWN_TYPE, "unknown critical class");
        }
        if ((h.flags & LumosConstants.FRAME_FLAGS_RESERVED_MASK_DEFAULT) != 0) {
            throw new WireException(LumosErrors.ERR_FRAME_RESERVED_FLAG, "reserved flag");
        }
        if (reserved != 0) {
            throw new WireException(LumosErrors.ERR_FRAME_RESERVED_FIELD, "reserved byte");
        }
    }

    /**
     * Parses one complete frame.
     *
     * @param data encoded frame bytes
     * @return the decoded frame
     * @throws WireException with the exact stable code for every rule violation
     */
    public static Frame decode(final byte[] data) throws WireException {
        if (data.length < 2) {
            throw new WireException(LumosErrors.ERR_FRAME_TRUNCATED, "no magic");
        }
        if (u16(data, 0) != LumosConstants.FRAME_MAGIC_DEFAULT) {
            throw new WireException(LumosErrors.ERR_FRAME_BAD_MAGIC, "magic");
        }
        if (data.length < HEADER_LEN) {
            throw new WireException(LumosErrors.ERR_FRAME_TRUNCATED, "header");
        }

        final Header h = new Header();
        h.version = data[2] & 0xFF;
        h.type = data[3] & 0xFF;
        h.flags = u16(data, 4);
        final int declaredHeaderLen = u16(data, 6);
        h.sessionId = Arrays.copyOfRange(data, 8, 24);
        h.messageId = Arrays.copyOfRange(data, 24, 40);
        h.sequence = u32(data, 40);
        h.ackBase = u32(data, 44);
        final long payloadLen = u32(data, 48);
        h.expirySeconds = u32(data, 52);
        h.hopLimit = data[56] & 0xFF;
        final int reserved = data[57] & 0xFF;

        validateHeader(h, declaredHeaderLen, reserved);

        final MessageClass mc = MessageClass.fromCode(h.type);
        if (mc != null) {
            final Long max = MessageClassLimits.maxPayloadBytes(mc);
            if (max != null && payloadLen > max) {
                // Checked before allocation (TRS-PROTO-007).
                throw new WireException(LumosErrors.ERR_FRAME_PAYLOAD_TOO_LARGE, "declared payload");
            }
        }
        final long available = data.length - (long) HEADER_LEN;
        if (available < payloadLen) {
            throw new WireException(LumosErrors.ERR_FRAME_TRUNCATED, "payload");
        }
        if (available > payloadLen) {
            throw new WireException(LumosErrors.ERR_PAYLOAD_MALFORMED, "trailing bytes");
        }

        final byte[] payload = Arrays.copyOfRange(data, HEADER_LEN, HEADER_LEN + (int) payloadLen);
        return new Frame(h, payload);
    }
}
