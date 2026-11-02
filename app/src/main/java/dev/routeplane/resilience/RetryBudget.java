package dev.routeplane.resilience;

import dev.routeplane.util.TimeSource;
import java.time.Duration;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Caps retries to a fraction of recent traffic so that retries cannot amplify an outage into a retry storm.
 *
 * <p>Over a sliding window, a retry is allowed while {@code retries < max(minRetriesPerSecond * windowSeconds,
 * ratio * firstAttempts)}. The floor lets low-traffic routes retry at all; the ratio bounds amplification under load
 * (ratio 0.2 means at most about 20% extra upstream requests caused by retries).
 */
public final class RetryBudget {
    private final double ratio;
    private final double minPerSecond;
    private final int windowSeconds;
    private final TimeSource time;
    private final ReentrantLock lock = new ReentrantLock();
    private final long[] second;
    private final long[] requests;
    private final long[] retries;

    public RetryBudget(double ratio, double minRetriesPerSecond, Duration window, TimeSource time) {
        this.ratio = ratio;
        this.minPerSecond = minRetriesPerSecond;
        this.windowSeconds = (int) Math.max(1, window.toSeconds());
        this.time = time;
        this.second = new long[windowSeconds];
        this.requests = new long[windowSeconds];
        this.retries = new long[windowSeconds];
        java.util.Arrays.fill(second, Long.MIN_VALUE);
    }

    private int bucket(long nowSecond) {
        int i = (int) Math.floorMod(nowSecond, (long) windowSeconds);
        if (second[i] != nowSecond) {
            second[i] = nowSecond;
            requests[i] = 0;
            retries[i] = 0;
        }
        return i;
    }

    private long nowSecond() {
        return Math.floorDiv(time.nanoTime(), 1_000_000_000L);
    }

    /** Records one first attempt (an original request, not a retry). */
    public void recordRequest() {
        lock.lock();
        try {
            requests[bucket(nowSecond())]++;
        } finally {
            lock.unlock();
        }
    }

    /** @return true and consumes budget if another retry is allowed right now */
    public boolean tryAcquireRetry() {
        lock.lock();
        try {
            long s = nowSecond();
            int cur = bucket(s);
            long req = 0;
            long ret = 0;
            for (int i = 0; i < windowSeconds; i++) {
                if (second[i] > s - windowSeconds && second[i] <= s) {
                    req += requests[i];
                    ret += retries[i];
                }
            }
            double allowed = Math.max(minPerSecond * windowSeconds, ratio * req);
            if (ret + 1 <= allowed) {
                retries[cur]++;
                return true;
            }
            return false;
        } finally {
            lock.unlock();
        }
    }
}
