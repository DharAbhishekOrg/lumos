/*
 * Lumos - android-app/app / Hand-cranked scheduler and clock for the BLE tests.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-TST-001
 * ADRs: -
 * Threading: test-only
 * Notes: The TaskScheduler seam's test half: tasks queue with an absolute due
 * time against a virtual clock, and advanceTo() runs everything due in order.
 * Nothing sleeps, so a full day of duty cycling or an EID rotation boundary is
 * one method call - the point of making time injectable everywhere.
 */

package com.lumos.app.ble;

import java.util.ArrayList;
import java.util.List;

/** Virtual-time TaskScheduler for JVM tests. */
final class FakeScheduler implements TaskScheduler {

    private static final class Task implements Scheduled {
        private final long dueMillis;
        private final long sequence;
        private final Runnable work;
        private boolean cancelled;

        private Task(final long dueMillis, final long sequence, final Runnable work) {
            this.dueMillis = dueMillis;
            this.sequence = sequence;
            this.work = work;
        }

        @Override
        public void cancel() {
            cancelled = true;
        }
    }

    private final List<Task> tasks = new ArrayList<>();
    private long nowMillis;
    private long sequence;

    @Override
    public Scheduled schedule(final Runnable task, final long delayMillis) {
        final Task t = new Task(nowMillis + Math.max(0, delayMillis), sequence++, task);
        tasks.add(t);
        return t;
    }

    /** @return the virtual clock, milliseconds */
    long nowMillis() {
        return nowMillis;
    }

    /** @return the virtual clock, whole unix seconds */
    long nowSeconds() {
        return nowMillis / 1000;
    }

    /**
     * Advances the virtual clock, running every due task in due order.
     *
     * @param targetMillis the new clock value
     */
    void advanceTo(final long targetMillis) {
        while (true) {
            Task next = null;
            for (final Task t : tasks) {
                if (t.cancelled || t.dueMillis > targetMillis) {
                    continue;
                }
                if (next == null || t.dueMillis < next.dueMillis
                        || (t.dueMillis == next.dueMillis && t.sequence < next.sequence)) {
                    next = t;
                }
            }
            if (next == null) {
                break;
            }
            tasks.remove(next);
            nowMillis = Math.max(nowMillis, next.dueMillis);
            next.work.run();
        }
        nowMillis = Math.max(nowMillis, targetMillis);
    }

    /**
     * Advances the clock by a delta.
     *
     * @param deltaMillis how far to move
     */
    void advanceBy(final long deltaMillis) {
        advanceTo(nowMillis + deltaMillis);
    }

    /** @return how many uncancelled tasks are waiting */
    int pending() {
        return (int) tasks.stream().filter(t -> !t.cancelled).count();
    }
}
