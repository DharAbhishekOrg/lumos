/*
 * Lumos - android-app/protocol-java / The JCA backend: the JVM's own curves and AEAD. Never on Android.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-CRY-001, TRS-CRY-003, TRS-TST-001
 * ADRs: ADR-001
 * Threading: any
 * Notes: The JDK 17+ JCA - XDH, Ed25519 and ChaCha20-Poly1305 - serving the
 * conformance suite, the backend, and the app's JVM unit tests. Android has
 * none of XDH, the XEC/EdEC key specs or Ed25519 verification below API 33,
 * so the app installs LibsodiumCryptoBackend at start and this class is
 * never loaded there; lumoslint's minsdk-api rule allows the API-33 key
 * classes in this file and nowhere else in the library.
 *
 * The raw<->key conversions matter: X25519 public keys are LITTLE-endian
 * u-coordinates while BigInteger is big-endian, and Ed25519 raw encoding
 * carries the x-sign in the top bit of the last byte. Getting either
 * backwards produces a key that is valid but wrong, which is exactly the
 * class of cross-language bug the vectors exist to catch.
 */

package com.lumos.protocol.wire;

import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.EdECPoint;
import java.security.spec.EdECPublicKeySpec;
import java.security.spec.NamedParameterSpec;
import java.security.spec.XECPrivateKeySpec;
import java.security.spec.XECPublicKeySpec;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** The JCA backend. */
public final class JcaCryptoBackend implements CryptoBackend {

    /** The one instance; it holds no state. */
    public static final JcaCryptoBackend INSTANCE = new JcaCryptoBackend();

    private static final int KEY_SIZE = 32;
    private static final byte TOP_BIT_CLEAR = 0x7F;
    private static final int SIGN_BIT = 0x80;
    private static final byte[] BASE_POINT = basePoint();

    private JcaCryptoBackend() { }

    private static byte[] basePoint() {
        final byte[] b = new byte[KEY_SIZE];
        b[0] = 9;
        return b;
    }

    private static PublicKey x25519Public(final byte[] raw) throws GeneralSecurityException {
        if (raw.length != KEY_SIZE) {
            throw new GeneralSecurityException("X25519 public key must be 32 bytes");
        }
        // RFC 7748 masks the top bit of the final byte before use.
        final byte[] le = raw.clone();
        le[KEY_SIZE - 1] &= TOP_BIT_CLEAR;
        final XECPublicKeySpec spec = new XECPublicKeySpec(NamedParameterSpec.X25519,
                new BigInteger(1, LumosCrypto.reversed(le)));
        return KeyFactory.getInstance("XDH").generatePublic(spec);
    }

    private static PrivateKey x25519Private(final byte[] raw) throws GeneralSecurityException {
        if (raw.length != KEY_SIZE) {
            throw new GeneralSecurityException("X25519 private key must be 32 bytes");
        }
        return KeyFactory.getInstance("XDH").generatePrivate(
                new XECPrivateKeySpec(NamedParameterSpec.X25519, raw.clone()));
    }

    @Override
    public byte[] x25519(final byte[] privateScalar, final byte[] peerPublic) throws GeneralSecurityException {
        final KeyAgreement ka = KeyAgreement.getInstance("XDH");
        ka.init(x25519Private(privateScalar));
        ka.doPhase(x25519Public(peerPublic), true);
        return ka.generateSecret();
    }

    @Override
    public byte[] x25519Base(final byte[] privateScalar) throws GeneralSecurityException {
        return x25519(privateScalar, BASE_POINT);
    }

    private static PublicKey ed25519Public(final byte[] raw) throws GeneralSecurityException {
        if (raw.length != KEY_SIZE) {
            throw new GeneralSecurityException("Ed25519 public key must be 32 bytes");
        }
        // RFC 8032: little-endian y, with the x-coordinate sign in the top bit.
        final byte[] le = raw.clone();
        final boolean xOdd = (le[KEY_SIZE - 1] & SIGN_BIT) != 0;
        le[KEY_SIZE - 1] &= TOP_BIT_CLEAR;
        final BigInteger y = new BigInteger(1, LumosCrypto.reversed(le));
        return KeyFactory.getInstance("Ed25519")
                .generatePublic(new EdECPublicKeySpec(NamedParameterSpec.ED25519, new EdECPoint(xOdd, y)));
    }

    @Override
    public boolean ed25519Verify(final byte[] publicKey, final byte[] message, final byte[] signature) {
        try {
            final Signature s = Signature.getInstance("Ed25519");
            s.initVerify(ed25519Public(publicKey));
            s.update(message);
            return s.verify(signature);
        } catch (GeneralSecurityException e) {
            // A malformed key or signature is a verification failure, not a
            // distinguishable error: surfacing which would be an oracle.
            return false;
        }
    }

    @Override
    public byte[] aeadSeal(final byte[] key, final byte[] nonce, final byte[] plaintext, final byte[] aad)
            throws GeneralSecurityException {
        final Cipher c = Cipher.getInstance("ChaCha20-Poly1305");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "ChaCha20"), new IvParameterSpec(nonce));
        c.updateAAD(aad);
        return c.doFinal(plaintext);
    }

    @Override
    public byte[] aeadOpen(final byte[] key, final byte[] nonce, final byte[] ciphertext, final byte[] aad)
            throws GeneralSecurityException {
        final Cipher c = Cipher.getInstance("ChaCha20-Poly1305");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "ChaCha20"), new IvParameterSpec(nonce));
        c.updateAAD(aad);
        return c.doFinal(ciphertext);
    }
}
