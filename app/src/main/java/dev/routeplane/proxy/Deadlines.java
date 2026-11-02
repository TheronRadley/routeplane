package dev.routeplane.proxy;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Deadline enforcement by interruption. A {@link Watch} interrupts the thread that handles a request when its budget
 * runs out. On a virtual thread, interrupt unblocks socket reads and writes (closing the channel) and makes
 * {@code HttpClient.send} throw {@link InterruptedException} - verified in ADR-0001.
 *
 * <p>The interrupt is issued under the same lock that {@link Watch#close()} takes to mark the watch finished, so no
 * interrupt can arrive after {@code close()} returns; a flag that was set but not consumed is cleared there.
 */
final class Deadlines {
    private static final ScheduledThreadPoolExecutor SCHEDULER = new ScheduledThreadPoolExecutor(1, r -> {
        Thread t = new Thread(r, "routeplane-deadlines");
        t.setDaemon(true);
        return t;
    });

    static {
        SCHEDULER.setRemoveOnCancelPolicy(true);
    }

    private Deadlines() {
    }

    static Watch watch(Thread target, long delayNanos) {
        return new Watch(target, delayNanos, null);
    }

    /** Like {@link #watch(Thread, long)} but also runs {@code alsoOnFire} (still under the same guarantee). */
    static Watch watch(Thread target, long delayNanos, Runnable alsoOnFire) {
        return new Watch(target, delayNanos, alsoOnFire);
    }

    static final class Watch implements AutoCloseable {
        private final Thread target;
        private final Object lock = new Object();
        private boolean done;
        private volatile boolean fired;
        private final ScheduledFuture<?> future;
        private final Runnable alsoOnFire;

        private Watch(Thread target, long delayNanos, Runnable alsoOnFire) {
            this.target = target;
            this.alsoOnFire = alsoOnFire;
            this.future = SCHEDULER.schedule(this::fire, Math.max(0, delayNanos), TimeUnit.NANOSECONDS);
        }

        private void fire() {
            synchronized (lock) {
                if (!done) {
                    fired = true;
                    target.interrupt();
                    if (alsoOnFire != null) {
                        alsoOnFire.run();
                    }
                }
            }
        }

        /** True if the deadline elapsed and the target thread was interrupted. */
        boolean fired() {
            return fired;
        }

        @Override
        public void close() {
            synchronized (lock) {
                done = true;
            }
            future.cancel(false);
            if (fired) {
                Thread.interrupted(); // consume a pending interrupt so it cannot leak into later work on this thread
            }
        }
    }
}
