/*
 * Lumos - android-app/app / Per-domain data keys derived under the KEK-wrapped root.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-DAT-002, TRS-AND-004, SRS-NFR-SEC-002
 * ADRs: ADR-003, ADR-004
 * Threading: serial-executor
 * Notes: One random root secret, wrapped by the KEK; every store's key is an
 * HKDF derivation from that root under a domain label. Destroying the KEK
 * therefore erases every store at once - the crypto-erasure the keys-first
 * wipe order relies on (owner ruling 2026-08-14). Domain labels are versioned
 * local-storage strings, named per TRS-CODE-005; they are not Sec. 23 wire
 * constants because nothing about them crosses a device boundary, and the iOS
 * hierarchy uses the same labels for naming consistency (TRS-CODE-007), not
 * for interoperability.
 */

package com.lumos.app.data;

import com.lumos.app.crypto.Hkdf;
import com.lumos.app.keystore.KekStore;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

/** Derives per-domain data keys from one KEK-wrapped root secret. */
public final class DataKeyHierarchy {

    /** The local stores that receive derived keys. */
    public enum DataDomain {

        /** The encrypted Room/SQLite database (TRS-AND-004). */
        DATABASE("lumos/local/db-key/v1"),

        /** The encrypted media object store (SRS-PRO-004). */
        MEDIA_STORE("lumos/local/media-key/v1");

        private final String info;

        DataDomain(final String info) {
            this.info = info;
        }

        /**
         * The HKDF domain-separation label for this store.
         *
         * @return the versioned label
         */
        public String info() {
            return info;
        }
    }

    /** Root secret length in bytes. */
    private static final int ROOT_SECRET_BYTES = 32;

    /** Derived data-key length in bytes. */
    private static final int DATA_KEY_BYTES = 32;

    /** AAD binding the wrapped root to its role, mirroring the identity envelope's. */
    public static final String ROOT_AAD = "lumos/local/data-root/v1";

    /**
     * Process-wide CSPRNG. SecureRandom is thread-safe and self-reseeding; one
     * instance per process is the platform-recommended usage, and creating one
     * per call is the pattern SpotBugs rightly flags (DMI_RANDOM_USED_ONLY_ONCE).
     */
    private static final SecureRandom RANDOM = new SecureRandom();

    private final KekStore kek;
    private final byte[] wrappedRoot;

    private DataKeyHierarchy(final KekStore kek, final byte[] wrappedRoot) {
        this.kek = kek;
        this.wrappedRoot = wrappedRoot;
    }

    /**
     * Creates a fresh hierarchy: a CSPRNG root secret, sealed under the KEK.
     *
     * @param kek the wrapping store
     * @return the hierarchy
     * @throws GeneralSecurityException if wrapping fails
     */
    public static DataKeyHierarchy create(final KekStore kek) throws GeneralSecurityException {
        final byte[] root = new byte[ROOT_SECRET_BYTES];
        RANDOM.nextBytes(root);
        try {
            return new DataKeyHierarchy(kek, kek.wrap(root, aad()));
        } finally {
            Arrays.fill(root, (byte) 0);
        }
    }

    /**
     * Reattaches to a hierarchy whose wrapped root was persisted earlier.
     *
     * @param kek the same KEK the root was wrapped under
     * @param wrappedRoot the persisted wrapped root
     * @return the hierarchy
     */
    public static DataKeyHierarchy restore(final KekStore kek, final byte[] wrappedRoot) {
        return new DataKeyHierarchy(kek, wrappedRoot.clone());
    }

    /**
     * The wrapped root for persistence.
     *
     * @return a copy of the KEK-wrapped root secret
     */
    public byte[] wrappedRoot() {
        return wrappedRoot.clone();
    }

    /**
     * Derives one domain's data key.
     *
     * <p>The root is unwrapped transiently and zeroed before return; after
     * {@link KekStore#destroy} this method can only fail, which is the
     * crypto-erasure property KeysFirstWipeTest pins.
     *
     * @param domain the requesting store
     * @return the 32-byte domain key; the caller zeroes it when done
     * @throws GeneralSecurityException if the KEK is destroyed or unwrap fails
     */
    public byte[] deriveKey(final DataDomain domain) throws GeneralSecurityException {
        final byte[] root = kek.unwrap(wrappedRoot, aad());
        try {
            return Hkdf.derive(root, new byte[Hkdf.HASH_LEN],
                    domain.info().getBytes(StandardCharsets.UTF_8), DATA_KEY_BYTES);
        } finally {
            Arrays.fill(root, (byte) 0);
        }
    }

    /**
     * The root envelope's AAD bytes.
     *
     * @return UTF-8 of {@link #ROOT_AAD}
     */
    private static byte[] aad() {
        return ROOT_AAD.getBytes(StandardCharsets.UTF_8);
    }
}
