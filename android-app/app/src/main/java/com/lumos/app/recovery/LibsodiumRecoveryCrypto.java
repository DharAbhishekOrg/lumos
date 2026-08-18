/*
 * Lumos - android-app/app / The libsodium adapter behind RecoveryCrypto.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-CRY-001, TRS-CRY-011, TRS-CRY-013, TRS-PAT-001
 * ADRs: ADR-001, ADR-003
 * Threading: any
 * Notes: THE ONLY FILE IN LUMOS THAT IMPORTS LAZYSODIUM. That is the point of
 * the seam next door: the MPL-2.0 dependency has one point of contact, and the
 * owner's "unmodified use only" condition is checkable by reading one import
 * block rather than by trusting a grep.
 *
 * Why libsodium rather than the platform, for each of the two primitives:
 *
 *   Argon2id - the JCA has no Argon2 at any API level. There is no alternative
 *     short of implementing a memory-hard KDF by hand, which ADR-001 forbids and
 *     which nobody should do.
 *   ChaCha20-Poly1305 - the JCA has it, from API 28. minSdk is 26, so two API
 *     levels would silently have no cipher. Taking libsodium's closes that gap
 *     with the implementation ADR-001 already names, rather than by raising
 *     minSdk to suit an implementation detail.
 *
 * SodiumAndroid loads a native library in its constructor, so an instance of
 * this class is not free. Construct one and pass it around (constructor
 * injection, TRS Sec. 24.2); do not build one per call.
 */

package com.lumos.app.recovery;

import com.goterl.lazysodium.LazySodiumAndroid;
import com.goterl.lazysodium.SodiumAndroid;
import com.goterl.lazysodium.interfaces.AEAD;
import com.goterl.lazysodium.interfaces.PwHash;
import com.lumos.protocol.LumosConstants;
import com.sun.jna.NativeLong;
import java.util.Arrays;

/** Argon2id and ChaCha20-Poly1305 from libsodium, through Lazysodium. */
public final class LibsodiumRecoveryCrypto implements RecoveryCrypto {

    /** KiB to bytes. */
    private static final long BYTES_PER_KIB = 1024L;

    private final LazySodiumAndroid sodium;

    /**
     * @param sodium the loaded binding; construct one per process
     */
    public LibsodiumRecoveryCrypto(final LazySodiumAndroid sodium) {
        this.sodium = sodium;
    }

    /**
     * Loads libsodium and wraps it.
     *
     * @return an adapter over a freshly loaded binding
     */
    public static LibsodiumRecoveryCrypto load() {
        return new LibsodiumRecoveryCrypto(new LazySodiumAndroid(new SodiumAndroid()));
    }

    @Override
    public byte[] argon2id(final byte[] passphrase, final byte[] salt, final RecoveryParams params)
            throws RecoveryException {
        if (salt.length != LumosConstants.RECOVERY_ARGON2ID_SALT_BYTES) {
            throw RecoveryException.openFailed();
        }
        // The caller has already validated params, which is what makes the two
        // casts below safe: memory is at most 1 GiB and iterations at most 64.
        final byte[] key = new byte[LumosConstants.RECOVERY_ARGON2ID_KEY_BYTES];
        final boolean ok = sodium.cryptoPwHash(
                key, key.length,
                passphrase, passphrase.length,
                salt,
                params.iterations(),
                new NativeLong(params.memoryKib() * BYTES_PER_KIB),
                PwHash.Alg.PWHASH_ALG_ARGON2ID13);
        if (!ok) {
            // libsodium reports a failed allocation the same way it reports bad
            // arguments. Either way there is no key, and the caller must not be
            // told which - the parameters came out of a file an attacker may own.
            throw RecoveryException.openFailed();
        }
        return key;
    }

    @Override
    public byte[] seal(final byte[] key, final byte[] nonce, final byte[] plaintext, final byte[] aad)
            throws RecoveryException {
        final byte[] sealed = new byte[plaintext.length + AEAD.CHACHA20POLY1305_IETF_ABYTES];
        final long[] sealedLength = new long[1];
        final boolean ok = sodium.cryptoAeadChaCha20Poly1305IetfEncrypt(
                sealed, sealedLength,
                plaintext, plaintext.length,
                aad, aad.length,
                null, nonce, key);
        if (!ok || sealedLength[0] != sealed.length) {
            throw RecoveryException.openFailed();
        }
        return sealed;
    }

    @Override
    public byte[] open(final byte[] key, final byte[] nonce, final byte[] ciphertext, final byte[] aad)
            throws RecoveryException {
        if (ciphertext.length < AEAD.CHACHA20POLY1305_IETF_ABYTES) {
            throw RecoveryException.openFailed();
        }
        final int plaintextLength = ciphertext.length - AEAD.CHACHA20POLY1305_IETF_ABYTES;
        // One spare byte when the content is empty. A zero-length array handed to
        // a native pointer parameter is the kind of edge that works on one ABI
        // and not the next, and the empty_content vector exists precisely because
        // a record sealing nothing is a real case.
        final byte[] scratch = new byte[Math.max(plaintextLength, 1)];
        final long[] plaintextWritten = new long[1];
        final boolean ok = sodium.cryptoAeadChaCha20Poly1305IetfDecrypt(
                scratch, plaintextWritten,
                null,
                ciphertext, ciphertext.length,
                aad, aad.length,
                nonce, key);
        if (!ok || plaintextWritten[0] != plaintextLength) {
            Arrays.fill(scratch, (byte) 0);
            throw RecoveryException.openFailed();
        }
        final byte[] plaintext = Arrays.copyOf(scratch, plaintextLength);
        Arrays.fill(scratch, (byte) 0);
        return plaintext;
    }
}
