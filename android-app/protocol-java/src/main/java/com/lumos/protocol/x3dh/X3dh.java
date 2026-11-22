/*
 * Lumos - android-app/protocol-java / X3DH session agreement, for production use.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-CRY-001, TRS-CRY-005, TRS-CRY-014, TRS-CRY-015, SRS-RMT-007
 * ADRs: ADR-001, ADR-008
 * Threading: any
 * Notes: A decision-for-decision port of protocol/crypto/x3dh/x3dh.go, which
 * until WP-05 S7a existed on Android only inside the KAT replay. Bundle
 * signatures are verified BEFORE any DH and fail closed with one stable code
 * (ADR-008 condition 2). DH1..DH4 and the HKDF over F | DH1 | DH2 | DH3
 * [| DH4] follow the X3DH specification; the transcript - both Ed25519
 * identities, both identity-DH keys, the signed and one-time prekey ids, the
 * protocol version and the match id - becomes the session's associated data
 * (TRS-CRY-014). Private keys arrive as raw scalars: where they live is the
 * app's business, not the protocol's.
 */

package com.lumos.protocol.x3dh;

import com.lumos.protocol.LumosConstants;
import com.lumos.protocol.LumosErrors;
import com.lumos.protocol.wire.Frame;
import com.lumos.protocol.wire.LumosCrypto;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;

/** X3DH key agreement for remote-text sessions. */
public final class X3dh {

    /** The derived shared-secret length. */
    public static final int SK_SIZE = 32;

    private static final int KEY_LEN = 32;
    private static final int U32_LEN = 4;

    private X3dh() {
        throw new AssertionError("no instances");
    }

    /**
     * A responder's public prekey bundle.
     *
     * @param identityEd Ed25519 identity public key
     * @param identityDh X25519 identity-DH public key (ADR-008)
     * @param identityDhSig Ed25519 signature binding identityDh
     * @param spkId signed-prekey id
     * @param spk signed-prekey public key
     * @param spkSig signature over the signed prekey
     * @param opkId one-time prekey id, ignored when opk is null
     * @param opk one-time prekey public key, or null when none was issued
     */
    public record Bundle(byte[] identityEd, byte[] identityDh, byte[] identityDhSig, long spkId,
            byte[] spk, byte[] spkSig, long opkId, byte[] opk) {

        /**
         * Defensive copies: a bundle is an immutable value.
         *
         * @param identityEd see the record
         * @param identityDh see the record
         * @param identityDhSig see the record
         * @param spkId see the record
         * @param spk see the record
         * @param spkSig see the record
         * @param opkId see the record
         * @param opk see the record
         */
        public Bundle {
            identityEd = identityEd.clone();
            identityDh = identityDh.clone();
            identityDhSig = identityDhSig.clone();
            spk = spk.clone();
            spkSig = spkSig.clone();
            opk = opk == null ? null : opk.clone();
        }

        /** @return a copy of the Ed25519 identity key */
        @Override
        public byte[] identityEd() {
            return identityEd.clone();
        }

        /** @return a copy of the identity-DH key */
        @Override
        public byte[] identityDh() {
            return identityDh.clone();
        }

        /** @return a copy of the identity binding signature */
        @Override
        public byte[] identityDhSig() {
            return identityDhSig.clone();
        }

        /** @return a copy of the signed prekey */
        @Override
        public byte[] spk() {
            return spk.clone();
        }

        /** @return a copy of the signed-prekey signature */
        @Override
        public byte[] spkSig() {
            return spkSig.clone();
        }

        /** @return a copy of the one-time prekey, or null */
        @Override
        public byte[] opk() {
            return opk == null ? null : opk.clone();
        }
    }

    /**
     * The agreed session material.
     *
     * @param sk the 32-byte shared secret
     * @param ad the transcript hash, the session's associated data
     * @param usedOpk whether a one-time prekey entered the agreement
     */
    public record Result(byte[] sk, byte[] ad, boolean usedOpk) {

        /**
         * Defensive copies.
         *
         * @param sk see the record
         * @param ad see the record
         * @param usedOpk see the record
         */
        public Result {
            sk = sk.clone();
            ad = ad.clone();
        }

        /** @return a copy of the shared secret */
        @Override
        public byte[] sk() {
            return sk.clone();
        }

        /** @return a copy of the associated data */
        @Override
        public byte[] ad() {
            return ad.clone();
        }
    }

    private static byte[] utf8(final String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] be32(final long v) {
        return ByteBuffer.allocate(U32_LEN).putInt((int) v).array();
    }

    private static Frame.WireException rejected() {
        return new Frame.WireException(LumosErrors.ERR_CRYPTO_PREKEY_SIGNATURE_INVALID, "bundle signature rejected");
    }

    private static Frame.WireException dhFailed() {
        return new Frame.WireException(LumosErrors.ERR_CRYPTO_AEAD_AUTH_FAILED, "dh failed");
    }

    /**
     * Whether sig binds dh to the Ed25519 identity ed (ADR-008).
     *
     * @param ed Ed25519 public key
     * @param dh X25519 public key
     * @param sig the binding signature
     * @return true when it verifies
     */
    public static boolean verifyIdentityBinding(final byte[] ed, final byte[] dh, final byte[] sig) {
        return LumosCrypto.ed25519Verify(ed, LumosCrypto.concat(utf8(LumosConstants.X3DH_IDENTITY_BINDING_INFO_VALUE), dh), sig);
    }

    /**
     * Checks both bundle signatures.
     *
     * @param b the bundle
     * @throws Frame.WireException ERR_CRYPTO_PREKEY_SIGNATURE_INVALID when either fails
     */
    public static void verifyBundle(final Bundle b) throws Frame.WireException {
        if (b.identityEd.length != KEY_LEN || b.identityDh.length != KEY_LEN || b.spk.length != KEY_LEN) {
            throw rejected();
        }
        if (!verifyIdentityBinding(b.identityEd, b.identityDh, b.identityDhSig)) {
            throw rejected();
        }
        final byte[] prekey = LumosCrypto.concat(utf8(LumosConstants.X3DH_PREKEY_SIGNING_INFO_VALUE), be32(b.spkId), b.spk);
        if (!LumosCrypto.ed25519Verify(b.identityEd, prekey, b.spkSig)) {
            throw rejected();
        }
    }

    /**
     * The TRS-CRY-014 transcript hash, initiator-first.
     *
     * @param initiatorEd initiator Ed25519 identity
     * @param responderEd responder Ed25519 identity
     * @param initiatorDh initiator identity-DH public key
     * @param responderDh responder identity-DH public key
     * @param spkId signed-prekey id
     * @param opkId one-time prekey id or X3DH_OPK_ID_NONE
     * @param matchId the match identifier
     * @return the 32-byte associated data
     */
    public static byte[] transcript(final byte[] initiatorEd, final byte[] responderEd, final byte[] initiatorDh,
            final byte[] responderDh, final long spkId, final long opkId, final byte[] matchId) {
        return LumosCrypto.sha256(LumosCrypto.concat(
                utf8(LumosConstants.X3DH_TRANSCRIPT_INFO_VALUE), initiatorEd, responderEd, initiatorDh, responderDh,
                be32(spkId), be32(opkId), new byte[] {(byte) LumosConstants.PROTOCOL_VERSION_ENCODED}, matchId));
    }

    private static byte[] deriveSk(final byte[] dh1, final byte[] dh2, final byte[] dh3, final byte[] dh4) {
        final byte[] f = new byte[LumosConstants.X3DH_F_PREFIX_LEN];
        Arrays.fill(f, (byte) LumosConstants.X3DH_F_PREFIX_FILL_BYTE);
        final byte[] ikm = LumosCrypto.concat(f, dh1, dh2, dh3, dh4);
        try {
            return LumosCrypto.hkdf(ikm, new byte[KEY_LEN], utf8(LumosConstants.X3DH_KDF_INFO_VALUE), SK_SIZE);
        } finally {
            Arrays.fill(ikm, (byte) 0);
        }
    }

    private static byte[] dh(final byte[] priv, final byte[] pub) throws Frame.WireException {
        try {
            return LumosCrypto.x25519(LumosCrypto.x25519Private(priv), LumosCrypto.x25519Public(pub));
        } catch (final GeneralSecurityException e) {
            throw dhFailed();
        }
    }

    private static byte[] pub(final byte[] priv) throws Frame.WireException {
        try {
            return LumosCrypto.x25519PublicFromPrivate(priv);
        } catch (final GeneralSecurityException e) {
            throw dhFailed();
        }
    }

    /**
     * The initiator side, against a verified bundle.
     *
     * @param identityEd the initiator's Ed25519 public identity
     * @param identityDhPriv the initiator's identity-DH scalar
     * @param ephemeralPriv a fresh ephemeral scalar from the CSPRNG
     * @param bundle the responder's bundle
     * @param matchId the match identifier
     * @return shared secret and associated data
     * @throws Frame.WireException a signature or agreement failure, with its stable code
     */
    public static Result initiate(final byte[] identityEd, final byte[] identityDhPriv, final byte[] ephemeralPriv,
            final Bundle bundle, final byte[] matchId) throws Frame.WireException {
        verifyBundle(bundle);
        final byte[] dh1 = dh(identityDhPriv, bundle.spk);
        final byte[] dh2 = dh(ephemeralPriv, bundle.identityDh);
        final byte[] dh3 = dh(ephemeralPriv, bundle.spk);
        final byte[] dh4 = bundle.opk == null ? new byte[0] : dh(ephemeralPriv, bundle.opk);
        final byte[] sk = deriveSk(dh1, dh2, dh3, dh4);
        final long opkId = bundle.opk == null ? LumosConstants.X3DH_OPK_ID_NONE_DEFAULT : bundle.opkId;
        final byte[] ad = transcript(identityEd, bundle.identityEd, pub(identityDhPriv), bundle.identityDh,
                bundle.spkId, opkId, matchId);
        return new Result(sk, ad, bundle.opk != null);
    }

    /**
     * The responder side, once the initiator's keys arrive.
     *
     * @param identityEd the responder's Ed25519 public identity
     * @param identityDhPriv the responder's identity-DH scalar
     * @param spkPriv the signed-prekey scalar
     * @param spkId its id
     * @param opkPriv the consumed one-time prekey scalar, or null
     * @param opkId its id, ignored when opkPriv is null
     * @param initiatorEd the initiator's Ed25519 identity
     * @param initiatorDh the initiator's identity-DH public key
     * @param initiatorEphemeral the initiator's ephemeral public key
     * @param matchId the match identifier
     * @return shared secret and associated data
     * @throws Frame.WireException an agreement failure, with its stable code
     */
    public static Result respond(final byte[] identityEd, final byte[] identityDhPriv, final byte[] spkPriv,
            final long spkId, final byte[] opkPriv, final long opkId, final byte[] initiatorEd,
            final byte[] initiatorDh, final byte[] initiatorEphemeral, final byte[] matchId)
            throws Frame.WireException {
        final byte[] dh1 = dh(spkPriv, initiatorDh);
        final byte[] dh2 = dh(identityDhPriv, initiatorEphemeral);
        final byte[] dh3 = dh(spkPriv, initiatorEphemeral);
        final byte[] dh4 = opkPriv == null ? new byte[0] : dh(opkPriv, initiatorEphemeral);
        final byte[] sk = deriveSk(dh1, dh2, dh3, dh4);
        final long usedOpk = opkPriv == null ? LumosConstants.X3DH_OPK_ID_NONE_DEFAULT : opkId;
        final byte[] ad = transcript(initiatorEd, identityEd, initiatorDh, pub(identityDhPriv), spkId, usedOpk, matchId);
        return new Result(sk, ad, opkPriv != null);
    }
}
