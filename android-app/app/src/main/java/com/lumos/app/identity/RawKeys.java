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
}
