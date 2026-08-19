/*
 * Lumos - android-app/app / RFC 5869 HKDF-SHA-256 for the local key hierarchy.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-CRY-001, SRS-NFR-SEC-002
 * ADRs: ADR-001
 * Threading: any
 * Notes: Extract-then-expand over HMAC-SHA-256 and nothing more, mirroring the
 * conformance runner's implementation byte for byte (that module is test-scope
 * and cannot be a production dependency, so the mirror is deliberate and small
 * rather than shared). JDK 21 and Android both predate javax.crypto.KDF. Every
 * Lumos KDF uses a zero-filled 32-byte salt; callers pass it explicitly so the
 * choice is visible at the call site.
 */

package com.lumos.app.crypto;

import java.security.GeneralSecurityException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** RFC 5869 HKDF-SHA-256, the KDF mandated by ADR-001. */
public final class Hkdf {

    /** Size of an HMAC-SHA-256 output and of every Lumos symmetric key, in bytes. */
    public static final int HASH_LEN = 32;

    private Hkdf() {
        throw new AssertionError("no instances");
    }

    /**
     * Computes HMAC-SHA-256.
     *
     * @param key the MAC key
     * @param data the message
     * @return the 32-byte tag
     */
    public static byte[] hmacSha256(final byte[] key, final byte[] data) {
        try {
            final Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            // HMAC-SHA-256 is mandatory on every supported platform; its absence
            // is a broken runtime, not a condition the caller can handle.
            throw new IllegalStateException("HMAC-SHA-256 unavailable", e);
        }
    }

    /**
     * Derives key material: extract then expand.
     *
     * @param ikm input keying material
     * @param salt extraction salt; Lumos KDFs pass a zero-filled 32-byte salt
     * @param info domain-separation context
     * @param length output length in bytes
     * @return the derived bytes
     */
    public static byte[] derive(final byte[] ikm, final byte[] salt, final byte[] info, final int length) {
        final byte[] prk = hmacSha256(salt, ikm);
        final byte[] out = new byte[length];
        byte[] block = new byte[0];
        int filled = 0;
        byte counter = 1;
        while (filled < length) {
            final byte[] input = new byte[block.length + info.length + 1];
            System.arraycopy(block, 0, input, 0, block.length);
            System.arraycopy(info, 0, input, block.length, info.length);
            input[input.length - 1] = counter;
            block = hmacSha256(prk, input);
            final int take = Math.min(block.length, length - filled);
            System.arraycopy(block, 0, out, filled, take);
            filled += take;
            counter++;
        }
        return out;
    }
}
