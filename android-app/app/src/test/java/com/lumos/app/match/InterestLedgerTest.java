/*
 * Lumos - android-app/app / The persisted interest machine, end to end.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-MAT-001, SRS-MAT-003, SRS-MAT-005, SRS-MAT-007, SRS-MAT-010,
 *             SRS-MAT-011, SRS-MAT-014, TRS-TST-001
 * ADRs: ADR-002, ADR-003, ADR-008
 * Threading: test-only
 * Notes: Real store, real identifiers, scripted clock. The pins that matter:
 * state survives a fresh ledger over the same store (a process restart);
 * one peer keeps ONE interest row however often the state changes (the
 * upsert the blind index needed); both sides of the race derive the same
 * match identifier from their own ledgers; a rate-limited request never
 * reaches the machine, so no state pretends a request went out and the
 * budget survives a restart too; and unmatching flips the match record
 * without deleting it (SRS-MAT-010's local half).
 */

package com.lumos.app.match;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lumos.app.Fixtures;
import com.lumos.app.store.LocalStore;
import com.lumos.app.store.RecordKind;
import com.lumos.protocol.LumosConstants;
import com.lumos.protocol.match.MatchMachine;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** InterestLedger over the real sealed store. */
final class InterestLedgerTest {

    private static final long NOW = 1_755_561_600_000L;

    private static byte[] peerId(final int seed) {
        final byte[] id = new byte[LumosConstants.PEER_ID_LEN_DEFAULT];
        Arrays.fill(id, (byte) seed);
        return id;
    }

    private static String hex(final byte[] bytes) {
        final StringBuilder sb = new StringBuilder();
        for (final byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static InterestLedger ledger(final LocalStore store, final int ownSeed) {
        return new InterestLedger(store, new PersistedBudget(store), peerId(ownSeed));
    }

    @Test
    void stateSurvivesARestartAndOnePeerKeepsOneRow() throws Exception {
        try (Fixtures.Stack stack = Fixtures.openInMemory()) {
            final String peer = hex(peerId(0x22));
            final InterestLedger first = ledger(stack.store(), 0x11);

            assertEquals(MatchMachine.State.NONE, first.stateOf(peer), "absence is NONE");
            first.apply(peer, MatchMachine.Event.REMOTE_REQUEST, NOW);
            first.apply(peer, MatchMachine.Event.LOCAL_DECLINE, NOW + 1);
            first.apply(peer, MatchMachine.Event.REMOTE_REQUEST, NOW + 2);

            // A fresh ledger over the same store is the restart.
            final InterestLedger second = ledger(stack.store(), 0x11);
            assertEquals(MatchMachine.State.REQUESTED_IN, second.stateOf(peer),
                    "the machine state is in the record, not the object");
            assertEquals(1, stack.store().count(RecordKind.INTEREST_RECORD),
                    "three state changes, one row: the upsert holds");
        }
    }

    @Test
    void bothSidesOfTheRaceDeriveTheSameMatchIdentifier() throws Exception {
        try (Fixtures.Stack a = Fixtures.openInMemory();
                Fixtures.Stack b = Fixtures.openInMemory()) {
            final InterestLedger alice = ledger(a.store(), 0x11);
            final InterestLedger bob = ledger(b.store(), 0x22);
            final String bobKey = hex(peerId(0x22));
            final String aliceKey = hex(peerId(0x11));

            alice.apply(bobKey, MatchMachine.Event.LOCAL_REQUEST, NOW);
            bob.apply(aliceKey, MatchMachine.Event.LOCAL_REQUEST, NOW);
            alice.apply(bobKey, MatchMachine.Event.REMOTE_REQUEST, NOW + 1);
            bob.apply(aliceKey, MatchMachine.Event.REMOTE_REQUEST, NOW + 1);

            final InterestLedger.Outcome fromAlice =
                    alice.apply(bobKey, MatchMachine.Event.LOCAL_ACCEPT, NOW + 2);
            final InterestLedger.Outcome fromBob =
                    bob.apply(aliceKey, MatchMachine.Event.REMOTE_ACCEPT, NOW + 2);

            assertEquals(MatchMachine.State.MATCHED, fromAlice.state());
            assertEquals(MatchMachine.State.MATCHED, fromBob.state());
            assertNotNull(fromAlice.matchIdHex());
            assertEquals(fromAlice.matchIdHex(), fromBob.matchIdHex(),
                    "one race, one identifier, derived independently (SRS-MAT-007)");
        }
    }

    @Test
    void aRateLimitedRequestNeverReachesTheMachineAndTheBudgetSurvivesRestart()
            throws Exception {
        try (Fixtures.Stack stack = Fixtures.openInMemory()) {
            final InterestLedger first = ledger(stack.store(), 0x11);
            final int limit = LumosConstants.INTEREST_RATE_LIMIT_PER_HOUR_DEFAULT;
            for (int i = 0; i < limit; i++) {
                final InterestLedger.Outcome outcome = first.apply(hex(peerId(100 + i)),
                        MatchMachine.Event.LOCAL_REQUEST, NOW + i);
                assertEquals(MatchMachine.Effect.SEND_REQUEST, outcome.effect(),
                        "send " + (i + 1) + " of " + limit + " is inside the budget");
            }

            final String oneMore = hex(peerId(0x33));
            final InterestLedger.Outcome refused =
                    first.apply(oneMore, MatchMachine.Event.LOCAL_REQUEST, NOW + limit);
            assertTrue(refused.rateLimited(), "the budget-plus-one request is refused");
            assertEquals(MatchMachine.Effect.NONE, refused.effect());
            assertEquals(MatchMachine.State.NONE, first.stateOf(oneMore),
                    "no state pretends a request was sent that was not");

            // The restart does not refill the budget (SRS-SAF-006).
            final InterestLedger second = ledger(stack.store(), 0x11);
            assertTrue(second.apply(oneMore, MatchMachine.Event.LOCAL_REQUEST,
                    NOW + limit + 1).rateLimited(),
                    "killing the process must not reset the flood limit");
        }
    }

    @Test
    void unmatchingFlipsTheMatchRecordWithoutDeletingIt() throws Exception {
        try (Fixtures.Stack stack = Fixtures.openInMemory()) {
            final InterestLedger ledger = ledger(stack.store(), 0x11);
            final String peer = hex(peerId(0x44));

            ledger.apply(peer, MatchMachine.Event.REMOTE_REQUEST, NOW);
            ledger.apply(peer, MatchMachine.Event.LOCAL_ACCEPT, NOW + 1);
            final String matched = ledger.matchRecordOf(peer);
            assertNotNull(matched);
            assertTrue(matched.contains("\nmatched\n"));

            ledger.apply(peer, MatchMachine.Event.LOCAL_UNMATCH, NOW + 2);
            final String unmatched = ledger.matchRecordOf(peer);
            assertNotNull(unmatched, "the fact of the match survives (SRS-MAT-010)");
            assertTrue(unmatched.contains("\nunmatched\n"));
            assertEquals(1, stack.store().count(RecordKind.MATCH_RECORD),
                    "flipped, not duplicated and not deleted");
        }
    }

    @Test
    void absorbedReplaysWriteNothing() throws Exception {
        try (Fixtures.Stack stack = Fixtures.openInMemory()) {
            final InterestLedger ledger = ledger(stack.store(), 0x11);
            final String peer = hex(peerId(0x55));

            // An accept with nothing outstanding is absorbed, never trusted.
            final InterestLedger.Outcome echo =
                    ledger.apply(peer, MatchMachine.Event.REMOTE_ACCEPT, NOW);
            assertEquals(MatchMachine.State.NONE, echo.state());
            assertNull(echo.matchIdHex());
            assertEquals(0, stack.store().count(RecordKind.INTEREST_RECORD),
                    "an absorbed event writes no row (SRS-MAT-014)");
            assertFalse(echo.rateLimited());
        }
    }
}
