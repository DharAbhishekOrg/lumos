/*
 * Lumos - android-app/app / Raw-to-JCA key conversion for identity verification.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-IDN-002, TRS-CRY-001
 * ADRs: ADR-001, ADR-008
 * Threading: any
 * Notes: The conversion goes through the X.509 SubjectPublicKeyInfo encoding
 * rather than the EdEC spec classes on purpose: EdECPoint, EdECPublicKeySpec
 * and NamedParameterSpec.ED25519 are API 33+ types, and referencing them makes
 * every caller carry an API gate even on paths that never run below 33. An
 * Ed25519 SPKI is a FIXED 12-byte DER prefix followed by the raw RFC 8032 key
 * - there is exactly one wildcard-free encoding - so prepending that prefix
 * and handing the result to X509EncodedKeySpec (API 1) is byte-for-byte the
 * same key with no new-API surface. Runtime availability of the "Ed25519"
 * algorithm itself is the real capability boundary (JVM 17 and API 33+ have
 * it; 26-32 need the ADR-001 libsodium provider) and is probed where it is
 * used, not assumed here.
 */

package com.lumos.app.identity;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;

/** Converts raw wire-format public keys into JCA keys. */
public final class RawKeys {

    /** Raw key length for Ed25519 and X25519 public keys. */
    private static final int RAW_KEY_BYTES = 32;

    /**
     * The DER prefix of an Ed25519 SubjectPublicKeyInfo: a 42-byte SEQUENCE
     * holding the id-Ed25519 OID (1.3.101.112) and a 33-byte BIT STRING whose
     * payload is the raw key. Transcribed from RFC 8410 section 4; the raw key
     * is always the final 32 bytes.
     */
    private static final byte[] ED25519_SPKI_PREFIX = {
        0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00,
    };

    /**
     * The DER prefix of an Ed25519 PKCS#8 PrivateKeyInfo: a 46-byte SEQUENCE -
     * version 0, the id-Ed25519 OID (1.3.101.112), and an OCTET STRING holding
     * an inner OCTET STRING whose payload is the raw 32-byte seed. Transcribed
     * from RFC 8410 section 7; there is exactly one wildcard-free v1 encoding,
     * and it is byte-identical to what the JDK's own generator emits - which
     * IdentityBindingVectorsTest proves by handing one to the JCA.
     */
    static final byte[] ED25519_PKCS8_PREFIX = {
        0x30, 0x2e, 0x02, 0x01, 0x00, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70,
        0x04, 0x22, 0x04, 0x20,
    };

    /** The X25519 PKCS#8 prefix; identical shape under OID 1.3.101.110. */
    static final byte[] X25519_PKCS8_PREFIX = {
        0x30, 0x2e, 0x02, 0x01, 0x00, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x6e,
        0x04, 0x22, 0x04, 0x20,
    };

    private RawKeys() {
        throw new AssertionError("no instances");
    }

    /**
     * Converts a raw Ed25519 public key (RFC 8032 encoding) into a JCA key.
     *
     * @param raw the 32-byte encoding: little-endian y, x-sign in the top bit
     * @return the public key
     * @throws GeneralSecurityException on malformed input or a runtime without
     *     the Ed25519 algorithm (the API 26-32 capability boundary)
     */
    public static PublicKey ed25519Public(final byte[] raw) throws GeneralSecurityException {
        if (raw.length != RAW_KEY_BYTES) {
            throw new GeneralSecurityException("Ed25519 public key must be 32 bytes");
        }
        final byte[] spki = new byte[ED25519_SPKI_PREFIX.length + RAW_KEY_BYTES];
        System.arraycopy(ED25519_SPKI_PREFIX, 0, spki, 0, ED25519_SPKI_PREFIX.length);
        System.arraycopy(raw, 0, spki, ED25519_SPKI_PREFIX.length, RAW_KEY_BYTES);
        return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(spki));
    }

    /**
     * Wraps a raw Ed25519 seed as PKCS#8, the envelope's canonical form.
     *
     * @param seed the raw 32-byte RFC 8032 seed
     * @return the PKCS#8 encoding; the caller zeroes it after use
     * @throws GeneralSecurityException on a wrong-length seed
     */
    static byte[] ed25519PrivatePkcs8(final byte[] seed) throws GeneralSecurityException {
        return privatePkcs8(ED25519_PKCS8_PREFIX, seed, "Ed25519 seed");
    }

    /**
     * Wraps a raw X25519 scalar as PKCS#8, the envelope's canonical form.
     *
     * @param scalar the raw 32-byte Curve25519 scalar
     * @return the PKCS#8 encoding; the caller zeroes it after use
     * @throws GeneralSecurityException on a wrong-length scalar
     */
    static byte[] x25519PrivatePkcs8(final byte[] scalar) throws GeneralSecurityException {
        return privatePkcs8(X25519_PKCS8_PREFIX, scalar, "X25519 scalar");
    }

    /**
     * Recovers the raw private bytes from one of this class's PKCS#8 forms.
     *
     * @param pkcs8 the encoding to open
     * @param prefix which algorithm's prefix it must carry
     * @return the raw 32 private bytes; the caller zeroes them after use
     * @throws GeneralSecurityException when the encoding is not prefix||raw32
     */
    static byte[] rawPrivateFromPkcs8(final byte[] pkcs8, final byte[] prefix)
            throws GeneralSecurityException {
        if (pkcs8.length != prefix.length + RAW_KEY_BYTES) {
            throw new GeneralSecurityException("unexpected PKCS#8 length");
        }
        for (int i = 0; i < prefix.length; i++) {
            if (pkcs8[i] != prefix[i]) {
                throw new GeneralSecurityException("unexpected PKCS#8 prefix");
            }
        }
        final byte[] raw = new byte[RAW_KEY_BYTES];
        System.arraycopy(pkcs8, prefix.length, raw, 0, RAW_KEY_BYTES);
        return raw;
    }

    /**
     * Builds prefix||raw32 after checking the raw length.
     *
     * @param prefix the fixed PKCS#8 prefix
     * @param raw the raw private bytes
     * @param what names the input in the failure message
     * @return the PKCS#8 encoding
     * @throws GeneralSecurityException on a wrong-length input
     */
    private static byte[] privatePkcs8(final byte[] prefix, final byte[] raw, final String what)
            throws GeneralSecurityException {
        if (raw.length != RAW_KEY_BYTES) {
            throw new GeneralSecurityException(what + " must be 32 bytes");
        }
        final byte[] pkcs8 = new byte[prefix.length + RAW_KEY_BYTES];
        System.arraycopy(prefix, 0, pkcs8, 0, prefix.length);
        System.arraycopy(raw, 0, pkcs8, prefix.length, RAW_KEY_BYTES);
        return pkcs8;
    }
}
