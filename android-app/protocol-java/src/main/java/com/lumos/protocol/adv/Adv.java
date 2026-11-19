/*
 * Lumos - android-app/protocol-java / BLE advertisement hint: EID schedule and authTag.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-PROTO-009, TRS-PROTO-017, TRS-PROTO-018, SRS-IDN-004
 * ADRs: ADR-002, ADR-004
 * Threading: any
 * Notes: The Java port of protocol/adv, held to test-vectors/adv/
 * ble_advertisement.json. THIS PAYLOAD CARRIES NO TRUST. The authTag is cheap
 * pre-connection noise rejection only; a matching tag means "worth connecting
 * to", never "authenticated peer". Canonical identity, capabilities, version
 * and venue context are re-established inside the authenticated GATT HELLO
 * (TRS-PROTO-017), and presence keys on that authenticated identity - so a
 * hintless peer (every iOS peer, and any Android peer in degraded mode) behaves
 * identically in the Nearby feed. Epoch arithmetic is pure: the caller supplies
 * unix seconds, this class never reads a clock - which is also what lets the
 * synthetic discovery harness script rotation.
 */

package com.lumos.protocol.adv;

import com.lumos.protocol.LumosConstants;
import com.lumos.protocol.LumosErrors;
import com.lumos.protocol.wire.Frame;
import com.lumos.protocol.wire.LumosCrypto;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

/** The adv_v1 Android Service-Data hint (TRS Sec. 9.3, ADR-002). */
public final class Adv {

    /** Total adv_v1 hint length. */
    public static final int TOTAL_LEN = LumosConstants.ADV_V1_LAYOUT_TOTAL_LEN;

    /** Rotating EID length. */
    public static final int EID_LEN = LumosConstants.ADV_V1_LAYOUT_EID_LEN;

    /** Advertisement auth tag length. */
    public static final int AUTH_TAG_LEN = LumosConstants.ADV_AUTH_TAG_LEN_DEFAULT;

    /** Android scan response length. */
    public static final int SCAN_RESPONSE_LEN = LumosConstants.ADV_V1_SCAN_RESPONSE_LAYOUT_TOTAL_LEN;

    /** mode_flags bit 0: venue mode. */
    public static final int MODE_FLAG_VENUE = 0x01;

    /** mode_flags bits 1..7: reserved, must be zero. */
    public static final int MODE_FLAG_RESERVED_MASK = 0xFE;

    private static final byte[] ZERO_SALT = new byte[32];

    private Adv() {
        throw new AssertionError("no instances");
    }

    /** A decoded adv_v1 hint. Structure, never trust. */
    public static final class Payload {

        private final int version;
        private final int modeFlags;
        private final byte[] eid;
        private final byte[] authTag;

        private Payload(final int version, final int modeFlags, final byte[] eid, final byte[] authTag) {
            this.version = version;
            this.modeFlags = modeFlags;
            this.eid = eid.clone();
            this.authTag = authTag.clone();
        }

        /** @return the advertisement version byte */
        public int version() {
            return version;
        }

        /** @return the mode flags byte */
        public int modeFlags() {
            return modeFlags;
        }

        /** @return whether the venue mode flag is set */
        public boolean venue() {
            return (modeFlags & MODE_FLAG_VENUE) != 0;
        }

        /** @return a copy of the 12-byte rotating EID */
        public byte[] eid() {
            return eid.clone();
        }

        /** @return a copy of the 4-byte auth tag */
        public byte[] authTag() {
            return authTag.clone();
        }
    }

    /**
     * Returns the EID rotation epoch for a unix timestamp (SRS-IDN-004).
     *
     * @param unixSeconds the caller's clock, unix seconds
     * @param rotationSec rotation cadence; non-positive falls back to the
     *     register default so a broken override cannot freeze the schedule
     * @return the epoch index
     */
    public static long epoch(final long unixSeconds, final int rotationSec) {
        final int cadence = rotationSec <= 0 ? LumosConstants.EID_ROTATION_SEC_DEFAULT : rotationSec;
        return unixSeconds / cadence;
    }

    /**
     * Derives the per-identity advertisement key. K_adv never leaves the device.
     *
     * @param advSecret the device's long-term advertisement secret
     * @return the 32-byte K_adv
     */
    public static byte[] deriveKAdv(final byte[] advSecret) {
        return LumosCrypto.hkdf(advSecret, ZERO_SALT,
                LumosConstants.ADV_KEY_KDF_INFO_VALUE.getBytes(StandardCharsets.UTF_8), 32);
    }

    /**
     * Derives the rotating ephemeral identifier for an epoch. Distinct epochs
     * yield unlinkable identifiers (SRS-IDN-004).
     *
     * @param kAdv the advertisement key
     * @param epoch the rotation epoch
     * @return the 12-byte EID
     */
    public static byte[] deriveEid(final byte[] kAdv, final long epoch) {
        final byte[] info = LumosCrypto.concat(
                LumosConstants.ADV_EID_KDF_INFO_VALUE.getBytes(StandardCharsets.UTF_8), be64(epoch));
        return LumosCrypto.hkdf(kAdv, ZERO_SALT, info, EID_LEN);
    }

    /**
     * Computes the advertisement tag: the first AUTH_TAG_LEN bytes of
     * HMAC-SHA-256(K_adv, info | version | mode_flags | eid | epoch).
     *
     * @param kAdv the advertisement key
     * @param version the advertisement version byte
     * @param modeFlags the mode flags byte
     * @param eid the rotating EID
     * @param epoch the rotation epoch
     * @return the 4-byte tag
     */
    public static byte[] authTag(final byte[] kAdv, final int version, final int modeFlags,
            final byte[] eid, final long epoch) {
        final byte[] mac = LumosCrypto.hmacSha256(kAdv, LumosCrypto.concat(
                LumosConstants.ADV_AUTH_TAG_INFO_VALUE.getBytes(StandardCharsets.UTF_8),
                new byte[] {(byte) version, (byte) modeFlags}, eid, be64(epoch)));
        return Arrays.copyOf(mac, AUTH_TAG_LEN);
    }

    /**
     * Assembles the 18-byte Android service-data hint for an epoch.
     *
     * @param kAdv the advertisement key
     * @param modeFlags the mode flags byte; reserved bits must be clear
     * @param epoch the rotation epoch
     * @return the adv_v1 bytes
     * @throws Frame.WireException when a reserved mode flag is set
     */
    public static byte[] build(final byte[] kAdv, final int modeFlags, final long epoch)
            throws Frame.WireException {
        if ((modeFlags & MODE_FLAG_RESERVED_MASK) != 0) {
            throw new Frame.WireException(LumosErrors.ERR_ADV_AUTH_TAG_MISMATCH, "reserved mode flag");
        }
        final byte[] eid = deriveEid(kAdv, epoch);
        final int version = 1;
        return LumosCrypto.concat(new byte[] {(byte) version, (byte) modeFlags}, eid,
                authTag(kAdv, version, modeFlags, eid, epoch));
    }

    /**
     * Decodes an adv_v1 payload without verifying it. Structure only: this is
     * the whole of the cheap discard a scanner can apply to a stranger's hint,
     * and even a verified hint is never an authenticated identity
     * (TRS-PROTO-017).
     *
     * @param b the service data bytes
     * @return the decoded payload
     * @throws Frame.WireException when the structure is not a current adv_v1
     */
    public static Payload parse(final byte[] b) throws Frame.WireException {
        if (b == null || b.length != TOTAL_LEN) {
            throw new Frame.WireException(LumosErrors.ERR_ADV_AUTH_TAG_MISMATCH, "advertisement length");
        }
        final int version = b[0] & 0xFF;
        final int modeFlags = b[1] & 0xFF;
        if (version != 1) {
            throw new Frame.WireException(LumosErrors.ERR_ADV_AUTH_TAG_MISMATCH, "advertisement version");
        }
        if ((modeFlags & MODE_FLAG_RESERVED_MASK) != 0) {
            throw new Frame.WireException(LumosErrors.ERR_ADV_AUTH_TAG_MISMATCH, "reserved mode flag");
        }
        return new Payload(version, modeFlags,
                Arrays.copyOfRange(b, 2, 2 + EID_LEN),
                Arrays.copyOfRange(b, 2 + EID_LEN, TOTAL_LEN));
    }

    /**
     * Recomputes the tag for an epoch and compares in constant time. A mismatch
     * is discarded silently before any connection attempt (ADR-002); a match
     * still carries no trust.
     *
     * @param kAdv the advertisement key the tag is expected under
     * @param b the service data bytes
     * @param epoch the epoch to verify against
     * @return the verified payload
     * @throws Frame.WireException when the structure or the tag is wrong
     */
    public static Payload verify(final byte[] kAdv, final byte[] b, final long epoch)
            throws Frame.WireException {
        final Payload p = parse(b);
        final byte[] want = authTag(kAdv, p.version(), p.modeFlags(), p.eid, epoch);
        if (!MessageDigest.isEqual(want, p.authTag)) {
            throw new Frame.WireException(LumosErrors.ERR_ADV_AUTH_TAG_MISMATCH, "advertisement discarded");
        }
        return p;
    }

    /**
     * Assembles the 6-byte Android scan response: capabilityBits(2) |
     * venueHint(4).
     *
     * @param capabilityBits the capability bits
     * @param venueHint the venue hint
     * @return the scan response bytes
     */
    public static byte[] buildScanResponse(final int capabilityBits, final long venueHint) {
        final byte[] out = new byte[SCAN_RESPONSE_LEN];
        out[0] = (byte) (capabilityBits >>> 8);
        out[1] = (byte) capabilityBits;
        out[2] = (byte) (venueHint >>> 24);
        out[3] = (byte) (venueHint >>> 16);
        out[4] = (byte) (venueHint >>> 8);
        out[5] = (byte) venueHint;
        return out;
    }

    private static byte[] be64(final long value) {
        final byte[] out = new byte[8];
        for (int i = 0; i < 8; i++) {
            out[i] = (byte) (value >>> (56 - 8 * i));
        }
        return out;
    }
}
