/*
 * Lumos - android-app/protocol-java / Remote-text sessions: X3DH establishment, then sealed envelopes.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-RMT-001, SRS-RMT-007, SRS-RMT-009, TRS-CRY-001, TRS-CRY-002, TRS-CRY-005
 * ADRs: ADR-001, ADR-008
 * Threading: serial-executor
 * Notes: protocol/chat/session.go step for step, proven by replaying the
 * scripted session in test-vectors/chat/remote_text.json byte for byte. The
 * initiator verifies the peer's bundle, agrees, and starts the ratchet on the
 * peer's signed prekey; its PREKEY fields carry its identity-DH key and the
 * binding signature it already published in its own bundle (Ed25519 is
 * deterministic, so this equals signing afresh). The responder checks that
 * binding against the identity the match pinned, requires the one-time
 * prekey named to be the one it supplies, and starts its ratchet on the
 * signed prekey. open refuses an envelope whose authenticated id differs
 * from the id the relay delivered it under.
 */

package com.lumos.protocol.chat;

import com.lumos.protocol.LumosConstants;
import com.lumos.protocol.LumosErrors;
import com.lumos.protocol.ratchet.DoubleRatchet;
import com.lumos.protocol.wire.Frame;
import com.lumos.protocol.wire.LumosCrypto;
import com.lumos.protocol.x3dh.X3dh;
import java.security.GeneralSecurityException;
import java.util.Arrays;

/** Starts, accepts and uses remote-text sessions. */
public final class RemoteSession {

    private RemoteSession() {
        throw new AssertionError("no instances");
    }

    /**
     * A started session and the PREKEY fields to send until the peer replies.
     *
     * @param ratchet the initiator's ratchet
     * @param prekey the fields for first-contact envelopes
     */
    public record Started(DoubleRatchet ratchet, RemoteText.PrekeyFields prekey) { }

    /**
     * The device's own identity, as the protocol needs it.
     *
     * @param identityEd the Ed25519 public identity
     * @param identityDhPriv the identity-DH scalar
     * @param identityDhSig the published binding signature over the identity-DH key
     */
    public record Identity(byte[] identityEd, byte[] identityDhPriv, byte[] identityDhSig) {

        /**
         * Copies.
         *
         * @param identityEd see the record
         * @param identityDhPriv see the record
         * @param identityDhSig see the record
         */
        public Identity {
            identityEd = identityEd.clone();
            identityDhPriv = identityDhPriv.clone();
            identityDhSig = identityDhSig.clone();
        }

        /** @return a copy of the Ed25519 identity */
        @Override
        public byte[] identityEd() {
            return identityEd.clone();
        }

        /** @return a copy of the identity-DH scalar */
        @Override
        public byte[] identityDhPriv() {
            return identityDhPriv.clone();
        }

        /** @return a copy of the binding signature */
        @Override
        public byte[] identityDhSig() {
            return identityDhSig.clone();
        }
    }

    private static Frame.WireException rejected(final String detail) {
        return new Frame.WireException(LumosErrors.ERR_CRYPTO_PREKEY_SIGNATURE_INVALID, detail);
    }

    private static byte[] publicOf(final byte[] priv) throws Frame.WireException {
        try {
            return LumosCrypto.x25519PublicFromPrivate(priv);
        } catch (final GeneralSecurityException e) {
            throw new Frame.WireException(LumosErrors.ERR_CRYPTO_AEAD_AUTH_FAILED, "bad scalar");
        }
    }

    /**
     * Starts a session with a matched peer from its fetched bundle.
     *
     * @param own this device's identity
     * @param bundle the peer's bundle
     * @param matchId the match identifier
     * @param ephemeralPriv a fresh ephemeral scalar
     * @param ratchetPriv a fresh first ratchet scalar
     * @return the ratchet and the PREKEY fields
     * @throws Frame.WireException a bundle or agreement failure
     */
    public static Started initiate(final Identity own, final X3dh.Bundle bundle, final byte[] matchId,
            final byte[] ephemeralPriv, final byte[] ratchetPriv) throws Frame.WireException {
        final X3dh.Result res = X3dh.initiate(own.identityEd, own.identityDhPriv, ephemeralPriv, bundle, matchId);
        final DoubleRatchet ratchet = DoubleRatchet.initiator(res.sk(), res.ad(), bundle.spk(), ratchetPriv,
                DoubleRatchet.Bounds.defaults());
        final long opkId = bundle.opk() == null ? LumosConstants.X3DH_OPK_ID_NONE_DEFAULT : bundle.opkId();
        return new Started(ratchet, new RemoteText.PrekeyFields(publicOf(own.identityDhPriv), own.identityDhSig,
                publicOf(ephemeralPriv), bundle.spkId(), opkId));
    }

    /**
     * The responder's secret prekeys the PREKEY fields name.
     *
     * @param spkPriv the signed-prekey scalar
     * @param spkId its id
     * @param opkPriv the one-time prekey scalar, or null when the envelope names none
     * @param opkId its id
     */
    public record ResponderKeys(byte[] spkPriv, long spkId, byte[] opkPriv, long opkId) {

        /**
         * Copies.
         *
         * @param spkPriv see the record
         * @param spkId see the record
         * @param opkPriv see the record
         * @param opkId see the record
         */
        public ResponderKeys {
            spkPriv = spkPriv.clone();
            opkPriv = opkPriv == null ? null : opkPriv.clone();
        }

        /** @return a copy of the signed-prekey scalar */
        @Override
        public byte[] spkPriv() {
            return spkPriv.clone();
        }

        /** @return a copy of the one-time prekey scalar, or null */
        @Override
        public byte[] opkPriv() {
            return opkPriv == null ? null : opkPriv.clone();
        }
    }

    /**
     * Accepts a PREKEY envelope from the identity the match pinned. The caller
     * deletes the consumed one-time prekey once the first message opens.
     *
     * @param own this device's identity
     * @param keys the prekeys named
     * @param initiatorEd the peer's Ed25519 identity from the match
     * @param p the PREKEY fields
     * @param matchId the match identifier
     * @return the responder's ratchet
     * @throws Frame.WireException ERR_CRYPTO_PREKEY_SIGNATURE_INVALID when anything does not check out
     */
    public static DoubleRatchet respond(final Identity own, final ResponderKeys keys, final byte[] initiatorEd,
            final RemoteText.PrekeyFields p, final byte[] matchId) throws Frame.WireException {
        if (!X3dh.verifyIdentityBinding(initiatorEd, p.initiatorIdentityDh(), p.initiatorIdentityDhSig())) {
            throw rejected("initiator identity binding rejected");
        }
        if (p.spkId() != keys.spkId()) {
            throw rejected("signed prekey id unknown");
        }
        final boolean namesNone = p.opkId() == LumosConstants.X3DH_OPK_ID_NONE_DEFAULT;
        if (namesNone != (keys.opkPriv() == null) || keys.opkPriv() != null && p.opkId() != keys.opkId()) {
            throw rejected("one-time prekey id mismatch");
        }
        final X3dh.Result res = X3dh.respond(own.identityEd, own.identityDhPriv, keys.spkPriv(), keys.spkId(),
                keys.opkPriv(), keys.opkId(), initiatorEd, p.initiatorIdentityDh(), p.ephemeral(), matchId);
        return DoubleRatchet.responder(res.sk(), res.ad(), keys.spkPriv(), DoubleRatchet.Bounds.defaults());
    }

    /**
     * Encrypts one plaintext.
     *
     * @param ratchet the session
     * @param plaintext the message
     * @param prekey the PREKEY fields while the peer has not replied, else null
     * @param now the caller's clock
     * @return the envelope bytes
     * @throws Frame.WireException on an encryption failure
     */
    public static byte[] seal(final DoubleRatchet ratchet, final RemoteText.Plaintext plaintext,
            final RemoteText.PrekeyFields prekey, final long now) throws Frame.WireException {
        final DoubleRatchet.Sealed s = ratchet.encrypt(plaintext.encode(), now);
        return new RemoteText.Envelope(prekey, s.header(), s.ciphertext()).encode();
    }

    /**
     * Decrypts one envelope delivered under outerId.
     *
     * @param ratchet the session
     * @param e the envelope
     * @param outerId the id the relay delivered it under
     * @param now the caller's clock
     * @param kp fresh ratchet keys
     * @return the plaintext
     * @throws Frame.WireException an authentication, replay or format failure
     */
    public static RemoteText.Plaintext open(final DoubleRatchet ratchet, final RemoteText.Envelope e,
            final byte[] outerId, final long now, final DoubleRatchet.KeyProvider kp) throws Frame.WireException {
        final RemoteText.Plaintext p = RemoteText.decodePlaintext(ratchet.decrypt(e.header(), e.ciphertext(), now, kp));
        if (!Arrays.equals(p.messageId(), outerId)) {
            throw new Frame.WireException(LumosErrors.ERR_PAYLOAD_MALFORMED, "authenticated id differs from delivered id");
        }
        return p;
    }
}
