/*
 * Lumos - android-app/app / Restoring a recovery export onto a fresh identity.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-ONB-007, SRS-DAT-004, SRS-IDN-002
 * ADRs: ADR-003, ADR-008
 * Threading: serial-executor
 * Notes: SRS-ONB-007 is explicit and this class exists to make it true:
 * "restore creates a new cryptographic identity and peers see the standard
 * re-verification notice". Restore is therefore not a two-step where content
 * comes back and identity is somebody else's job - it generates the identity
 * itself, in the same call, and returns the new public key so the caller can
 * prove it changed.
 *
 * The order matters and is the reverse of the export's. The identity is created
 * FIRST: if content were written first and identity creation then failed, the
 * device would hold a restored profile under the OLD identity, which is exactly
 * the state ADR-003 forbids - a recovered account continuing a cryptographic
 * relationship it should have had to re-establish.
 *
 * The re-verification notice is a WP-04 surface. What WP-02 owes it is the
 * fact that the identity changed, and that is what RestoreResult carries.
 */

package com.lumos.app.recovery;

import com.lumos.app.identity.IdentityCrypto;
import com.lumos.app.identity.IdentityKeyGenerator;
import com.lumos.app.identity.IdentityKeyRecord;
import com.lumos.app.identity.IdentityRepository;
import com.lumos.app.keystore.KekStore;
import com.lumos.app.store.LocalStore;
import com.lumos.app.store.RecordKind;
import com.lumos.app.store.StoreException;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/** Opens a recovery export and restores its content under a new identity. */
public final class RecoveryRestore {

    /** What a restore produced, including the proof that the identity is new. */
    public static final class RestoreResult {

        private final byte[] previousEd25519Public;
        private final byte[] newEd25519Public;
        private final int restoredRows;
        private final List<RecoverySection> sectionsNotInTheFile;

        private RestoreResult(final byte[] previousEd25519Public, final byte[] newEd25519Public,
                final int restoredRows, final List<RecoverySection> sectionsNotInTheFile) {
            this.previousEd25519Public =
                    previousEd25519Public == null ? null : previousEd25519Public.clone();
            this.newEd25519Public = newEd25519Public.clone();
            this.restoredRows = restoredRows;
            this.sectionsNotInTheFile = List.copyOf(sectionsNotInTheFile);
        }

        /**
         * The identity that was on the device before, if there was one.
         *
         * @return a copy of the old Ed25519 public key, or null on a clean device
         */
        public byte[] previousEd25519Public() {
            return previousEd25519Public == null ? null : previousEd25519Public.clone();
        }

        /**
         * The identity the device has now.
         *
         * @return a copy of the new Ed25519 public key
         */
        public byte[] newEd25519Public() {
            return newEd25519Public.clone();
        }

        /**
         * Whether the identity actually changed.
         *
         * <p>The requirement stated as a question the caller can ask. On a
         * device that had no identity, this is true: there is now one that did
         * not exist, and no peer relationship survived.
         *
         * @return true when the device's identity is not the one it had
         */
        public boolean identityIsNew() {
            return previousEd25519Public == null
                    || !Arrays.equals(previousEd25519Public, newEd25519Public);
        }

        /**
         * How many rows came back.
         *
         * @return the restored row count
         */
        public int restoredRows() {
            return restoredRows;
        }

        /**
         * Sections the export could not contain, for the user to be told about.
         *
         * @return the sections the exporting build had no feature for
         */
        public List<RecoverySection> sectionsNotInTheFile() {
            return List.copyOf(sectionsNotInTheFile);
        }
    }

    private final LocalStore store;
    private final RecoveryRecord record;
    private final IdentityRepository identities;
    private final KekStore kek;
    private final IdentityCrypto crypto;

    /**
     * Assembles the restore flow from its collaborators.
     *
     * @param store the opened local store the content is written into
     * @param record the S4b record format, over its crypto seam
     * @param identities where the device identity is stored
     * @param kek the key-encryption key the new identity is wrapped under
     * @param crypto the identity backend - libsodium on Android, JCA on the JVM
     */
    public RecoveryRestore(final LocalStore store, final RecoveryRecord record,
            final IdentityRepository identities, final KekStore kek,
            final IdentityCrypto crypto) {
        this.store = store;
        this.record = record;
        this.identities = identities;
        this.kek = kek;
        this.crypto = crypto;
    }

    /**
     * Opens an export and restores it onto a brand-new identity (SRS-ONB-007).
     *
     * @param passphrase the user's passphrase, UTF-8; zeroed before return
     * @param sealed the recovery record bytes the user supplied
     * @return what was restored, and the proof the identity changed
     * @throws RecoveryException if the passphrase is wrong or the file is not a
     *     record this build can open - indistinguishably, per TRS-CRY-013
     * @throws GeneralSecurityException if generating the new identity fails
     * @throws StoreException on low storage or engine failure
     */
    public RestoreResult restore(final byte[] passphrase, final byte[] sealed)
            throws RecoveryException, GeneralSecurityException, StoreException {
        final byte[] plaintext;
        try {
            plaintext = record.open(passphrase, sealed);
        } finally {
            Arrays.fill(passphrase, (byte) 0);
        }

        final RecoveryBundle bundle;
        try {
            bundle = RecoveryBundleCodec.decode(plaintext);
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }

        final Optional<IdentityKeyRecord> before = identities.load();
        final IdentityKeyRecord fresh = IdentityKeyGenerator.generate(crypto, kek);
        identities.replace(fresh);

        int rows = 0;
        for (final RecoverySection section : RecoverySection.values()) {
            final RecordKind kind = section.kind();
            if (kind == null) {
                continue;
            }
            for (final RecoveryBundle.Item item : bundle.items(section)) {
                // The blind-index input for kinds that have one. It is
                // re-derived on THIS device from the row content rather than
                // carried in the file: a tag is a stable function of a peer
                // identifier, and a file full of them would let anyone holding
                // two exports tell whether the same person appears on both
                // block lists.
                final byte[] naturalKey = kind.isLookedUpByTag() ? item.content() : null;
                store.put(kind, item.id(), item.content(), naturalKey);
                rows++;
            }
        }
        return new RestoreResult(before.map(IdentityKeyRecord::ed25519Public).orElse(null),
                fresh.ed25519Public(), rows, bundle.unavailableSections());
    }
}
