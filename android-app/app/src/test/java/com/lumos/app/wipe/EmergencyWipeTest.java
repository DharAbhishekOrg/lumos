/*
 * Lumos - android-app/app / Emergency wipe over every store that exists.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-SAF-009, SRS-SAF-010, SRS-NFR-PRI-012
 * ADRs: ADR-003, ADR-010
 * Threading: any
 * Notes: Slice S1 proved the ORDER with synthetic stages and slice S2 proved it
 * over the store. What is new here is coverage and confirmation: every record
 * kind that exists is written before the wipe and asserted gone after it, so a
 * record kind added in a later slice without being wired into EmergencyWipe
 * fails this test rather than surviving a wipe.
 *
 * The interruption property is re-pinned rather than assumed. It was proved in
 * S2 against two stores; the store set has grown by two kinds since, and the
 * claim is about the set, so it is re-run against the set as it is now.
 */

package com.lumos.app.wipe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lumos.app.Fixtures;
import com.lumos.app.safety.DeliberateConfirmation;
import com.lumos.app.store.RecordKind;
import com.lumos.app.store.StoreException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The wipe a person in trouble actually runs. */
final class EmergencyWipeTest {

    private static final String PHRASE = "erase everything";
    private static final long NOW = 1_755_561_600_000L;

    private static DeliberateConfirmation.Confirmation wipeConfirmation() throws Exception {
        return DeliberateConfirmation
                .issue(DeliberateConfirmation.Action.EMERGENCY_WIPE, PHRASE, NOW)
                .confirm(PHRASE, NOW + 1);
    }

    /**
     * Fills every record table so the wipe has something to fail to remove.
     *
     * @param stack the standing store
     * @throws StoreException on engine failure
     */
    private static void writeOneOfEverything(final Fixtures.Stack stack) throws StoreException {
        for (final RecordKind kind : RecordKind.values()) {
            final byte[] payload = com.lumos.app.store.LocalStore.payloadOf("private-" + kind);
            stack.store().put(kind, kind.name().toLowerCase(java.util.Locale.ROOT),
                    payload, kind.isLookedUpByTag() ? payload : null);
        }
    }

    @Test
    void everyRecordKindThatExistsIsClearedByTheWipe() throws Exception {
        try (Fixtures.Stack stack = Fixtures.openInMemory()) {
            writeOneOfEverything(stack);
            for (final RecordKind kind : RecordKind.values()) {
                assertEquals(1, stack.store().count(kind), kind + " was not written");
            }

            final EmergencyWipe.Outcome outcome =
                    new EmergencyWipe(stack.kek(), stack.store()).execute(wipeConfirmation());

            assertTrue(outcome.cryptoErasureHolds());
            assertTrue(outcome.report().fullyClean());
            for (final RecordKind kind : RecordKind.values()) {
                assertEquals(0, stack.store().count(kind),
                        kind + " survived the wipe; a record kind added to the store must be "
                                + "cleared by it, and this table is how that is noticed");
            }
        }
    }

    @Test
    void theKeysGoBeforeTheContent() throws Exception {
        try (Fixtures.Stack stack = Fixtures.openInMemory()) {
            writeOneOfEverything(stack);
            final EmergencyWipe.Outcome outcome =
                    new EmergencyWipe(stack.kek(), stack.store()).execute(wipeConfirmation());

            final List<String> order = new ArrayList<>();
            final List<KeysFirstWipe.Phase> phases = new ArrayList<>();
            for (final KeysFirstWipe.StageResult result : outcome.report().results()) {
                order.add(result.name());
                phases.add(result.phase());
            }
            assertEquals(List.of(EmergencyWipe.STAGE_KEK, EmergencyWipe.STAGE_STORE_KEYS,
                    EmergencyWipe.STAGE_LOCAL_RECORDS), order);
            assertEquals(List.of(KeysFirstWipe.Phase.KEY_MATERIAL,
                    KeysFirstWipe.Phase.KEY_MATERIAL, KeysFirstWipe.Phase.CONTENT), phases);
        }
    }

    @Test
    void theKekIsGoneAndCannotUnwrapAnythingAfterwards() throws Exception {
        try (Fixtures.Stack stack = Fixtures.openInMemory()) {
            writeOneOfEverything(stack);
            new EmergencyWipe(stack.kek(), stack.store()).execute(wipeConfirmation());

            assertTrue(stack.kek().isDestroyed());
            assertThrows(GeneralSecurityException.class,
                    () -> stack.hierarchy().deriveKey(
                            com.lumos.app.data.DataKeyHierarchy.DataDomain.DATABASE),
                    "with the KEK gone, no store key can be derived again - which is what "
                            + "makes anything left on the disk unreadable");
        }
    }

    @Test
    void anInterruptionAfterTheKeyPhaseStillLeavesCryptoErasure() throws Exception {
        try (Fixtures.Stack stack = Fixtures.openInMemory()) {
            writeOneOfEverything(stack);

            // The content stage fails - the process is killed, the disk is full,
            // the phone is taken. The keys are already gone by then, and that is
            // the guarantee: what remains is ciphertext with no key.
            final KeysFirstWipe.WipeReport report = new KeysFirstWipe()
                    .registerKeyMaterial(EmergencyWipe.STAGE_KEK, stack.kek()::destroy)
                    .registerKeyMaterial(EmergencyWipe.STAGE_STORE_KEYS,
                            stack.store()::destroyKeys)
                    .registerContent(EmergencyWipe.STAGE_LOCAL_RECORDS, () -> {
                        throw new StoreException(StoreException.Reason.ENGINE, "interrupted");
                    })
                    .execute();

            assertTrue(report.allKeyMaterialDestroyed(),
                    "the key phase completed before the content phase was reached");
            assertFalse(report.fullyClean(), "and the report is honest that rows remain");
            assertThrows(StoreException.class,
                    () -> stack.store().get(RecordKind.LOCAL_PROFILE, "local_profile"),
                    "the surviving rows cannot be opened by anyone, including us");
        }
    }

    @Test
    void aWipeWithoutAConfirmationIsRefused() throws Exception {
        try (Fixtures.Stack stack = Fixtures.openInMemory()) {
            final EmergencyWipe wipe = new EmergencyWipe(stack.kek(), stack.store());
            assertThrows(IllegalArgumentException.class, () -> wipe.execute(null));
            assertEquals(0, stack.store().count(RecordKind.LOCAL_PROFILE));
            assertFalse(stack.kek().isDestroyed(),
                    "a refused wipe must not have destroyed anything on its way to refusing");
        }
    }

    @Test
    void aResetConfirmationCannotBeSpentOnAWipe() throws Exception {
        try (Fixtures.Stack stack = Fixtures.openInMemory()) {
            final DeliberateConfirmation.Confirmation forReset = DeliberateConfirmation
                    .issue(DeliberateConfirmation.Action.IDENTITY_RESET, "new identity", NOW)
                    .confirm("new identity", NOW + 1);
            final EmergencyWipe wipe = new EmergencyWipe(stack.kek(), stack.store());
            assertThrows(IllegalArgumentException.class, () -> wipe.execute(forReset));
            assertFalse(stack.kek().isDestroyed());
        }
    }

    @Test
    void theCoverageStatementNamesEverySrsSaf010ItemHonestly() {
        final WipeCoverage coverage = WipeCoverage.forWpTwo();
        final String everything = (render(coverage.covered()) + render(coverage.notYetBuilt())
                + render(coverage.beyondReach())).toLowerCase(java.util.Locale.ROOT);

        // SRS-SAF-010 names six things. Every one must appear somewhere, in the
        // list that tells the truth about it.
        for (final String item : List.of("profile", "conversations", "cached objects",
                "queued packets", "venue tokens", "cryptographic state")) {
            assertTrue(everything.contains(item), "SRS-SAF-010 names " + item
                    + " and the coverage statement does not mention it");
        }
        assertFalse(coverage.notYetBuilt().isEmpty(),
                "four of the six have no store in WP-02 and the statement must say so");
        assertFalse(coverage.beyondReach().isEmpty(),
                "the platform limit is part of 'to the documented platform limit'");
        for (final WipeCoverage.Item item : coverage.notYetBuilt()) {
            assertTrue(item.status().contains("WP-"),
                    "a pending item must name the work package that closes it: " + item.what());
        }
    }

    /**
     * Flattens a coverage list for a substring assertion.
     *
     * @param items the list
     * @return every phrase in it, joined
     */
    private static String render(final List<WipeCoverage.Item> items) {
        final StringBuilder out = new StringBuilder();
        for (final WipeCoverage.Item item : items) {
            out.append(item.what()).append(' ').append(item.status()).append(' ');
        }
        return out.toString();
    }
}
