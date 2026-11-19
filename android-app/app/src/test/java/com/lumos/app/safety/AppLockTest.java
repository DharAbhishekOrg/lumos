/*
 * Lumos - android-app/app / App Lock: off until chosen, shut until proven.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-NFR-SEC-003, SRS-NFR-SEC-013, SRS-DAT-002
 * ADRs: ADR-010
 * Threading: any
 * Notes: The store is the real one (Fixtures), the prompt is scripted - the
 * DeviceAuth seam exists exactly so this machine runs in a JVM. What gets
 * pinned: absence means not enabled (the schema-v3 record is added, never
 * presumed); backgrounding locks only an enabled app; NOT_CONFIRMED and
 * UNAVAILABLE both leave the lock shut - a device without a credential gets
 * the honest UNAVAILABLE, and an attacker does not get a free unlock out of
 * it; disabling the protection demands the same proof as passing it; the
 * choice survives a new process over the same store; and the stored payload
 * carries the choice alone - a byte-level scan for anything secret-shaped,
 * because the record that "could never become a credential store" is one
 * convenient field away from becoming one.
 */

package com.lumos.app.safety;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lumos.app.Fixtures;
import com.lumos.app.store.RecordKind;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** The ADR-010 application lock. */
final class AppLockTest {

    private static final long NOW = 1_755_561_600_000L;

    /** A prompt that answers from a script and counts how often it is asked. */
    private static final class ScriptedPrompt implements DeviceAuth {
        private DeviceAuth.Result answer = Result.NOT_CONFIRMED;
        private int asked;

        @Override
        public Result confirm(final Purpose purpose) {
            assertEquals(Purpose.APP_UNLOCK, purpose,
                    "the app lock asks for APP_UNLOCK and nothing grander");
            asked++;
            return answer;
        }
    }

    @Test
    void absentRecordMeansNotEnabledAndBackgroundingChangesNothing() throws Exception {
        try (Fixtures.Stack stack = Fixtures.openInMemory()) {
            final AppLock lock = new AppLock(stack.store(), new ScriptedPrompt());
            assertFalse(lock.isEnabled(), "a protection the user has not added is off");
            lock.onAppBackgrounded();
            assertFalse(lock.requiresUnlock(),
                    "with the lock off, leaving the app locks nothing");
        }
    }

    @Test
    void enabledLockShutsOnBackgroundAndOpensOnlyOnConfirmed() throws Exception {
        try (Fixtures.Stack stack = Fixtures.openInMemory()) {
            final ScriptedPrompt prompt = new ScriptedPrompt();
            final AppLock lock = new AppLock(stack.store(), prompt);
            lock.enableAfterExplicitUserRequest(NOW);
            assertTrue(lock.isEnabled());
            assertFalse(lock.requiresUnlock(), "enabling does not lock the open app");

            lock.onAppBackgrounded();
            assertTrue(lock.requiresUnlock(), "leaving the app is what locks it");

            prompt.answer = DeviceAuth.Result.NOT_CONFIRMED;
            assertEquals(DeviceAuth.Result.NOT_CONFIRMED, lock.unlock());
            assertTrue(lock.requiresUnlock(), "a failed prompt leaves the lock shut");

            prompt.answer = DeviceAuth.Result.UNAVAILABLE;
            assertEquals(DeviceAuth.Result.UNAVAILABLE, lock.unlock());
            assertTrue(lock.requiresUnlock(),
                    "no device credential is reported, never treated as a pass");

            prompt.answer = DeviceAuth.Result.CONFIRMED;
            assertEquals(DeviceAuth.Result.CONFIRMED, lock.unlock());
            assertFalse(lock.requiresUnlock(), "only CONFIRMED opens the lock");
        }
    }

    @Test
    void disablingTheProtectionDemandsTheSameProofAsPassingIt() throws Exception {
        try (Fixtures.Stack stack = Fixtures.openInMemory()) {
            final ScriptedPrompt prompt = new ScriptedPrompt();
            final AppLock lock = new AppLock(stack.store(), prompt);
            lock.enableAfterExplicitUserRequest(NOW);

            prompt.answer = DeviceAuth.Result.NOT_CONFIRMED;
            assertFalse(lock.disableAfterDeviceAuth(NOW + 1));
            assertTrue(lock.isEnabled(),
                    "whoever cannot pass the lock cannot remove it either");

            prompt.answer = DeviceAuth.Result.UNAVAILABLE;
            assertFalse(lock.disableAfterDeviceAuth(NOW + 2));
            assertTrue(lock.isEnabled());

            prompt.answer = DeviceAuth.Result.CONFIRMED;
            assertTrue(lock.disableAfterDeviceAuth(NOW + 3));
            assertFalse(lock.isEnabled());
            lock.onAppBackgrounded();
            assertFalse(lock.requiresUnlock(), "disabled means backgrounding locks nothing");
        }
    }

    @Test
    void theChoiceSurvivesANewProcessOverTheSameStore() throws Exception {
        try (Fixtures.Stack stack = Fixtures.openInMemory()) {
            new AppLock(stack.store(), new ScriptedPrompt()).enableAfterExplicitUserRequest(NOW);

            // A fresh AppLock over the same store is what a process restart is
            // to this class: no carried memory, only the record.
            final AppLock fresh = new AppLock(stack.store(), new ScriptedPrompt());
            assertTrue(fresh.isEnabled(), "the choice is in the record, not the object");
            fresh.onAppBackgrounded();
            assertTrue(fresh.requiresUnlock());
        }
    }

    @Test
    void theStoredRecordCarriesTheChoiceAndNothingThatCouldUnlockIt() throws Exception {
        try (Fixtures.Stack stack = Fixtures.openInMemory()) {
            final AppLock lock = new AppLock(stack.store(), new ScriptedPrompt());
            lock.enableAfterExplicitUserRequest(NOW);

            final byte[] raw = stack.store()
                    .get(RecordKind.APP_LOCK, AppLock.APP_LOCK_ID).orElseThrow();
            final String payload = new String(raw, StandardCharsets.UTF_8);
            assertEquals("lumos-app-lock/1\non\n" + NOW + "\n", payload,
                    "the whole record: a version line, on|off, and when - no PIN, no "
                            + "hash, no secret; unlocking belongs to the platform prompt");
        }
    }

    @Test
    void anUnknownPayloadVersionReadsAsNotEnabled() throws Exception {
        try (Fixtures.Stack stack = Fixtures.openInMemory()) {
            stack.store().put(RecordKind.APP_LOCK, AppLock.APP_LOCK_ID,
                    "lumos-app-lock/999\non\n0\n".getBytes(StandardCharsets.UTF_8), null);
            assertFalse(new AppLock(stack.store(), new ScriptedPrompt()).isEnabled(),
                    "a record this build cannot read is not a record saying 'on'");
        }
    }
}
