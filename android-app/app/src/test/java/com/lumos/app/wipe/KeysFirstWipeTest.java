/*
 * Lumos - android-app/app / The wipe ORDER is the property under test.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-SAF-010, SRS-NFR-PRI-012
 * ADRs: ADR-003, ADR-010
 * Threading: any
 * Notes: The owner confirmed keys-first as normative on crypto-erasure
 * rationale (2026-08-14). These tests assert the ORDER and the interruption
 * property, not merely the end state - a wipe that happens to finish clean in
 * the happy path can still be one that leaves plaintext recoverable when
 * interrupted, and that difference is invisible to an end-state assertion.
 */

package com.lumos.app.wipe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lumos.app.data.DataKeyHierarchy;
import com.lumos.app.keystore.SoftwareKekStore;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Order and interruption behaviour of the keys-first wipe. */
final class KeysFirstWipeTest {

    @Test
    void keyStagesRunBeforeContentRegardlessOfRegistrationOrder() {
        final List<String> order = new ArrayList<>();
        final KeysFirstWipe.WipeReport report = new KeysFirstWipe()
                .registerContent("profile", () -> order.add("profile"))
                .registerKeyMaterial("kek", () -> order.add("kek"))
                .registerContent("media", () -> order.add("media"))
                .registerKeyMaterial("envelopes", () -> order.add("envelopes"))
                .execute();

        assertEquals(List.of("kek", "envelopes", "profile", "media"), order,
                "every key-material stage must run before any content stage");
        assertTrue(report.fullyClean());
        assertTrue(report.allKeyMaterialDestroyed());
    }

    @Test
    void contentFailureAfterKeyPhaseStillLeavesCryptoErasure() throws GeneralSecurityException {
        final SoftwareKekStore kek = SoftwareKekStore.create();
        final DataKeyHierarchy hierarchy = DataKeyHierarchy.create(kek);

        final KeysFirstWipe.WipeReport report = new KeysFirstWipe()
                .registerKeyMaterial("kek", kek::destroy)
                .registerContent("profile", () -> {
                    throw new GeneralSecurityException("store unavailable mid-wipe");
                })
                .execute();

        assertFalse(report.fullyClean(), "the content failure must be reported, not swallowed");
        assertTrue(report.allKeyMaterialDestroyed(),
                "the guarantee: key material was destroyed before content was attempted");
        assertTrue(kek.isDestroyed());
        assertThrows(GeneralSecurityException.class,
                () -> hierarchy.deriveKey(DataKeyHierarchy.DataDomain.DATABASE),
                "whatever the interrupted content phase left behind is ciphertext without a key");
    }

    @Test
    void aFailingKeyStageDoesNotStopTheOtherKeyStages() {
        final List<String> order = new ArrayList<>();
        final KeysFirstWipe.WipeReport report = new KeysFirstWipe()
                .registerKeyMaterial("first", () -> {
                    throw new GeneralSecurityException("refused");
                })
                .registerKeyMaterial("second", () -> order.add("second"))
                .registerContent("content", () -> order.add("content"))
                .execute();

        assertEquals(List.of("second", "content"), order,
                "as much key material as possible is destroyed even when one stage fails");
        assertFalse(report.allKeyMaterialDestroyed(),
                "a failed key stage must be visible: the crypto-erasure guarantee does NOT hold");
        assertEquals("refused", report.results().get(0).failure());
    }

    @Test
    void reportRecordsPhaseNameAndOutcomePerStage() {
        final KeysFirstWipe.WipeReport report = new KeysFirstWipe()
                .registerKeyMaterial("kek", () -> {
                })
                .registerContent("cache", () -> {
                })
                .execute();
        assertEquals(2, report.results().size());
        assertEquals(KeysFirstWipe.Phase.KEY_MATERIAL, report.results().get(0).phase());
        assertEquals("kek", report.results().get(0).name());
        assertTrue(report.results().get(0).succeeded());
        assertEquals(KeysFirstWipe.Phase.CONTENT, report.results().get(1).phase());
    }

    @Test
    void aWipeExecutesExactlyOnce() {
        final KeysFirstWipe wipe = new KeysFirstWipe();
        wipe.registerKeyMaterial("kek", () -> {
        });
        wipe.execute();
        assertThrows(IllegalStateException.class, wipe::execute,
                "re-running a wipe would report destruction that already happened as new");
        assertThrows(IllegalStateException.class,
                () -> wipe.registerContent("late", () -> {
                }),
                "a stage registered after execution would silently never run");
    }
}
