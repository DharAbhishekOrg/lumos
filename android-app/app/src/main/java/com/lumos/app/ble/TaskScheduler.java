/*
 * Lumos - android-app/app / Deferred-execution seam for the radio controllers.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-TST-001, SRS-NBY-005
 * ADRs: -
 * Threading: serial-executor
 * Notes: Duty cycles, EID rotation and retry backoff all mean "run this later",
 * and "later" on a device is a Handler while in a JVM test it is a hand-cranked
 * fake. The controllers depend on this seam plus an injected clock, so every
 * timing behaviour in the BLE stack is a pure function of scripted time -
 * the same discipline the fragmentation reassembler set in S2a.
 */

package com.lumos.app.ble;

/** Schedules work after a delay on the owning serial executor. */
public interface TaskScheduler {

    /** A scheduled handle that can be cancelled before it runs. */
    interface Scheduled {
        /** Cancels the task if it has not run yet; idempotent. */
        void cancel();
    }

    /**
     * Runs the task after a delay.
     *
     * @param task the work
     * @param delayMillis how long to wait
     * @return a cancellable handle
     */
    Scheduled schedule(Runnable task, long delayMillis);
}
