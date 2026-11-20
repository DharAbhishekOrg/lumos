/*
 * Lumos - android-app/app / The interest machine, persisted per peer.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-MAT-001, SRS-MAT-003, SRS-MAT-005, SRS-MAT-007, SRS-MAT-010,
 *             SRS-MAT-011, SRS-MAT-014
 * ADRs: ADR-002, ADR-003, ADR-008
 * Threading: serial-executor
 * Notes: The glue and nothing else: MatchMachine decides, this class loads
 * the peer's state from the sealed interest_record (absence is NONE), steps
 * it, persists what changed, and does the two side jobs the machine's
 * effects name - spending the persisted budget on a send_request (a refused
 * budget means the event never reaches the machine, and the caller gets the
 * cooldown instead), and writing the match_record with the derived
 * identifier when an effect establishes. Unmatching flips the match_record's
 * state and never deletes the row (SRS-MAT-010's local half: the fact
 * survives for the safety flows; what may be sent after an unmatch is the
 * remote path's job in WP-05/06). Idempotency comes for free: absorbed
 * events change no state, so they also write nothing.
 */

package com.lumos.app.match;

import com.lumos.app.store.LocalStore;
import com.lumos.app.store.PeerKeys;
import com.lumos.app.store.RecordKind;
import com.lumos.app.store.StoreException;
import com.lumos.protocol.match.MatchId;
import com.lumos.protocol.match.MatchMachine;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/** Persists per-peer interest state and the matches it produces. */
public final class InterestLedger {

    private static final String INTEREST_VERSION = "lumos-interest/1";
    private static final String MATCH_VERSION = "lumos-match/1";

    /**
     * One applied event's outcome.
     *
     * @param state where the relationship now stands
     * @param effect the one instruction for the caller
     * @param matchIdHex the derived identifier on an establishing or
     *     unmatching step, otherwise null
     * @param cooldownRemainingMillis non-zero when the rate limit refused
     *     the event
     */
    public record Outcome(MatchMachine.State state, MatchMachine.Effect effect,
            String matchIdHex, long cooldownRemainingMillis) {

        /** @return whether the event was refused by the rate limit */
        public boolean rateLimited() {
            return cooldownRemainingMillis > 0;
        }
    }

    private final LocalStore store;
    private final PersistedBudget budget;
    private final byte[] ownPeerId;

    /**
     * Binds the ledger.
     *
     * @param store the opened local store
     * @param budget the persisted request budget
     * @param ownPeerId this device's authenticated peer identifier
     */
    public InterestLedger(final LocalStore store, final PersistedBudget budget,
            final byte[] ownPeerId) {
        this.store = store;
        this.budget = budget;
        this.ownPeerId = ownPeerId.clone();
    }

    /**
     * The peer's current state; absence is NONE.
     *
     * @param peerKey the authenticated peer identifier, hex
     * @return where the relationship stands
     * @throws StoreException on engine failure
     */
    public MatchMachine.State stateOf(final String peerKey) throws StoreException {
        final List<byte[]> rows = store.findByNaturalKey(RecordKind.INTEREST_RECORD,
                PeerKeys.naturalKey(peerKey));
        if (rows.isEmpty()) {
            return MatchMachine.State.NONE;
        }
        final String[] lines = new String(rows.get(0), StandardCharsets.UTF_8).split("\n", -1);
        if (lines.length < 2 || !INTEREST_VERSION.equals(lines[0])) {
            return MatchMachine.State.NONE;
        }
        for (final var entry : MatchMachine.stateNames().entrySet()) {
            if (entry.getValue().equals(lines[1])) {
                return entry.getKey();
            }
        }
        return MatchMachine.State.NONE;
    }

    /**
     * Applies one event to one peer, persisting whatever changed.
     *
     * @param peerKey the authenticated peer identifier, hex
     * @param event what happened
     * @param nowMillis the caller's clock
     * @return the outcome, including the match identifier when established
     * @throws StoreException on low storage or engine failure
     */
    public Outcome apply(final String peerKey, final MatchMachine.Event event,
            final long nowMillis) throws StoreException {
        final MatchMachine.State before = stateOf(peerKey);

        // The budget gates the EVENT, not the transition: a rate-limited
        // request never reaches the machine, so no state pretends a request
        // was sent that was not.
        if (event == MatchMachine.Event.LOCAL_REQUEST
                && MatchMachine.step(before, event).effect() == MatchMachine.Effect.SEND_REQUEST
                && !budget.allowed(nowMillis)) {
            return new Outcome(before, MatchMachine.Effect.NONE, null,
                    budget.cooldownRemainingMillis(nowMillis));
        }

        final MatchMachine.Result result = MatchMachine.step(before, event);
        if (result.state() != before) {
            persistState(peerKey, result.state(), nowMillis);
        }
        if (result.effect() == MatchMachine.Effect.SEND_REQUEST) {
            budget.record(nowMillis);
        }

        String matchIdHex = null;
        if (result.effect().establishes()) {
            matchIdHex = toHex(MatchId.derive(ownPeerId, PeerKeys.naturalKey(peerKey)));
            persistMatch(peerKey, matchIdHex, "matched", nowMillis);
        }
        if (result.state() == MatchMachine.State.UNMATCHED
                && before == MatchMachine.State.MATCHED) {
            // The row survives with its state flipped (SRS-MAT-010).
            matchIdHex = toHex(MatchId.derive(ownPeerId, PeerKeys.naturalKey(peerKey)));
            persistMatch(peerKey, matchIdHex, "unmatched", nowMillis);
        }
        return new Outcome(result.state(), result.effect(), matchIdHex, 0);
    }

    /**
     * The stored match record for a peer, if any.
     *
     * @param peerKey the authenticated peer identifier, hex
     * @return the raw payload lines, or null when no match was ever made
     * @throws StoreException on engine failure
     */
    public String matchRecordOf(final String peerKey) throws StoreException {
        final List<byte[]> rows = store.findByNaturalKey(RecordKind.MATCH_RECORD,
                PeerKeys.naturalKey(peerKey));
        return rows.isEmpty() ? null : new String(rows.get(0), StandardCharsets.UTF_8);
    }

    private void persistState(final String peerKey, final MatchMachine.State state,
            final long nowMillis) throws StoreException {
        final String payload = INTEREST_VERSION + "\n"
                + MatchMachine.stateNames().get(state) + "\n" + nowMillis + "\n";
        store.putByNaturalKey(RecordKind.INTEREST_RECORD,
                payload.getBytes(StandardCharsets.UTF_8), PeerKeys.naturalKey(peerKey));
    }

    private void persistMatch(final String peerKey, final String matchIdHex,
            final String state, final long nowMillis) throws StoreException {
        final String payload = MATCH_VERSION + "\n" + matchIdHex + "\n"
                + state + "\n" + nowMillis + "\n";
        store.putByNaturalKey(RecordKind.MATCH_RECORD,
                payload.getBytes(StandardCharsets.UTF_8), PeerKeys.naturalKey(peerKey));
    }

    private static String toHex(final byte[] bytes) {
        final StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (final byte b : bytes) {
            sb.append(String.format(Locale.ROOT, "%02x", b));
        }
        return sb.toString();
    }
}
