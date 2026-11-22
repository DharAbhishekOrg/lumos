/*
 * Lumos - android-app/app / Every branch of the relay job's next step.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-RMT-003, TRS-TRN-012, SRS-NFR-BAT-001, TRS-TST-001
 * ADRs: ADR-001
 * Threading: test-only
 * Notes: One assertion per branch, so a changed rule fails by name.
 */

package com.lumos.app.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** The relay schedule. */
public class RelayScheduleTest {

    private static RelaySync.Report stopped(final RelayException.Kind kind, final long retryAfter) {
        return new RelaySync.Report(0, 0, 0, 0, false, new RelaySync.Stop(kind, "", retryAfter));
    }

    @Test
    void everyOutcomeHasOneNextStep() {
        assertEquals(RelaySchedule.Kind.DONE, RelaySchedule.after(new RelaySync.Report(1, 0, 0, 0, false, null), 1).kind());
        assertEquals(RelaySchedule.Kind.AGAIN, RelaySchedule.after(new RelaySync.Report(0, 0, 10, 10, true, null), 1).kind(),
                "more waiting in the mailbox: again at once");
        assertEquals(RelaySchedule.Kind.DONE, RelaySchedule.after(new RelaySync.Report(0, 0, 10, 10, true, null),
                RelaySchedule.MAX_PASSES_PER_JOB).kind(), "but never more than the cap in one job");
        assertEquals(RelaySchedule.Kind.WHEN_ONLINE, RelaySchedule.after(stopped(RelayException.Kind.OFFLINE, -1), 1).kind(),
                "offline waits for a network, not a timer (SRS-RMT-003)");
        final RelaySchedule.Next rate = RelaySchedule.after(stopped(RelayException.Kind.BUSY, 30), 1);
        assertEquals(RelaySchedule.Kind.AFTER_DELAY, rate.kind());
        assertEquals(30_000, rate.delayMillis(), "the server's Retry-After, to the millisecond");
        assertEquals(RelaySchedule.Kind.BACKOFF, RelaySchedule.after(stopped(RelayException.Kind.BUSY, -1), 1).kind());
        assertEquals(RelaySchedule.Kind.AGAIN, RelaySchedule.after(stopped(RelayException.Kind.AUTH, -1), 1).kind(),
                "a dropped credential re-registers on the next pass");
        assertEquals(RelaySchedule.Kind.BACKOFF, RelaySchedule.after(stopped(RelayException.Kind.AUTH, -1),
                RelaySchedule.MAX_PASSES_PER_JOB).kind());
        assertEquals(RelaySchedule.Kind.BACKOFF, RelaySchedule.after(stopped(RelayException.Kind.REFUSED, -1), 1).kind());
        assertEquals(RelaySchedule.Kind.BACKOFF, RelaySchedule.after(stopped(RelayException.Kind.NOT_FOUND, -1), 1).kind());
    }
}
