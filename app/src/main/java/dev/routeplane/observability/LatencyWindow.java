package dev.routeplane.observability;

import dev.routeplane.util.TimeSource;
import java.util.concurrent.locks.ReentrantLock;
import org.HdrHistogram.ConcurrentHistogram;
import org.HdrHistogram.Histogram;

/**
 * Sliding-window latency percentiles on top of HdrHistogram.
 *
 * <p>The window is a ring of fixed-length slices. Recording is wait-free for writers within the current slice;
 * rotating to a new slice and reading take a lock (rare). Percentiles are only reported when the window holds enough
 * samples for the percentile to mean something: a quantile {@code q} needs at least {@code 10 / (1 - q)} samples, i.e.
 * ten observations in the tail beyond it (p50: 20, p99: 1000, p99.9: 10000).
 */
public final class LatencyWindow {
    public static final double[] QUANTILES = {0.5, 0.9, 0.95, 0.99, 0.999};
    private static final long MAX_MICROS = 3_600_000_000L; // 1 hour

    private final TimeSource time;
    private final long sliceNanos;
    private final Slice[] slices;
    private final ReentrantLock lock = new ReentrantLock();
    private volatile Slice current;

    public LatencyWindow(TimeSource time, long windowMillis, int slices) {
        this.time = time;
        this.sliceNanos = Math.max(1, windowMillis / slices) * 1_000_000L;
        this.slices = new Slice[slices];
        for (int i = 0; i < slices; i++) {
            this.slices[i] = new Slice();
        }
        this.slices[0].epoch = epochNow();
        this.current = this.slices[0];
    }

    private long epochNow() {
        return Math.floorDiv(time.nanoTime(), sliceNanos);
    }

    public void recordNanos(long nanos) {
        long micros = Math.min(MAX_MICROS, Math.max(1, nanos / 1000));
        long epoch = epochNow();
        Slice s = current;
        if (s.epoch != epoch) {
            s = rotate(epoch);
        }
        s.histogram.recordValue(micros);
    }

    private Slice rotate(long epoch) {
        lock.lock();
        try {
            Slice s = current;
            if (s.epoch == epoch) {
                return s;
            }
            Slice next = slices[(int) Math.floorMod(epoch, (long) slices.length)];
            if (next.epoch != epoch) {
                next.histogram.reset();
                next.epoch = epoch;
            }
            current = next;
            return next;
        } finally {
            lock.unlock();
        }
    }

    /** Immutable view of the window at one instant. */
    public static final class Snapshot {
        private final Histogram merged;

        Snapshot(Histogram merged) {
            this.merged = merged;
        }

        public long samples() {
            return merged.getTotalCount();
        }

        /** Latency in seconds at quantile {@code q}, or NaN when the window has too few samples for that quantile. */
        public double quantileSeconds(double q) {
            if (merged.getTotalCount() < minSamples(q)) {
                return Double.NaN;
            }
            return merged.getValueAtPercentile(q * 100.0) / 1e6;
        }

        public double maxSeconds() {
            return merged.getTotalCount() == 0 ? Double.NaN : merged.getMaxValue() / 1e6;
        }
    }

    public Snapshot snapshot() {
        long epoch = epochNow();
        Histogram merged = new Histogram(1, MAX_MICROS, 2);
        lock.lock();
        try {
            for (Slice s : slices) {
                if (s.epoch > epoch - slices.length && s.epoch <= epoch) {
                    merged.add(s.histogram);
                }
            }
        } finally {
            lock.unlock();
        }
        return new Snapshot(merged);
    }

    /** Minimum number of samples before quantile {@code q} is considered meaningful (ten tail observations). */
    public static long minSamples(double q) {
        return (long) Math.ceil(10.0 / (1.0 - q) - 1e-6); // tolerance: 10/(1-0.9) is 100.00000000000003 in floating point
    }

    private static final class Slice {
        volatile long epoch = Long.MIN_VALUE;
        final ConcurrentHistogram histogram = new ConcurrentHistogram(1, MAX_MICROS, 2);
    }
}
