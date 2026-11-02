package dev.routeplane.resilience;

import dev.routeplane.config.RouteplaneConfig.HealthConfig;
import dev.routeplane.util.TimeSource;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Health state of one upstream, fed by two independent sources:
 *
 * <ul>
 *   <li><b>passive</b> (outlier ejection): {@code consecutiveFailures} failed real requests eject the upstream for
 *       {@code ejectionTime}. When the ejection expires the upstream is on probation - a single further failure ejects
 *       it again - so a dead upstream costs one request per period rather than N.</li>
 *   <li><b>active</b>: periodic probes; {@code unhealthyThreshold} consecutive failures mark it down and
 *       {@code healthyThreshold} consecutive successes mark it up again.</li>
 * </ul>
 *
 * An upstream is available only if neither source considers it unhealthy. New upstreams start available.
 */
public final class UpstreamHealth {
    private final HealthConfig config;
    private final TimeSource time;

    private final AtomicInteger consecutiveRequestFailures = new AtomicInteger();
    private final AtomicLong ejectedUntilNanos = new AtomicLong(Long.MIN_VALUE);
    private final LongAdder ejections = new LongAdder();

    private volatile boolean activeDown;
    private int activeFailures;
    private int activeSuccesses;

    public UpstreamHealth(HealthConfig config, TimeSource time) {
        this.config = config;
        this.time = time;
    }

    public boolean isAvailable() {
        if (activeDown) {
            return false;
        }
        long until = ejectedUntilNanos.get();
        return until == Long.MIN_VALUE || time.nanoTime() - until >= 0;
    }

    public boolean isActiveDown() {
        return activeDown;
    }

    public boolean isEjected() {
        long until = ejectedUntilNanos.get();
        return until != Long.MIN_VALUE && time.nanoTime() - until < 0;
    }

    public long ejections() {
        return ejections.sum();
    }

    // ----------------------------------------------------------------------------------------------- passive

    public void recordRequestSuccess() {
        if (config == null || config.passive() == null) {
            return;
        }
        consecutiveRequestFailures.set(0);
    }

    /** @return true if this failure caused an ejection */
    public boolean recordRequestFailure() {
        if (config == null || config.passive() == null) {
            return false;
        }
        int n = consecutiveRequestFailures.incrementAndGet();
        if (n >= config.passive().consecutiveFailures()) {
            long until = time.nanoTime() + config.passive().ejectionTime().toNanos();
            ejectedUntilNanos.set(until);
            consecutiveRequestFailures.set(Math.max(0, config.passive().consecutiveFailures() - 1)); // probation
            ejections.increment();
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------------------------------------ active

    /** @return true if the up/down state changed */
    public synchronized boolean recordProbe(boolean ok) {
        if (config == null || config.active() == null) {
            return false;
        }
        if (ok) {
            activeFailures = 0;
            if (activeDown && ++activeSuccesses >= config.active().healthyThreshold()) {
                activeDown = false;
                activeSuccesses = 0;
                return true;
            }
        } else {
            activeSuccesses = 0;
            if (!activeDown && ++activeFailures >= config.active().unhealthyThreshold()) {
                activeDown = true;
                activeFailures = 0;
                return true;
            }
        }
        return false;
    }
}
