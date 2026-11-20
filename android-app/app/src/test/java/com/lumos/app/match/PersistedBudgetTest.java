/*
 * Lumos - android-app/app / The persisted budget against the Go semantics.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-MAT-011, SRS-SAF-006, TRS-TST-001
 * ADRs: ADR-002, ADR-003
 * Threading: test-only
 * Notes: The Go budget suite's assertions over the sealed row: the rolling
 * hour, the exact moment a slot frees, and the cooldown holding both its
 * floor and its truth. Every number comes from the register.
 */

package com.lumos.app.match;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lumos.app.Fixtures;
import com.lumos.protocol.LumosConstants;
import org.junit.jupiter.api.Test;

/** PersistedBudget semantics over the real store. */
final class PersistedBudgetTest {

    private static final long WINDOW = 3_600_000L;
    private static final long START = 1_755_561_600_000L;

    @Test
    void theBudgetIsARollingHourOverTheSealedRow() throws Exception {
        try (Fixtures.Stack stack = Fixtures.openInMemory()) {
            final PersistedBudget budget = new PersistedBudget(stack.store());
            final int limit = LumosConstants.INTEREST_RATE_LIMIT_PER_HOUR_DEFAULT;
            for (int i = 0; i < limit; i++) {
                assertTrue(budget.allowed(START + i));
                budget.record(START + i);
            }
            assertFalse(budget.allowed(START + limit), "budget-plus-one is refused");
            assertFalse(budget.allowed(START + WINDOW - 1), "the window had not rolled");
            assertTrue(budget.allowed(START + WINDOW), "a slot frees as the window rolls");
        }
    }

    @Test
    void theCooldownHoldsItsFloorAndItsTruth() throws Exception {
        try (Fixtures.Stack stack = Fixtures.openInMemory()) {
            final PersistedBudget budget = new PersistedBudget(stack.store());
            final int limit = LumosConstants.INTEREST_RATE_LIMIT_PER_HOUR_DEFAULT;
            for (int i = 0; i < limit; i++) {
                budget.record(START + i);
            }
            final long lastSend = START + limit - 1;
            final long floorMillis = LumosConstants.INTEREST_COOLDOWN_SEC_DEFAULT * 1000L;

            assertTrue(budget.cooldownRemainingMillis(START + limit) > 0,
                    "a spent budget holds a cooldown");
            final long afterFloor = lastSend + floorMillis + 1;
            if (afterFloor < START + WINDOW) {
                assertTrue(budget.cooldownRemainingMillis(afterFloor) > 0,
                        "the cooldown cannot clear while no slot is free");
            }
            assertEquals(0, budget.cooldownRemainingMillis(START + WINDOW),
                    "an allowed budget holds no cooldown");
        }
    }
}
