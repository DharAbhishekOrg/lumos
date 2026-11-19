/*
 * Lumos - android-app/app / The ADR-010 policy table, pinned.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-SAF-009, SRS-ONB-007, SRS-IDN-003
 * ADRs: ADR-010
 * Threading: any
 * Notes: Two pins. Every purpose in the enum requires the prompt - walked
 * over values(), so a purpose added later is covered the moment it exists.
 * And the purpose LIST itself is pinned to the four moments ADR-010 names,
 * so adding a fifth is a deliberate act that changes this test, not a quiet
 * enum edit; the ruling lives in review, not in memory.
 */

package com.lumos.app.safety;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** ADR-010's table: what must present the device-auth prompt. */
final class DeviceAuthPolicyTest {

    @Test
    void everyPurposeRequiresThePrompt() {
        for (final DeviceAuth.Purpose purpose : DeviceAuth.Purpose.values()) {
            assertTrue(DeviceAuth.requiresDeviceAuth(purpose),
                    purpose + " must present the device-auth prompt (ADR-010)");
        }
    }

    @Test
    void thePurposeListIsTheFourMomentsTheOwnerRulingNames() {
        final List<String> purposes = Arrays.stream(DeviceAuth.Purpose.values())
                .map(Enum::name).sorted().collect(Collectors.toList());
        assertEquals(
                List.of("APP_UNLOCK", "EMERGENCY_WIPE", "IDENTITY_RESET", "RECOVERY_EXPORT"),
                purposes,
                "a new auth-gated moment is an ADR-010 change: add it here deliberately");
    }
}
