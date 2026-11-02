package dev.routeplane.resilience;

import java.util.concurrent.atomic.AtomicInteger;

/** Lock-free, non-blocking concurrency limit. Excess load is rejected immediately, never queued. */
public final class ConcurrencyLimiter {
    private final int limit;
    private final AtomicInteger inFlight = new AtomicInteger();

    public ConcurrencyLimiter(int limit) {
        this.limit = limit;
    }

    public boolean tryAcquire() {
        while (true) {
            int cur = inFlight.get();
            if (cur >= limit) {
                return false;
            }
            if (inFlight.compareAndSet(cur, cur + 1)) {
                return true;
            }
        }
    }

    public void release() {
        inFlight.decrementAndGet();
    }

    public int inFlight() {
        return inFlight.get();
    }

    public int limit() {
        return limit;
    }
}
