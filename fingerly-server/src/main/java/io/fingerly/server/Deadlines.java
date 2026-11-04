package io.fingerly.server;

import java.time.Duration;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * The one timer thread behind every asynchronous deadline in the SDK.
 *
 * <p>A deadline only completes a future; the work after it runs elsewhere,
 * so one daemon thread serves any number of requests in flight. Cancelled
 * deadlines are removed at once rather than left queued until they would
 * have fired.
 */
final class Deadlines {

    private static final ScheduledThreadPoolExecutor TIMER = timer();

    private Deadlines() {}

    private static ScheduledThreadPoolExecutor timer() {
        ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, "fingerly-deadlines");
            thread.setDaemon(true);
            return thread;
        });
        timer.setRemoveOnCancelPolicy(true);
        return timer;
    }

    /** Runs {@code task} once {@code after} has passed, unless cancelled first. */
    static ScheduledFuture<?> schedule(Runnable task, Duration after) {
        return TIMER.schedule(task, after.toNanos(), TimeUnit.NANOSECONDS);
    }
}
