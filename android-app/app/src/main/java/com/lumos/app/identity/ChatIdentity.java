/*
 * Lumos - android-app/app / The device identity, unwrapped for the chat: its DH scalar and a signer.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-RMT-007, TRS-CRY-001, SRS-IDN-001
 * ADRs: ADR-001, ADR-003, ADR-008, ADR-010
 * Threading: any
 * Notes: The identity's private halves live wrapped under the hardware KEK
 * (ADR-003). The chat needs two of them while it runs: the X25519 scalar,
 * for X3DH and the ratchet, and the Ed25519 key, to sign the prekeys it
 * publishes. ADR-010 rules out key-level authentication gating because
 * background decryption requires the key, so they are unwrapped once for the
 * runtime's lifetime and zeroed by close(). Signing goes through the
 * IdentityCrypto backend (libsodium on Android), never through the JCA.
 */

package com.lumos.app.identity;

import com.lumos.app.keystore.KekStore;
import com.lumos.protocol.chat.RemoteSession;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;

/** The identity, unwrapped for the chat. */
public final class ChatIdentity implements AutoCloseable {

    private final RemoteSession.Identity identity;
    private final byte[] ed25519Pkcs8;
    private final IdentityCrypto crypto;

    private ChatIdentity(final RemoteSession.Identity identity, final byte[] ed25519Pkcs8, final IdentityCrypto crypto) {
        this.identity = identity;
        this.ed25519Pkcs8 = ed25519Pkcs8;
        this.crypto = crypto;
    }

    /**
     * Unwraps the stored identity's private halves under the KEK.
     *
     * @param record the stored identity
     * @param kek the key-encryption key it was wrapped under
     * @param crypto the signing backend
     * @return the unlocked identity; close it when the chat stops
     * @throws GeneralSecurityException when the envelope does not unwrap
     */
    public static ChatIdentity unlock(final IdentityKeyRecord record, final KekStore kek, final IdentityCrypto crypto)
            throws GeneralSecurityException {
        final byte[] envelope = kek.unwrap(record.wrappedPrivateKeys(),
                IdentityKeyGenerator.ENVELOPE_AAD.getBytes(StandardCharsets.UTF_8));
        try {
            final PrivateKeyEnvelope.Keys keys = PrivateKeyEnvelope.decode(envelope);
            final byte[] xPkcs8 = keys.x25519Pkcs8();
            final byte[] scalar = RawKeys.rawPrivateFromPkcs8(xPkcs8, RawKeys.X25519_PKCS8_PREFIX);
            Arrays.fill(xPkcs8, (byte) 0);
            final RemoteSession.Identity id = new RemoteSession.Identity(record.ed25519Public(), scalar,
                    record.bindingSignature());
            Arrays.fill(scalar, (byte) 0);
            return new ChatIdentity(id, keys.ed25519Pkcs8(), crypto);
        } finally {
            Arrays.fill(envelope, (byte) 0);
        }
    }

    /** @return the identity as the chat protocol takes it */
    public RemoteSession.Identity identity() {
        return identity;
    }

    /**
     * Signs with the identity's Ed25519 key.
     *
     * @param message the exact bytes
     * @return the signature
     * @throws GeneralSecurityException on backend failure
     */
    public byte[] sign(final byte[] message) throws GeneralSecurityException {
        return crypto.signBinding(ed25519Pkcs8, message);
    }

    /** Zeroes the Ed25519 key this object holds. */
    @Override
    public void close() {
        Arrays.fill(ed25519Pkcs8, (byte) 0);
    }
}
