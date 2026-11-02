package dev.routeplane.util;

/** Injectable time source: a monotonic clock for durations and a wall clock for timestamps. */
public interface TimeSource {

    /** Monotonic time in nanoseconds; only differences are meaningful. */
    long nanoTime();

    /** Wall-clock time in epoch milliseconds. */
    long currentTimeMillis();

    TimeSource SYSTEM = new TimeSource() {
        @Override
        public long nanoTime() {
            return System.nanoTime();
        }

        @Override
        public long currentTimeMillis() {
            return System.currentTimeMillis();
        }
    };
}
