package dev.routeplane.resilience;

import dev.routeplane.config.RouteplaneConfig.JitterMode;
import java.time.Duration;
import java.util.random.RandomGenerator;

/** Exponential backoff with optional jitter: {@code min(max, initial * multiplier^(n-1))}, then jittered. */
public final class Backoff {
    private final long initialNanos;
    private final long maxNanos;
    private final double multiplier;
    private final JitterMode jitter;
    private final RandomGenerator random;

    public Backoff(Duration initial, Duration max, double multiplier, JitterMode jitter, RandomGenerator random) {
        this.initialNanos = initial.toNanos();
        this.maxNanos = max.toNanos();
        this.multiplier = multiplier;
        this.jitter = jitter;
        this.random = random;
    }

    /** Delay to wait before retry number {@code retryNumber} (1 for the first retry). */
    public Duration delayBeforeRetry(int retryNumber) {
        double cap = Math.min((double) maxNanos, initialNanos * Math.pow(multiplier, Math.max(0, retryNumber - 1)));
        long capNanos = (long) cap;
        long nanos = switch (jitter) {
            case NONE -> capNanos;
            case FULL -> (long) (random.nextDouble() * capNanos);
            case EQUAL -> capNanos / 2 + (long) (random.nextDouble() * (capNanos / 2));
        };
        return Duration.ofNanos(nanos);
    }
}
