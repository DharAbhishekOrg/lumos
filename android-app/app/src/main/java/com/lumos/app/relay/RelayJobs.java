/*
 * Lumos - android-app/app / Scheduling the relay pass with the platform's JobScheduler.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-RMT-003, TRS-TRN-012, SRS-NFR-BAT-001
 * ADRs: ADR-001
 * Threading: any
 * Notes: Every job carries a network constraint, so a pass only ever starts
 * when the platform reports a usable network: that is the "retry when an
 * approved path returns" of SRS-RMT-003 at no battery cost while offline.
 * kick() asks for a pass as soon as possible (after a send, a read, a
 * route swap); after() honours a server Retry-After; the periodic job polls
 * the mailbox every 15 minutes, the platform's floor, until push wake
 * (WP-05 S6's generic FCM/APNs wake) is wired into the app. No new
 * dependency: JobScheduler is part of the platform since API 21.
 */

package com.lumos.app.relay;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;

/** Schedules relay passes. */
public final class RelayJobs {

    /** The one-off pass, replaced by each kick. */
    static final int ONE_OFF_JOB = 0x5E1A;
    /** The periodic mailbox poll. */
    static final int PERIODIC_JOB = 0x5E1B;
    private static final long PERIOD_MILLIS = 15L * 60L * 1000L;

    private RelayJobs() { }

    private static JobInfo.Builder builder(final Context context, final int id) {
        return new JobInfo.Builder(id, new ComponentName(context, RelaySyncJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY);
    }

    /**
     * Asks for a pass as soon as a network allows.
     *
     * @param context any context
     */
    public static void kick(final Context context) {
        context.getSystemService(JobScheduler.class).schedule(builder(context, ONE_OFF_JOB).build());
    }

    /**
     * Asks for a pass after a delay, and only with a network.
     *
     * @param context any context
     * @param delayMillis the delay
     */
    public static void after(final Context context, final long delayMillis) {
        context.getSystemService(JobScheduler.class).schedule(builder(context, ONE_OFF_JOB)
                .setMinimumLatency(delayMillis).build());
    }

    /**
     * Ensures the periodic mailbox poll exists.
     *
     * @param context any context
     */
    public static void ensurePeriodic(final Context context) {
        final JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler.getPendingJob(PERIODIC_JOB) == null) {
            scheduler.schedule(builder(context, PERIODIC_JOB).setPeriodic(PERIOD_MILLIS).setPersisted(false).build());
        }
    }
}
