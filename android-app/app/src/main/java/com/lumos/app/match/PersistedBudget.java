/*
 * Lumos - android-app/app / The interest budget that survives a restart.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-MAT-011, SRS-SAF-006, SRS-DAT-002
 * ADRs: ADR-002, ADR-003
 * Threading: serial-executor
 * Notes: The Go reference's semantics over the sealed single-row
 * interest_budget record: a rolling hour of sent-at timestamps, pruned on
 * every touch, with the cooldown holding the LONGER of its
 * INTEREST_COOLDOWN_SEC floor (measured from the budget-spending send) and
 * the time until a slot actually frees. Persistence is the point - killing
 * the process to reset the flood limit is exactly the move SRS-SAF-006
 * exists to stop - so every record() writes through before it counts.
 */

package com.lumos.app.match;

import com.lumos.app.store.LocalStore;
import com.lumos.app.store.RecordKind;
import com.lumos.app.store.StoreException;
import com.lumos.protocol.LumosConstants;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** The persisted rolling-hour interest-request allowance (SRS-MAT-011). */
public final class PersistedBudget {

    /** The single-row identifier. */
    public static final String BUDGET_ID = "self";

    private static final String VERSION_LINE = "lumos-interest-budget/1";
    private static final long WINDOW_MILLIS = 3_600_000L;

    private final LocalStore store;

    /**
     * Binds the budget to its store, refusing register values outside their
     * bounds.
     *
     * @param store the opened local store
     */
    public PersistedBudget(final LocalStore store) {
        final int limit = LumosConstants.INTEREST_RATE_LIMIT_PER_HOUR_DEFAULT;
        final int cooldown = LumosConstants.INTEREST_COOLDOWN_SEC_DEFAULT;
        if (limit < LumosConstants.INTEREST_RATE_LIMIT_PER_HOUR_MIN
                || limit > LumosConstants.INTEREST_RATE_LIMIT_PER_HOUR_MAX
                || cooldown < LumosConstants.INTEREST_COOLDOWN_SEC_MIN
                || cooldown > LumosConstants.INTEREST_COOLDOWN_SEC_MAX) {
            throw new IllegalStateException("interest budget outside the register bounds");
        }
        this.store = store;
    }

    /**
     * Whether one more request may be sent.
     *
     * @param nowMillis the caller's clock
     * @return true while the rolling hour has room
     * @throws StoreException on engine failure
     */
    public boolean allowed(final long nowMillis) throws StoreException {
        return load(nowMillis).size() < LumosConstants.INTEREST_RATE_LIMIT_PER_HOUR_DEFAULT;
    }

    /**
     * Notes a sent request, written through before it counts.
     *
     * @param nowMillis the caller's clock
     * @throws StoreException on low storage or engine failure
     */
    public void record(final long nowMillis) throws StoreException {
        final List<Long> sent = load(nowMillis);
        sent.add(nowMillis);
        final StringBuilder payload = new StringBuilder(VERSION_LINE).append('\n');
        for (final Long at : sent) {
            payload.append(at).append('\n');
        }
        store.put(RecordKind.INTEREST_BUDGET, BUDGET_ID,
                payload.toString().getBytes(StandardCharsets.UTF_8), null);
    }

    /**
     * How long the respectful cooldown message should still hold.
     *
     * @param nowMillis the caller's clock
     * @return zero when sending is allowed; otherwise the longer of the
     *     cooldown floor and the time until a slot frees
     * @throws StoreException on engine failure
     */
    public long cooldownRemainingMillis(final long nowMillis) throws StoreException {
        final List<Long> sent = load(nowMillis);
        if (sent.size() < LumosConstants.INTEREST_RATE_LIMIT_PER_HOUR_DEFAULT) {
            return 0;
        }
        final long floor = sent.get(sent.size() - 1)
                + LumosConstants.INTEREST_COOLDOWN_SEC_DEFAULT * 1000L - nowMillis;
        final long slot = sent.get(0) + WINDOW_MILLIS - nowMillis;
        final long remaining = Math.max(floor, slot);
        return Math.max(remaining, 0);
    }

    private List<Long> load(final long nowMillis) throws StoreException {
        final List<Long> sent = new ArrayList<>();
        final Optional<byte[]> raw = store.get(RecordKind.INTEREST_BUDGET, BUDGET_ID);
        if (raw.isEmpty()) {
            return sent;
        }
        final String[] lines = new String(raw.get(), StandardCharsets.UTF_8).split("\n", -1);
        if (lines.length == 0 || !VERSION_LINE.equals(lines[0])) {
            return sent;
        }
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isEmpty()) {
                continue;
            }
            final long at = Long.parseLong(lines[i]);
            if (nowMillis - at < WINDOW_MILLIS) {
                sent.add(at);
            }
        }
        return sent;
    }
}
