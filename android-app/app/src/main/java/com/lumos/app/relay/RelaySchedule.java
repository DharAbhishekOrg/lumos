/*
 * Lumos - android-app/app / What the relay job does after a pass.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-RMT-003, TRS-TRN-012, SRS-NFR-BAT-001
 * ADRs: ADR-001
 * Threading: any
 * Notes: The policy is plain Java so every branch is tested; the Android job
 * only carries it out. Offline: nothing is retried on a timer - the next run
 * waits for the platform to report a network, which is exactly "retry when an
 * approved path returns" (SRS-RMT-003, TRS-TRN-012) and costs no battery
 * while there is none (SRS-NFR-BAT-001). Rate limited: wait the server's
 * Retry-After. Busy without one, or a local failure: the platform's
 * exponential backoff. A refused credential: run again at once, because the
 * pass already dropped it and the next one registers afresh. More waiting in
 * the mailbox: run again at once. A job never loops more than
 * MAX_PASSES_PER_JOB times, so a bad server cannot pin the CPU.
 */

package com.lumos.app.relay;

/** The relay job's next step. */
public final class RelaySchedule {

    /** Passes one job may run back to back. */
    public static final int MAX_PASSES_PER_JOB = 8;
    private static final long MILLIS_PER_SECOND = 1000L;

    private RelaySchedule() { }

    /** The kinds of next step. */
    public enum Kind {
        /** Run another pass now, in this job. */
        AGAIN,
        /** Nothing more to do until something new happens. */
        DONE,
        /** Run when the platform reports a network. */
        WHEN_ONLINE,
        /** Run after a fixed delay (the server's Retry-After). */
        AFTER_DELAY,
        /** Let the platform back off and retry. */
        BACKOFF
    }

    /**
     * The next step.
     *
     * @param kind what to do
     * @param delayMillis the delay for AFTER_DELAY, otherwise 0
     */
    public record Next(Kind kind, long delayMillis) { }

    /**
     * Decides from one pass's report.
     *
     * @param r the report
     * @param passesSoFar passes this job has already run, this one included
     * @return the next step
     */
    public static Next after(final RelaySync.Report r, final int passesSoFar) {
        final boolean canLoop = passesSoFar < MAX_PASSES_PER_JOB;
        final RelaySync.Stop stop = r.stoppedBy();
        if (stop == null) {
            return new Next(r.moreWaiting() && canLoop ? Kind.AGAIN : Kind.DONE, 0);
        }
        switch (stop.kind()) {
            case OFFLINE:
                return new Next(Kind.WHEN_ONLINE, 0);
            case AUTH:
                return new Next(canLoop ? Kind.AGAIN : Kind.BACKOFF, 0);
            case BUSY:
                return stop.retryAfterSeconds() >= 0
                        ? new Next(Kind.AFTER_DELAY, stop.retryAfterSeconds() * MILLIS_PER_SECOND)
                        : new Next(Kind.BACKOFF, 0);
            default:
                return new Next(Kind.BACKOFF, 0);
        }
    }
}
