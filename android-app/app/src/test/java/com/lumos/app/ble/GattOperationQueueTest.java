/*
 * Lumos - android-app/app / TRS-TRN-003 semantics of the GATT operation queue.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-TRN-003, TRS-TST-001
 * ADRs: ADR-002
 * Threading: test-only
 * Notes: Every behaviour TRS-TRN-003 names, executed: strict one-in-flight
 * serialization, the stale callback from a dead connection discarded by its
 * epoch stamp, reconnect epochs failing everything queued against the old
 * connection, and transient status failures retried on the register's backoff
 * shape within RETRY_BUDGET_PER_PATH.
 */

package com.lumos.app.ble;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lumos.protocol.LumosConstants;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The serialized GATT queue, held to TRS-TRN-003. */
final class GattOperationQueueTest {

    private static final int STATUS_FAILURE = 133;

    /** A scripted operation recording its dispatches and result. */
    private static final class Recorded implements GattOperationQueue.Operation {
        private final List<Long> executedAtEpochs = new ArrayList<>();
        private Integer result;

        @Override
        public void execute(final long epoch) {
            executedAtEpochs.add(epoch);
        }

        @Override
        public void onResult(final int status) {
            result = status;
        }
    }

    private final FakeScheduler scheduler = new FakeScheduler();
    private final GattOperationQueue queue =
            new GattOperationQueue(scheduler, delay -> delay);

    @Test
    void operationsRunStrictlyOneAtATime() {
        final Recorded first = new Recorded();
        final Recorded second = new Recorded();
        queue.enqueue(first);
        queue.enqueue(second);

        assertEquals(1, first.executedAtEpochs.size(), "first dispatches immediately");
        assertTrue(second.executedAtEpochs.isEmpty(), "second must wait for the callback");

        queue.operationCompleted(queue.epoch(), GattOperationQueue.STATUS_SUCCESS);
        assertEquals(Integer.valueOf(GattOperationQueue.STATUS_SUCCESS), first.result);
        assertEquals(1, second.executedAtEpochs.size(), "completion releases the next");
    }

    @Test
    void aStaleEpochCompletionIsDiscarded() {
        final Recorded op = new Recorded();
        queue.enqueue(op);
        final long oldEpoch = queue.epoch();
        queue.advanceEpoch(STATUS_FAILURE);
        assertEquals(Integer.valueOf(STATUS_FAILURE), op.result, "reconnect fails the in-flight op");

        // The classic late callback from the connection that no longer exists.
        final Recorded next = new Recorded();
        queue.enqueue(next);
        queue.operationCompleted(oldEpoch, GattOperationQueue.STATUS_SUCCESS);
        assertEquals(null, next.result, "a stale completion must not credit the new operation");

        queue.operationCompleted(queue.epoch(), GattOperationQueue.STATUS_SUCCESS);
        assertEquals(Integer.valueOf(GattOperationQueue.STATUS_SUCCESS), next.result);
    }

    @Test
    void reconnectFailsEverythingPending() {
        final Recorded inFlight = new Recorded();
        final Recorded pending = new Recorded();
        queue.enqueue(inFlight);
        queue.enqueue(pending);

        final long before = queue.epoch();
        assertEquals(before + 1, queue.advanceEpoch(STATUS_FAILURE));
        assertEquals(Integer.valueOf(STATUS_FAILURE), inFlight.result);
        assertEquals(Integer.valueOf(STATUS_FAILURE), pending.result,
                "queued operations from the old connection must not carry over");
    }

    @Test
    void transientFailuresRetryOnTheRegisterBackoffWithinTheBudget() {
        final Recorded op = new Recorded();
        queue.enqueue(op);

        for (int attempt = 1; attempt < LumosConstants.RETRY_BUDGET_PER_PATH_DEFAULT; attempt++) {
            queue.operationCompleted(queue.epoch(), STATUS_FAILURE);
            assertEquals(attempt, op.executedAtEpochs.size(), "retry waits for its backoff");
            scheduler.advanceBy(GattOperationQueue.backoffMillis(attempt));
            assertEquals(attempt + 1, op.executedAtEpochs.size(),
                    "attempt " + (attempt + 1) + " dispatches after the backoff");
        }

        // The budget is spent: the next failure is terminal.
        queue.operationCompleted(queue.epoch(), STATUS_FAILURE);
        assertEquals(Integer.valueOf(STATUS_FAILURE), op.result);
        assertEquals(LumosConstants.RETRY_BUDGET_PER_PATH_DEFAULT, op.executedAtEpochs.size(),
                "attempts stop at RETRY_BUDGET_PER_PATH");
    }

    @Test
    void theBackoffShapeIsTheRegisterShape() {
        assertEquals(LumosConstants.RETRY_BACKOFF_BASE_MS, GattOperationQueue.backoffMillis(1));
        assertEquals((long) LumosConstants.RETRY_BACKOFF_BASE_MS * LumosConstants.RETRY_BACKOFF_FACTOR,
                GattOperationQueue.backoffMillis(2));
        assertEquals(LumosConstants.RETRY_BACKOFF_CAP_MS, GattOperationQueue.backoffMillis(30),
                "the cap holds however many attempts a path sees");
    }

    @Test
    void aReconnectCancelsAWaitingRetry() {
        final Recorded op = new Recorded();
        queue.enqueue(op);
        queue.operationCompleted(queue.epoch(), STATUS_FAILURE);
        queue.advanceEpoch(STATUS_FAILURE);
        scheduler.advanceBy(LumosConstants.RETRY_BACKOFF_CAP_MS);
        assertEquals(1, op.executedAtEpochs.size(),
                "the old connection's retry must not fire into the new epoch");
        assertEquals(Integer.valueOf(STATUS_FAILURE), op.result);
    }
}
