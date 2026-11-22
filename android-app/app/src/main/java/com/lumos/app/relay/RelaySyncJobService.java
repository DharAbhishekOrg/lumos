/*
 * Lumos - android-app/app / The JobScheduler job that runs relay passes.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-RMT-003, TRS-TRN-012, SRS-RMT-018, SRS-NFR-BAT-001
 * ADRs: ADR-001
 * Threading: main for the callbacks; the passes run on the application's one relay executor
 * Notes: The job only carries out RelaySchedule's decisions, which are
 * tested on the JVM. Passes run on the application's single relay executor,
 * so two jobs never run passes at once and ChatStore's transactions stay the
 * only concurrency rule. A process kill mid-pass is the case the relay layer
 * was built for: nothing is lost, and the next job resumes (SRS-RMT-017).
 * When the runtime is not there - the relay is not configured, or this
 * install has not onboarded - the job ends without rescheduling.
 */

package com.lumos.app.relay;

import android.app.job.JobParameters;
import android.app.job.JobService;
import com.lumos.app.LumosApplication;

/** Runs relay passes when the platform says a network is there. */
public final class RelaySyncJobService extends JobService {

    @Override
    public boolean onStartJob(final JobParameters params) {
        final LumosApplication app = (LumosApplication) getApplication();
        app.relayExecutor().execute(() -> jobFinished(params, runPasses(app)));
        return true;
    }

    /** Runs passes until the schedule says stop; true asks the platform to back off and retry. */
    private boolean runPasses(final LumosApplication app) {
        try {
            final ChatRuntime runtime = app.runtime();
            if (runtime == null) {
                return false;
            }
            for (int pass = 1; ; pass++) {
                final RelaySchedule.Next next = RelaySchedule.after(runtime.sync().run(), pass);
                switch (next.kind()) {
                    case AGAIN:
                        continue;
                    case WHEN_ONLINE:
                        RelayJobs.kick(this);
                        return false;
                    case AFTER_DELAY:
                        RelayJobs.after(this, next.delayMillis());
                        return false;
                    case BACKOFF:
                        return true;
                    default:
                        return false;
                }
            }
        } catch (final java.io.IOException | java.security.GeneralSecurityException
                | com.lumos.app.store.StoreException e) {
            // A local engine, keystore or store failure: no detail leaves the
            // device (TRS-OBS-002); the platform backs off and tries again.
            return true;
        }
    }

    @Override
    public boolean onStopJob(final JobParameters params) {
        return true;
    }
}
