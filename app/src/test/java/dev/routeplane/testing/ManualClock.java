package dev.routeplane.testing;

import dev.routeplane.util.TimeSource;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/** A hand-advanced clock for deterministic tests of time-dependent logic. */
public final class ManualClock implements TimeSource {
    private final AtomicLong nanos = new AtomicLong(1_000_000_000L);
    private final AtomicLong wall = new AtomicLong(1_700_000_000_000L);

    @Override
    public long nanoTime() {
        return nanos.get();
    }

    @Override
    public long currentTimeMillis() {
        return wall.get();
    }

    public void advance(Duration d) {
        nanos.addAndGet(d.toNanos());
        wall.addAndGet(d.toMillis());
    }
}
