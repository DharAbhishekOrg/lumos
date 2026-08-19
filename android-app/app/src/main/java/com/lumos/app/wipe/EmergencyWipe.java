/*
 * Lumos - android-app/app / Emergency wipe, end to end.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-SAF-009, SRS-SAF-010, SRS-NFR-PRI-012
 * ADRs: ADR-003, ADR-010
 * Threading: serial-executor
 * Notes: Slice S1 built KeysFirstWipe, the executor that runs registered stages
 * in the normative order. This is the thing that registers them - every store
 * that exists today, wired in one place so "what does wipe cover?" has a single
 * answer instead of being distributed across whoever remembered.
 *
 * Two properties this class owes SRS-SAF-009, and it owes them to a person in a
 * bad situation rather than to a reviewer:
 *
 * 1. It cannot be triggered by accident. execute() takes a Confirmation, which
 *    cannot be constructed - only obtained by satisfying a challenge issued
 *    alongside the consequences screen. There is no overload without one.
 * 2. It does not stop. A stage that fails is recorded and the wipe continues;
 *    the key phase runs to completion before any content stage begins, so an
 *    interruption at any point after the key phase leaves ciphertext with no
 *    key. That is KeysFirstWipe's guarantee and this class is careful not to
 *    weaken it - notably, nothing here reads the store between the phases.
 *
 * SRS-SAF-010 names profile, conversations, cached objects, queued packets,
 * venue tokens and app cryptographic state. Four of those six have no store in
 * WP-02. WipeCoverage says so, out loud, in the type - because "to the
 * documented platform limit" is only honest if the limit is documented, and a
 * report that listed six covered stages when two exist would be the exact kind
 * of unearned claim AGENTS.md Sec. 3 forbids.
 */

package com.lumos.app.wipe;

import com.lumos.app.keystore.KekStore;
import com.lumos.app.safety.DeliberateConfirmation;
import com.lumos.app.store.LocalStore;

/** Wires every store that exists into a keys-first wipe, behind a confirmation. */
public final class EmergencyWipe {

    /** Report of one executed wipe, with the coverage it actually had. */
    public static final class Outcome {

        private final KeysFirstWipe.WipeReport report;
        private final WipeCoverage coverage;

        private Outcome(final KeysFirstWipe.WipeReport report, final WipeCoverage coverage) {
            this.report = report;
            this.coverage = coverage;
        }

        /**
         * What each stage did, in execution order.
         *
         * @return the executor's report
         */
        public KeysFirstWipe.WipeReport report() {
            return report;
        }

        /**
         * What this build could and could not reach.
         *
         * @return the coverage statement the UI must show
         */
        public WipeCoverage coverage() {
            return coverage;
        }

        /**
         * Whether every key-material stage completed.
         *
         * <p>The property that matters. When true, everything the keys
         * protected is unreadable whatever the content phase managed.
         *
         * @return true when the crypto-erasure guarantee holds
         */
        public boolean cryptoErasureHolds() {
            return report.allKeyMaterialDestroyed();
        }
    }

    /** The stage name for destroying the key-encryption key. */
    public static final String STAGE_KEK = "key-encryption key";

    /** The stage name for destroying the local store's derived keys. */
    public static final String STAGE_STORE_KEYS = "local store derived keys";

    /** The stage name for clearing every record table. */
    public static final String STAGE_LOCAL_RECORDS = "local records";

    private final KekStore kek;
    private final LocalStore store;

    /**
     * Assembles the wipe from the stores it must clear.
     *
     * @param kek the key-encryption key, destroyed first
     * @param store the encrypted record store
     */
    public EmergencyWipe(final KekStore kek, final LocalStore store) {
        this.kek = kek;
        this.store = store;
    }

    /**
     * The consequences a user must be shown before confirming (SRS-SAF-009).
     *
     * <p>Exposed before execution so the warning screen describes what THIS
     * device will actually lose, rather than a generic sentence. It is also the
     * text the trace matrix points at for "describes irreversible consequences".
     *
     * @return the coverage statement
     */
    public WipeCoverage coverage() {
        return WipeCoverage.forWpTwo();
    }

    /**
     * Runs the wipe (SRS-SAF-010).
     *
     * <p>Registration order does not matter - KeysFirstWipe runs every
     * key-material stage before any content stage regardless - but it is
     * written in the order it executes anyway, because a reader should not have
     * to know that to follow it.
     *
     * @param confirmation proof the warning was shown and the phrase typed
     * @return the report and the coverage
     * @throws IllegalArgumentException if the confirmation is for another action
     */
    public Outcome execute(final DeliberateConfirmation.Confirmation confirmation) {
        if (confirmation == null
                || confirmation.action() != DeliberateConfirmation.Action.EMERGENCY_WIPE) {
            throw new IllegalArgumentException(
                    "emergency wipe requires a confirmation issued for emergency wipe");
        }
        final KeysFirstWipe wipe = new KeysFirstWipe()
                .registerKeyMaterial(STAGE_KEK, kek::destroy)
                .registerKeyMaterial(STAGE_STORE_KEYS, store::destroyKeys)
                .registerContent(STAGE_LOCAL_RECORDS, store::deleteEverything);
        return new Outcome(wipe.execute(), coverage());
    }
}
