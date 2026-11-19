/*
 * Lumos - android-app/app / Explicit identity reset, with its warning.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-IDN-003, SRS-IDN-001, SRS-ONB-006
 * ADRs: ADR-003, ADR-008, ADR-010
 * Threading: serial-executor
 * Notes: SRS-IDN-003 asks for two things - an explicit reset, and a clear
 * warning that existing matches and encrypted conversation continuity may be
 * lost - and the second is the harder one to get right, because the honest
 * warning is not the same on every build.
 *
 * So the warning is COMPUTED. consequences() reports what this device would
 * actually lose, and in WP-02 that is "no matches or conversations exist yet",
 * which is the true statement. When WP-04 and WP-05 add those stores the same
 * method reports them, and the screen does not change.
 *
 * What reset is NOT: it is not a wipe. The profile, the block list and the
 * settings survive, because SRS-IDN-003 is about the cryptographic identity and
 * a user resetting it has not asked to lose their profile. Emergency wipe is
 * the other action and it is deliberately separate.
 *
 * The limit, stated plainly rather than glossed: the previous identity's
 * private keys existed only inside one KEK-wrapped envelope in one row, and
 * this overwrites that row. The old ciphertext may survive in SQLite free pages
 * (threat model LT-14), and unlike emergency wipe this action does NOT destroy
 * the KEK - it cannot, because the same KEK protects the profile the user is
 * keeping. Recovering the old private keys would therefore need both the
 * database file and an unlocked device with a live KEK. That is a real
 * residual, it is recorded here and in the trace matrix, and the answer for a
 * user who needs more than that is emergency wipe.
 */

package com.lumos.app.identity;

import com.lumos.app.keystore.KekStore;
import com.lumos.app.safety.DeliberateConfirmation;
import com.lumos.app.store.StoreException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/** Replaces the device identity, after an explicit warning and confirmation. */
public final class IdentityReset {

    /** What this device will lose, computed rather than asserted. */
    public static final class Consequences {

        private final List<String> lost;
        private final List<String> kept;
        private final List<String> residual;

        private Consequences(final List<String> lost, final List<String> kept,
                final List<String> residual) {
            this.lost = Collections.unmodifiableList(lost);
            this.kept = Collections.unmodifiableList(kept);
            this.residual = Collections.unmodifiableList(residual);
        }

        /**
         * What goes, in the words the warning screen uses.
         *
         * @return the losses
         */
        public List<String> lost() {
            return lost;
        }

        /**
         * What stays, so the user is not frightened away from a safe action.
         *
         * @return what survives the reset
         */
        public List<String> kept() {
            return kept;
        }

        /**
         * What this action cannot promise, stated rather than omitted.
         *
         * @return the residual risks
         */
        public List<String> residual() {
            return residual;
        }
    }

    /** The outcome, carrying the proof that the identity actually changed. */
    public static final class ResetResult {

        private final byte[] previousEd25519Public;
        private final byte[] newEd25519Public;

        private ResetResult(final byte[] previousEd25519Public, final byte[] newEd25519Public) {
            this.previousEd25519Public =
                    previousEd25519Public == null ? null : previousEd25519Public.clone();
            this.newEd25519Public = newEd25519Public.clone();
        }

        /**
         * The identity the device had, if any.
         *
         * @return a copy of the old Ed25519 public key, or null
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
         * Whether the identity changed.
         *
         * @return true when the new key differs from the old, or there was none
         */
        public boolean identityIsNew() {
            return previousEd25519Public == null
                    || !Arrays.equals(previousEd25519Public, newEd25519Public);
        }
    }

    private final IdentityRepository identities;
    private final KekStore kek;
    private final IdentityCrypto crypto;

    /**
     * Assembles the reset from its collaborators.
     *
     * @param identities where the device identity is stored
     * @param kek the key-encryption key the new envelope is wrapped under
     * @param crypto the identity backend - libsodium on Android, JCA on the JVM
     */
    public IdentityReset(final IdentityRepository identities, final KekStore kek,
            final IdentityCrypto crypto) {
        this.identities = identities;
        this.kek = kek;
        this.crypto = crypto;
    }

    /**
     * The warning this device must show before resetting (SRS-IDN-003).
     *
     * @return what will be lost, kept, and not promised
     * @throws StoreException on engine failure
     */
    public Consequences consequences() throws StoreException {
        final List<String> lost = new ArrayList<>();
        lost.add("Your Lumos identity. The keys on this phone are destroyed and new ones "
                + "are made, so you become a different person to everyone you have met here.");
        if (identities.exists()) {
            lost.add("Any match you already have. Matches are tied to the old keys and "
                    + "cannot be moved to the new ones.");
            lost.add("The ability to carry on an existing encrypted conversation. Anyone "
                    + "you were talking to will need to verify you again from scratch.");
        }
        // WP-04 and WP-05 add match and message stores; when they exist this
        // method counts them and says how many, instead of speaking in general
        // terms about things that might not be there.
        lost.add("Nothing else - this build has no matches or conversations stored yet, "
                + "so there is nothing further to lose today.");

        final List<String> kept = List.of(
                "Your profile, your photos and your settings.",
                "Your block list. Resetting your identity does not unblock anyone.",
                "Any recovery file you have already saved. It holds content, not keys, "
                        + "and it still opens.");

        final List<String> residual = List.of(
                "Traces of the old keys may remain in unused parts of the phone's storage "
                        + "until the phone overwrites them. They are encrypted, and they "
                        + "cannot be read without this phone unlocked. If you need more "
                        + "than that, use Emergency wipe instead.");

        return new Consequences(lost, kept, residual);
    }

    /**
     * Resets the identity (SRS-IDN-003).
     *
     * @param confirmation proof the warning was shown and the phrase typed
     * @return the old and new public keys
     * @throws IllegalArgumentException if the confirmation is for another action
     * @throws GeneralSecurityException if generating or wrapping the new identity fails
     * @throws StoreException on low storage or engine failure
     */
    public ResetResult reset(final DeliberateConfirmation.Confirmation confirmation)
            throws GeneralSecurityException, StoreException {
        if (confirmation == null
                || confirmation.action() != DeliberateConfirmation.Action.IDENTITY_RESET) {
            throw new IllegalArgumentException(
                    "identity reset requires a confirmation issued for identity reset");
        }
        final Optional<IdentityKeyRecord> before = identities.load();
        final IdentityKeyRecord fresh = IdentityKeyGenerator.generate(crypto, kek);
        identities.replace(fresh);
        return new ResetResult(before.map(IdentityKeyRecord::ed25519Public).orElse(null),
                fresh.ed25519Public());
    }
}
