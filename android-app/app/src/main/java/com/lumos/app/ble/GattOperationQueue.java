/*
 * Lumos - android-app/app / Serialized GATT operations with reconnect epochs.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-TRN-003, TRS-TRN-002
 * ADRs: ADR-002
 * Threading: serial-executor
 * Notes: Android's BluetoothGatt tolerates exactly one operation in flight per
 * connection; a second write before the first's callback silently fails on
 * most stacks. This queue is the TRS-TRN-003 answer: operations run strictly
 * one at a time, every dispatch is stamped with the connection EPOCH, and a
 * completion carrying a stale epoch - the classic late callback from a
 * connection that has since dropped and been re-established - is discarded
 * instead of being credited to the wrong operation. A reconnect advances the
 * epoch and fails everything pending, because a new connection has new
 * handles, a renegotiated MTU and no memory of the old one's state.
 *
 * Transient status failures retry on the register's backoff shape
 * (RETRY_BACKOFF: base 1000 ms, factor 2, cap 60 s) up to
 * RETRY_BUDGET_PER_PATH attempts; the jitter bit is honoured through an
 * injected source so tests script it. The queue never sleeps and owns no
 * thread: delays go through the TaskScheduler seam.
 */

package com.lumos.app.ble;

import com.lumos.protocol.LumosConstants;
import java.util.ArrayDeque;
import java.util.function.LongUnaryOperator;

/** One-in-flight GATT operation queue with epoch-stamped completions. */
public final class GattOperationQueue {

    /** A platform success status (BluetoothGatt.GATT_SUCCESS). */
    public static final int STATUS_SUCCESS = 0;

    /** One queued GATT operation. */
    public interface Operation {

        /**
         * Issues the platform call. The queue calls this exactly once per
         * attempt, never concurrently with another operation.
         *
         * @param epoch the connection epoch this attempt belongs to
         */
        void execute(long epoch);

        /**
         * The terminal outcome, after retries are exhausted or on success.
         *
         * @param status STATUS_SUCCESS, or the last platform status
         */
        void onResult(int status);
    }

    private final TaskScheduler scheduler;
    private final LongUnaryOperator jitter;
    private final ArrayDeque<Operation> pending = new ArrayDeque<>();

    private long epoch;
    private Operation inFlight;
    private int attemptsUsed;
    private TaskScheduler.Scheduled retryHandle;

    /**
     * @param scheduler the deferred-execution seam retries run on
     * @param jitter maps a computed backoff delay to the jittered delay; the
     *     register says jitter applies, the SOURCE of randomness is injected
     *     so tests stay scripted (identity for none)
     */
    public GattOperationQueue(final TaskScheduler scheduler, final LongUnaryOperator jitter) {
        this.scheduler = scheduler;
        this.jitter = jitter;
    }

    /** @return the current connection epoch */
    public long epoch() {
        return epoch;
    }

    /**
     * Enqueues an operation; it runs when everything ahead of it has finished.
     *
     * @param op the operation
     */
    public void enqueue(final Operation op) {
        pending.addLast(op);
        dispatchIfIdle();
    }

    /**
     * Delivers a platform completion for the in-flight operation.
     *
     * <p>A stale epoch is the TRS-TRN-003 late callback: it is dropped whole,
     * because whatever connection it came from no longer exists.
     *
     * @param completionEpoch the epoch stamped on the attempt this answers
     * @param status the platform status
     */
    public void operationCompleted(final long completionEpoch, final int status) {
        if (completionEpoch != epoch || inFlight == null) {
            return;
        }
        if (status == STATUS_SUCCESS) {
            finishInFlight(STATUS_SUCCESS);
            return;
        }
        if (attemptsUsed >= LumosConstants.RETRY_BUDGET_PER_PATH_DEFAULT) {
            finishInFlight(status);
            return;
        }
        final long dispatchedEpoch = epoch;
        retryHandle = scheduler.schedule(() -> {
            retryHandle = null;
            if (dispatchedEpoch != epoch || inFlight == null) {
                return;
            }
            attemptsUsed++;
            inFlight.execute(epoch);
        }, jitter.applyAsLong(backoffMillis(attemptsUsed)));
    }

    /**
     * Advances the connection epoch after a disconnect or reconnect.
     *
     * <p>The in-flight operation and everything pending fail with the given
     * status: a fresh connection has new handles and a renegotiated MTU, so
     * nothing queued against the old one may silently carry over.
     *
     * @param failStatus the status delivered to the failed operations
     * @return the new epoch
     */
    public long advanceEpoch(final int failStatus) {
        epoch++;
        if (retryHandle != null) {
            retryHandle.cancel();
            retryHandle = null;
        }
        if (inFlight != null) {
            final Operation op = inFlight;
            inFlight = null;
            op.onResult(failStatus);
        }
        while (!pending.isEmpty()) {
            pending.removeFirst().onResult(failStatus);
        }
        return epoch;
    }

    /**
     * The register backoff delay before a given retry attempt.
     *
     * @param attempt 1-based retry attempt number
     * @return the un-jittered delay in milliseconds
     */
    public static long backoffMillis(final int attempt) {
        long delay = LumosConstants.RETRY_BACKOFF_BASE_MS;
        for (int i = 1; i < attempt; i++) {
            delay = Math.min(delay * LumosConstants.RETRY_BACKOFF_FACTOR,
                    LumosConstants.RETRY_BACKOFF_CAP_MS);
        }
        return Math.min(delay, LumosConstants.RETRY_BACKOFF_CAP_MS);
    }

    private void dispatchIfIdle() {
        if (inFlight != null || pending.isEmpty()) {
            return;
        }
        inFlight = pending.removeFirst();
        attemptsUsed = 1;
        inFlight.execute(epoch);
    }

    private void finishInFlight(final int status) {
        final Operation op = inFlight;
        inFlight = null;
        attemptsUsed = 0;
        op.onResult(status);
        dispatchIfIdle();
    }
}
