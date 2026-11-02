package dev.routeplane.resilience;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.routeplane.config.RouteplaneConfig.BreakerConfig;
import dev.routeplane.config.RouteplaneConfig.WindowType;
import dev.routeplane.resilience.CircuitBreaker.Permit;
import dev.routeplane.resilience.CircuitBreaker.State;
import dev.routeplane.resilience.CircuitBreaker.Transition;
import dev.routeplane.testing.ManualClock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CircuitBreakerTest {
    private final ManualClock clock = new ManualClock();
    private final List<Transition> seen = Collections.synchronizedList(new ArrayList<>());

    private static BitSet fiveXX() {
        BitSet b = new BitSet(600);
        b.set(500, 600);
        return b;
    }

    private BreakerConfig config(int minimum, int window, double threshold, int probes, int recovery) {
        return new BreakerConfig(threshold, minimum, WindowType.COUNT, window, Duration.ofSeconds(10), Duration.ofSeconds(10),
                probes, recovery, fiveXX(), true);
    }

    private CircuitBreaker breaker(BreakerConfig c) {
        return new CircuitBreaker("api/a", c, clock, (n, t) -> seen.add(t));
    }

    private void record(CircuitBreaker b, int successes, int failures) {
        for (int i = 0; i < successes; i++) {
            Permit p = b.tryAcquire();
            assertTrue(p.allowed());
            b.onSuccess(p);
        }
        for (int i = 0; i < failures; i++) {
            Permit p = b.tryAcquire();
            assertTrue(p.allowed());
            b.onFailure(p);
        }
    }

    @Test
    void staysClosedBelowMinimumVolumeEvenIfEverythingFails() {
        CircuitBreaker b = breaker(config(10, 20, 50, 2, 2));
        record(b, 0, 9);
        assertEquals(State.CLOSED, b.state(), "9 failures < minimumRequests 10");
        record(b, 0, 1);
        assertEquals(State.OPEN, b.state(), "10th call reaches the minimum volume with 100% failures");
        assertEquals("failure_rate_exceeded", seen.get(0).reason());
    }

    @Test
    void opensOnlyWhenTheFailureRateReachesTheThreshold() {
        CircuitBreaker b = breaker(config(10, 10, 50, 2, 2));
        record(b, 6, 3);
        assertEquals(State.CLOSED, b.state(), "3/9 failures, below minimum volume");
        record(b, 0, 1);
        assertEquals(State.CLOSED, b.state(), "4/10 = 40% < 50%");
        record(b, 0, 1);
        assertEquals(State.OPEN, b.state(), "window of the last 10 calls now holds 5 failures: 50% >= 50%");
    }

    @Test
    void slidingWindowForgetsOldOutcomes() {
        CircuitBreaker b = breaker(config(10, 10, 50, 2, 2));
        record(b, 0, 4);
        record(b, 6, 0);
        assertEquals(State.CLOSED, b.state());
        record(b, 4, 0); // pushes the four old failures out of the window of 10
        assertEquals(0, b.snapshot().windowFailures());
        record(b, 0, 4);
        assertEquals(State.CLOSED, b.state(), "only 4 failures in the last 10");
    }

    @Test
    void openRejectsUntilTheOpenDurationElapsesThenAllowsBoundedProbes() {
        CircuitBreaker b = breaker(config(5, 10, 50, 2, 3));
        record(b, 0, 5);
        assertEquals(State.OPEN, b.state());
        assertFalse(b.tryAcquire().allowed());
        clock.advance(Duration.ofSeconds(9));
        assertFalse(b.tryAcquire().allowed(), "still within openDuration");
        assertEquals(2, b.snapshot().totalRejected());
        clock.advance(Duration.ofSeconds(1));
        Permit p1 = b.tryAcquire();
        Permit p2 = b.tryAcquire();
        Permit p3 = b.tryAcquire();
        assertTrue(p1.allowed() && p1.isProbe());
        assertTrue(p2.allowed() && p2.isProbe());
        assertFalse(p3.allowed(), "halfOpenMaxProbes = 2 concurrent probes");
        assertEquals(State.HALF_OPEN, b.state());
        assertEquals("open_duration_elapsed", seen.get(1).reason());
    }

    @Test
    void enoughSuccessfulProbesCloseTheBreakerAndResetTheWindow() {
        CircuitBreaker b = breaker(config(5, 10, 50, 1, 3));
        record(b, 0, 5);
        clock.advance(Duration.ofSeconds(10));
        for (int i = 0; i < 3; i++) {
            Permit p = b.tryAcquire();
            assertTrue(p.allowed() && p.isProbe(), "probe " + i);
            assertEquals(State.HALF_OPEN, b.state());
            b.onSuccess(p);
        }
        assertEquals(State.CLOSED, b.state());
        assertEquals(0, b.snapshot().windowCalls(), "window starts empty after recovery");
        assertEquals("recovered", seen.get(seen.size() - 1).reason());
        assertEquals(List.of(State.CLOSED, State.OPEN, State.HALF_OPEN), List.of(seen.get(0).from(), seen.get(1).from(), seen.get(2).from()));
    }

    @Test
    void aSingleProbeFailureReopensWithAFreshOpenPeriod() {
        CircuitBreaker b = breaker(config(5, 10, 50, 2, 3));
        record(b, 0, 5);
        clock.advance(Duration.ofSeconds(10));
        Permit ok = b.tryAcquire();
        Permit bad = b.tryAcquire();
        b.onSuccess(ok);
        b.onFailure(bad);
        assertEquals(State.OPEN, b.state());
        assertEquals("probe_failed", seen.get(seen.size() - 1).reason());
        clock.advance(Duration.ofSeconds(9));
        assertFalse(b.tryAcquire().allowed(), "the open period restarted at the probe failure");
        clock.advance(Duration.ofSeconds(1));
        assertTrue(b.tryAcquire().allowed());
    }

    @Test
    void outcomesFromBeforeTheLastTransitionAreDiscarded() {
        CircuitBreaker b = breaker(config(5, 10, 50, 1, 1));
        Permit slow = b.tryAcquire(); // a call that started while CLOSED and is still running
        record(b, 0, 5);
        assertEquals(State.OPEN, b.state());
        clock.advance(Duration.ofSeconds(10));
        Permit probe = b.tryAcquire();
        assertTrue(probe.isProbe());
        b.onFailure(slow); // stale failure must not re-open or otherwise touch the HALF_OPEN breaker
        assertEquals(State.HALF_OPEN, b.state());
        b.onSuccess(probe);
        assertEquals(State.CLOSED, b.state());
    }

    @Test
    void ignoredProbesFreeTheirSlotWithoutCountingAsAnOutcome() {
        CircuitBreaker b = breaker(config(5, 10, 50, 1, 1));
        record(b, 0, 5);
        clock.advance(Duration.ofSeconds(10));
        Permit probe = b.tryAcquire();
        assertFalse(b.tryAcquire().allowed(), "the single probe slot is taken");
        b.onIgnored(probe);
        Permit again = b.tryAcquire();
        assertTrue(again.allowed() && again.isProbe(), "slot is free again");
        assertEquals(State.HALF_OPEN, b.state());
    }

    @Test
    void timeBasedWindowExpiresOldBuckets() {
        BreakerConfig c = new BreakerConfig(50, 4, WindowType.TIME, 100, Duration.ofSeconds(5), Duration.ofSeconds(10), 1, 1, fiveXX(), true);
        CircuitBreaker b = breaker(c);
        record(b, 0, 3);
        clock.advance(Duration.ofSeconds(6)); // the three failures fall out of the 5 s window
        assertEquals(0, b.snapshot().windowCalls());
        record(b, 0, 3);
        assertEquals(State.CLOSED, b.state(), "3 recent failures < minimumRequests 4");
        record(b, 0, 1);
        assertEquals(State.OPEN, b.state());
    }

    @Test
    void failureStatusClassificationFollowsConfiguration() {
        CircuitBreaker b = breaker(config(5, 10, 50, 1, 1));
        assertTrue(b.isFailureStatus(500));
        assertTrue(b.isFailureStatus(503));
        assertFalse(b.isFailureStatus(404));
        assertFalse(b.isFailureStatus(429));
        assertTrue(b.countTimeoutsAsFailures());
    }

    @Test
    void transitionsAreAtomicAndProbesAreBoundedUnderContention() throws Exception {
        CircuitBreaker b = breaker(config(20, 50, 50, 3, 2));
        AtomicInteger probesNow = new AtomicInteger();
        AtomicInteger maxProbes = new AtomicInteger();
        AtomicInteger violations = new AtomicInteger();
        int threads = 16;
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService ex = Executors.newFixedThreadPool(threads);
        for (int t = 0; t < threads; t++) {
            final int seed = t;
            ex.submit(() -> {
                try {
                    go.await();
                    java.util.SplittableRandom r = new java.util.SplittableRandom(seed);
                    for (int i = 0; i < 20_000; i++) {
                        Permit p = b.tryAcquire();
                        if (!p.allowed()) {
                            if (r.nextInt(200) == 0) {
                                clock.advance(Duration.ofSeconds(3));
                            }
                            continue;
                        }
                        if (p.isProbe()) {
                            int now = probesNow.incrementAndGet();
                            maxProbes.accumulateAndGet(now, Math::max);
                            if (now > 3) {
                                violations.incrementAndGet();
                            }
                        }
                        boolean fail = r.nextInt(100) < 70;
                        if (p.isProbe()) {
                            probesNow.decrementAndGet();
                        }
                        if (fail) {
                            b.onFailure(p);
                        } else {
                            b.onSuccess(p);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        go.countDown();
        ex.shutdown();
        assertTrue(ex.awaitTermination(60, TimeUnit.SECONDS));
        assertEquals(0, violations.get(), "never more than halfOpenMaxProbes probes in flight");
        List<Transition> copy;
        synchronized (seen) {
            copy = new ArrayList<>(seen);
        }
        assertTrue(copy.size() > 4, "the breaker should have cycled many times under 70% failures: " + copy.size());
        assertEquals(State.CLOSED, copy.get(0).from());
        // every transition starts where the previous one ended, i.e. no transition was lost or interleaved
        for (int i = 1; i < copy.size(); i++) {
            assertEquals(copy.get(i - 1).to(), copy.get(i).from(), "transition " + i + " of " + copy.size());
        }
        assertEquals(copy.size(), b.snapshot().transitionCount());
    }
}
