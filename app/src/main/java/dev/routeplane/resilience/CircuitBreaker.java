package dev.routeplane.resilience;

import dev.routeplane.config.RouteplaneConfig.BreakerConfig;
import dev.routeplane.config.RouteplaneConfig.WindowType;
import dev.routeplane.util.TimeSource;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Per-upstream circuit breaker: CLOSED -> OPEN -> HALF_OPEN -> CLOSED.
 *
 * <ul>
 *   <li><b>CLOSED</b>: every call is permitted and its outcome is recorded in a sliding window (last N calls, or the
 *       last T seconds). When the window holds at least {@code minimumRequests} outcomes and the failure rate reaches
 *       {@code failureRateThreshold} the breaker opens.</li>
 *   <li><b>OPEN</b>: calls are rejected without touching the upstream until {@code openDuration} has elapsed.</li>
 *   <li><b>HALF_OPEN</b>: at most {@code halfOpenMaxProbes} probe calls may be in flight. {@code recoverySuccessThreshold}
 *       successful probes close the breaker; a single probe failure re-opens it with a fresh open period.</li>
 * </ul>
 *
 * <p>Atomicity: all state, window and probe bookkeeping is guarded by one lock whose critical sections never block, so
 * transitions are atomic with respect to concurrent callers. Each transition bumps an <em>epoch</em>; a {@link Permit}
 * remembers the epoch it was issued in and an outcome that arrives after the breaker has moved on is discarded rather
 * than corrupting the new state. Transition listeners are invoked after the lock is released.
 */
public final class CircuitBreaker {

    public enum State { CLOSED, OPEN, HALF_OPEN }

    /** Result of {@link #tryAcquire()}. */
    public static final class Permit {
        static final Permit DENIED = new Permit(false, false, -1);
        final boolean allowed;
        final boolean probe;
        final long epoch;

        private Permit(boolean allowed, boolean probe, long epoch) {
            this.allowed = allowed;
            this.probe = probe;
            this.epoch = epoch;
        }

        public boolean allowed() {
            return allowed;
        }

        public boolean isProbe() {
            return probe;
        }
    }

    public record Transition(long atMillis, State from, State to, String reason) {
    }

    @FunctionalInterface
    public interface Listener {
        void onTransition(String breakerName, Transition transition);
    }

    /** Point-in-time view for the admin API and tests. */
    public record Snapshot(
            String name,
            State state,
            int windowCalls,
            int windowFailures,
            double failureRatePercent,
            long openRemainingMillis,
            int probesInFlight,
            int probeSuccesses,
            long totalSuccesses,
            long totalFailures,
            long totalRejected,
            long transitionCount,
            List<Transition> recentTransitions) {
    }

    private final String name;
    private final BreakerConfig config;
    private final TimeSource time;
    private final Listener listener;
    private final ReentrantLock lock = new ReentrantLock();
    private final Window window;
    private final Deque<Transition> history = new ArrayDeque<>();
    private final LongAdder successes = new LongAdder();
    private final LongAdder failures = new LongAdder();
    private final LongAdder rejected = new LongAdder();
    private final LongAdder transitions = new LongAdder();

    private State state = State.CLOSED;
    private long epoch;
    private long openUntilNanos;
    private int probesInFlight;
    private int probeSuccesses;

    public CircuitBreaker(String name, BreakerConfig config, TimeSource time, Listener listener) {
        this.name = name;
        this.config = config;
        this.time = time;
        this.listener = listener;
        this.window = config.windowType() == WindowType.COUNT
                ? new CountWindow(config.windowCount())
                : new TimeWindow((int) config.windowDuration().toSeconds(), time);
    }

    public String name() {
        return name;
    }

    public boolean isFailureStatus(int status) {
        return config.failureStatuses().get(status);
    }

    public boolean countTimeoutsAsFailures() {
        return config.countTimeoutsAsFailures();
    }

    // -------------------------------------------------------------------------------------------- permits

    /** Asks for permission to call the upstream. A denied call must not be attempted and must not report an outcome. */
    public Permit tryAcquire() {
        Transition fired = null;
        Permit result;
        lock.lock();
        try {
            if (state == State.OPEN && time.nanoTime() - openUntilNanos >= 0) {
                fired = moveTo(State.HALF_OPEN, "open_duration_elapsed");
            }
            switch (state) {
                case CLOSED -> result = new Permit(true, false, epoch);
                case HALF_OPEN -> {
                    if (probesInFlight < config.halfOpenMaxProbes()) {
                        probesInFlight++;
                        result = new Permit(true, true, epoch);
                    } else {
                        result = Permit.DENIED;
                    }
                }
                default -> result = Permit.DENIED;
            }
        } finally {
            lock.unlock();
        }
        if (!result.allowed) {
            rejected.increment();
        }
        notifyListener(fired);
        return result;
    }

    /**
     * Non-mutating peek used when choosing among upstreams: would a call be permitted right now? True when CLOSED, when
     * OPEN with the open period elapsed (the next call becomes the first half-open probe), or when HALF_OPEN with a free
     * probe slot. It does not consume a permit; {@link #tryAcquire()} remains the single authority.
     */
    public boolean wouldPermit() {
        lock.lock();
        try {
            return switch (state) {
                case CLOSED -> true;
                case OPEN -> time.nanoTime() - openUntilNanos >= 0;
                case HALF_OPEN -> probesInFlight < config.halfOpenMaxProbes();
            };
        } finally {
            lock.unlock();
        }
    }

    public void onSuccess(Permit permit) {
        if (!permit.allowed) {
            return;
        }
        successes.increment();
        Transition fired = null;
        lock.lock();
        try {
            if (permit.epoch != epoch) {
                return; // outcome of a call that started before the last transition
            }
            if (permit.probe) {
                if (state == State.HALF_OPEN) {
                    probesInFlight = Math.max(0, probesInFlight - 1);
                    if (++probeSuccesses >= config.recoverySuccessThreshold()) {
                        fired = moveTo(State.CLOSED, "recovered");
                    }
                }
            } else if (state == State.CLOSED) {
                window.record(true);
            }
        } finally {
            lock.unlock();
        }
        notifyListener(fired);
    }

    public void onFailure(Permit permit) {
        if (!permit.allowed) {
            return;
        }
        failures.increment();
        Transition fired = null;
        lock.lock();
        try {
            if (permit.epoch != epoch) {
                return;
            }
            if (permit.probe) {
                if (state == State.HALF_OPEN) {
                    fired = moveTo(State.OPEN, "probe_failed");
                }
            } else if (state == State.CLOSED) {
                window.record(false);
                int total = window.total();
                if (total >= config.minimumRequests() && window.failureRatePercent() >= config.failureRateThreshold()) {
                    fired = moveTo(State.OPEN, "failure_rate_exceeded");
                }
            }
        } finally {
            lock.unlock();
        }
        notifyListener(fired);
    }

    /** Releases a permit without recording an outcome (client cancelled, request never reached the upstream). */
    public void onIgnored(Permit permit) {
        if (!permit.allowed || !permit.probe) {
            return;
        }
        lock.lock();
        try {
            if (permit.epoch == epoch && state == State.HALF_OPEN) {
                probesInFlight = Math.max(0, probesInFlight - 1);
            }
        } finally {
            lock.unlock();
        }
    }

    // ------------------------------------------------------------------------------------------ inspection

    public State state() {
        lock.lock();
        try {
            // an expired OPEN period is reported as HALF_OPEN-pending only once a call arrives; report what is stored
            return state;
        } finally {
            lock.unlock();
        }
    }

    public Snapshot snapshot() {
        lock.lock();
        try {
            long remaining = state == State.OPEN ? Math.max(0, (openUntilNanos - time.nanoTime()) / 1_000_000) : 0;
            return new Snapshot(name, state, window.total(), window.failures(), window.failureRatePercent(), remaining, probesInFlight,
                    probeSuccesses, successes.sum(), failures.sum(), rejected.sum(), transitions.sum(), new ArrayList<>(history));
        } finally {
            lock.unlock();
        }
    }

    // -------------------------------------------------------------------------------------------- internals

    /** Must be called with the lock held. */
    private Transition moveTo(State to, String reason) {
        State from = state;
        state = to;
        epoch++;
        probesInFlight = 0;
        probeSuccesses = 0;
        window.reset();
        if (to == State.OPEN) {
            openUntilNanos = time.nanoTime() + config.openDuration().toNanos();
        }
        Transition t = new Transition(time.currentTimeMillis(), from, to, reason);
        history.addLast(t);
        while (history.size() > 100) {
            history.removeFirst();
        }
        transitions.increment();
        return t;
    }

    private void notifyListener(Transition t) {
        if (t != null && listener != null) {
            try {
                listener.onTransition(name, t);
            } catch (RuntimeException ignored) {
                // observers must never break the data path
            }
        }
    }

    // ----------------------------------------------------------------------------------------------- windows

    private interface Window {
        void record(boolean success);

        int total();

        int failures();

        void reset();

        default double failureRatePercent() {
            int t = total();
            return t == 0 ? 0.0 : 100.0 * failures() / t;
        }
    }

    /** The last {@code size} outcomes. */
    private static final class CountWindow implements Window {
        private final boolean[] failed;
        private int next;
        private int count;
        private int failureCount;

        CountWindow(int size) {
            this.failed = new boolean[size];
        }

        @Override
        public void record(boolean success) {
            if (count == failed.length && failed[next]) {
                failureCount--;
            }
            failed[next] = !success;
            if (!success) {
                failureCount++;
            }
            next = (next + 1) % failed.length;
            count = Math.min(count + 1, failed.length);
        }

        @Override
        public int total() {
            return count;
        }

        @Override
        public int failures() {
            return failureCount;
        }

        @Override
        public void reset() {
            java.util.Arrays.fill(failed, false);
            next = 0;
            count = 0;
            failureCount = 0;
        }
    }

    /** Outcomes of the last {@code seconds} one-second buckets. */
    private static final class TimeWindow implements Window {
        private final long[] bucketSecond;
        private final int[] totals;
        private final int[] failureCounts;
        private final TimeSource time;

        TimeWindow(int seconds, TimeSource time) {
            this.bucketSecond = new long[seconds];
            this.totals = new int[seconds];
            this.failureCounts = new int[seconds];
            this.time = time;
            java.util.Arrays.fill(bucketSecond, Long.MIN_VALUE);
        }

        private long nowSecond() {
            return Math.floorDiv(time.nanoTime(), 1_000_000_000L);
        }

        @Override
        public void record(boolean success) {
            long s = nowSecond();
            int i = (int) Math.floorMod(s, (long) bucketSecond.length);
            if (bucketSecond[i] != s) {
                bucketSecond[i] = s;
                totals[i] = 0;
                failureCounts[i] = 0;
            }
            totals[i]++;
            if (!success) {
                failureCounts[i]++;
            }
        }

        @Override
        public int total() {
            return sum(totals);
        }

        @Override
        public int failures() {
            return sum(failureCounts);
        }

        private int sum(int[] arr) {
            long s = nowSecond();
            int sum = 0;
            for (int i = 0; i < bucketSecond.length; i++) {
                if (bucketSecond[i] > s - bucketSecond.length && bucketSecond[i] <= s) {
                    sum += arr[i];
                }
            }
            return sum;
        }

        @Override
        public void reset() {
            java.util.Arrays.fill(bucketSecond, Long.MIN_VALUE);
            java.util.Arrays.fill(totals, 0);
            java.util.Arrays.fill(failureCounts, 0);
        }
    }
}
