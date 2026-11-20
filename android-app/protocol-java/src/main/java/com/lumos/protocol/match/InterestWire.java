/*
 * Lumos - android-app/protocol-java / The consent-stage envelopes, mirrored.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-MAT-001, SRS-MAT-002, SRS-MAT-004, SRS-MAT-009
 * ADRs: ADR-002
 * Threading: any
 * Notes: The Go reference byte for byte, with its one loud property intact:
 * the response verdict alphabet is ACCEPT and UNMATCH, so a decline or
 * ignore is UNENCODABLE - SRS-MAT-004's silence enforced at the codec. The
 * request envelope is content-opaque around the app's REQUESTER-audience
 * preview blob; identity rides the authenticated session, never the payload.
 */

package com.lumos.protocol.match;

import java.util.Arrays;

/** Class-3/4 consent-stage payload codecs. */
public final class InterestWire {

    /** The response alphabet. There is no decline on purpose. */
    public enum Verdict {
        /** Mutual consent underway. */
        ACCEPT((byte) 1),
        /** The one deliberately visible ending (SRS-MAT-009). */
        UNMATCH((byte) 2);

        private final byte wire;

        Verdict(final byte wire) {
            this.wire = wire;
        }

        /** @return the wire byte */
        public byte wireByte() {
            return wire;
        }
    }

    /** A stable-coded consent-stage decode failure. */
    public static final class WireException extends Exception {

        private static final long serialVersionUID = 1L;

        private final String code;

        WireException(final String code, final String why) {
            super(why + " (" + code + ")");
            this.code = code;
        }

        /** @return the stable error-registry code */
        public String code() {
            return code;
        }
    }

    private static final String BAD_PAYLOAD = "ERR_MATCH_BAD_PAYLOAD";
    private static final String BAD_VERDICT = "ERR_MATCH_BAD_VERDICT";
    private static final int REQUEST_VERSION = 1;
    private static final int RESPONSE_VERSION = 1;

    private InterestWire() {
        throw new AssertionError("no instances");
    }

    /**
     * Builds a class-3 payload around the preview blob.
     *
     * @param preview the request-stage preview bytes
     * @return the payload
     * @throws WireException when the preview exceeds the length prefix
     */
    public static byte[] encodeRequest(final byte[] preview) throws WireException {
        if (preview.length > 0xFFFF) {
            throw new WireException(BAD_PAYLOAD,
                    "preview longer than the length prefix can carry");
        }
        final byte[] out = new byte[3 + preview.length];
        out[0] = REQUEST_VERSION;
        out[1] = (byte) (preview.length >> 8);
        out[2] = (byte) preview.length;
        System.arraycopy(preview, 0, out, 3, preview.length);
        return out;
    }

    /**
     * Opens a class-3 payload.
     *
     * @param payload the received bytes
     * @return the preview blob
     * @throws WireException on any structural refusal
     */
    public static byte[] decodeRequest(final byte[] payload) throws WireException {
        if (payload.length < 3) {
            throw new WireException(BAD_PAYLOAD, "truncated request");
        }
        if (payload[0] != REQUEST_VERSION) {
            throw new WireException(BAD_PAYLOAD, "unknown request version " + payload[0]);
        }
        final int want = ((payload[1] & 0xFF) << 8) | (payload[2] & 0xFF);
        if (payload.length != 3 + want) {
            throw new WireException(BAD_PAYLOAD, "length prefix disagrees with the bytes");
        }
        return Arrays.copyOfRange(payload, 3, payload.length);
    }

    /**
     * Builds a class-4 payload. The enum already keeps a decline out.
     *
     * @param verdict accept or unmatch
     * @return the two payload bytes
     */
    public static byte[] encodeResponse(final Verdict verdict) {
        return new byte[] {RESPONSE_VERSION, verdict.wireByte()};
    }

    /**
     * Opens a class-4 payload.
     *
     * @param payload the received bytes
     * @return the verdict
     * @throws WireException on any structural refusal or an alphabet miss
     */
    public static Verdict decodeResponse(final byte[] payload) throws WireException {
        if (payload.length != 2) {
            throw new WireException(BAD_PAYLOAD, "response must be two bytes");
        }
        if (payload[0] != RESPONSE_VERSION) {
            throw new WireException(BAD_PAYLOAD, "unknown response version " + payload[0]);
        }
        for (final Verdict v : Verdict.values()) {
            if (v.wireByte() == payload[1]) {
                return v;
            }
        }
        throw new WireException(BAD_VERDICT,
                "verdict " + payload[1] + " is outside the alphabet");
    }
}
