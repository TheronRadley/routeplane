package dev.routeplane.resilience;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.routeplane.config.RouteplaneConfig.ActiveHealth;
import dev.routeplane.config.RouteplaneConfig.HealthConfig;
import dev.routeplane.config.RouteplaneConfig.JitterMode;
import dev.routeplane.config.RouteplaneConfig.PassiveHealth;
import dev.routeplane.testing.ManualClock;
import java.time.Duration;
import java.util.BitSet;
import java.util.SplittableRandom;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ResiliencePrimitivesTest {
    private final ManualClock clock = new ManualClock();

    // ----------------------------------------------------------------------------------------- retry budget

    @Test
    void retryBudgetHasAFloorForQuietRoutes() {
        RetryBudget b = new RetryBudget(0.1, 2.0, Duration.ofSeconds(10), clock);
        for (int i = 0; i < 20; i++) {
            assertTrue(b.tryAcquireRetry(), "floor = 2/s * 10 s = 20 retries even with no traffic: " + i);
        }
        assertFalse(b.tryAcquireRetry());
    }

    @Test
    void retryBudgetScalesWithTrafficAndBoundsAmplification() {
        RetryBudget b = new RetryBudget(0.2, 0.0, Duration.ofSeconds(10), clock);
        assertFalse(b.tryAcquireRetry(), "no traffic, no floor, no retries");
        for (int i = 0; i < 100; i++) {
            b.recordRequest();
        }
        int granted = 0;
        for (int i = 0; i < 1000; i++) {
            if (b.tryAcquireRetry()) {
                granted++;
            }
        }
        assertEquals(20, granted, "20% of 100 requests");
    }

    @Test
    void retryBudgetRecoversAsTheWindowSlides() {
        RetryBudget b = new RetryBudget(0.0, 1.0, Duration.ofSeconds(5), clock);
        int first = 0;
        while (b.tryAcquireRetry()) {
            first++;
        }
        assertEquals(5, first);
        clock.advance(Duration.ofSeconds(6));
        assertTrue(b.tryAcquireRetry(), "old retries aged out of the window");
    }

    // ----------------------------------------------------------------------------------------------- backoff

    @Test
    void backoffGrowsExponentiallyAndIsCapped() {
        Backoff b = new Backoff(Duration.ofMillis(100), Duration.ofSeconds(1), 2.0, JitterMode.NONE, new SplittableRandom(1));
        assertEquals(Duration.ofMillis(100), b.delayBeforeRetry(1));
        assertEquals(Duration.ofMillis(200), b.delayBeforeRetry(2));
        assertEquals(Duration.ofMillis(400), b.delayBeforeRetry(3));
        assertEquals(Duration.ofMillis(800), b.delayBeforeRetry(4));
        assertEquals(Duration.ofSeconds(1), b.delayBeforeRetry(5));
        assertEquals(Duration.ofSeconds(1), b.delayBeforeRetry(30));
    }

    @Test
    void jitterStaysWithinItsDocumentedBounds() {
        Backoff full = new Backoff(Duration.ofMillis(100), Duration.ofSeconds(10), 2.0, JitterMode.FULL, new SplittableRandom(7));
        Backoff equal = new Backoff(Duration.ofMillis(100), Duration.ofSeconds(10), 2.0, JitterMode.EQUAL, new SplittableRandom(7));
        long minFull = Long.MAX_VALUE;
        long maxFull = 0;
        for (int i = 0; i < 5000; i++) {
            long f = full.delayBeforeRetry(3).toMillis();
            long e = equal.delayBeforeRetry(3).toMillis();
            assertTrue(f >= 0 && f <= 400, "full jitter in [0, 400]: " + f);
            assertTrue(e >= 200 && e <= 400, "equal jitter in [200, 400]: " + e);
            minFull = Math.min(minFull, f);
            maxFull = Math.max(maxFull, f);
        }
        assertTrue(minFull < 20 && maxFull > 380, "full jitter actually spreads: " + minFull + ".." + maxFull);
    }

    @Test
    void jitterIsReproducibleForAFixedSeed() {
        Backoff a = new Backoff(Duration.ofMillis(50), Duration.ofSeconds(5), 2.0, JitterMode.FULL, new SplittableRandom(99));
        Backoff b = new Backoff(Duration.ofMillis(50), Duration.ofSeconds(5), 2.0, JitterMode.FULL, new SplittableRandom(99));
        for (int i = 1; i < 20; i++) {
            assertEquals(a.delayBeforeRetry(i), b.delayBeforeRetry(i));
        }
    }

    // ---------------------------------------------------------------------------------------------- limiter

    @Test
    void limiterRejectsBeyondItsLimitAndIsExactUnderContention() throws Exception {
        ConcurrencyLimiter l = new ConcurrencyLimiter(3);
        assertTrue(l.tryAcquire());
        assertTrue(l.tryAcquire());
        assertTrue(l.tryAcquire());
        assertFalse(l.tryAcquire());
        l.release();
        assertTrue(l.tryAcquire());

        ConcurrencyLimiter shared = new ConcurrencyLimiter(10);
        AtomicInteger granted = new AtomicInteger();
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService ex = Executors.newFixedThreadPool(16);
        for (int t = 0; t < 16; t++) {
            ex.submit(() -> {
                try {
                    go.await();
                } catch (InterruptedException e) {
                    return;
                }
                for (int i = 0; i < 1000; i++) {
                    if (shared.tryAcquire()) {
                        granted.incrementAndGet();
                    }
                }
            });
        }
        go.countDown();
        ex.shutdown();
        assertTrue(ex.awaitTermination(30, TimeUnit.SECONDS));
        assertEquals(10, granted.get(), "never more permits than the limit, none lost");
        assertEquals(10, shared.inFlight());
    }

    // ---------------------------------------------------------------------------------------------- health

    private HealthConfig health(int failures, int unhealthy, int healthy) {
        BitSet ok = new BitSet(600);
        ok.set(200, 400);
        return new HealthConfig(new ActiveHealth("/h", Duration.ofSeconds(1), Duration.ofMillis(500), unhealthy, healthy, ok),
                new PassiveHealth(failures, Duration.ofSeconds(30)));
    }

    @Test
    void passiveEjectionHasProbationAfterTheEjectionExpires() {
        UpstreamHealth h = new UpstreamHealth(health(3, 3, 2), clock);
        assertTrue(h.isAvailable());
        assertFalse(h.recordRequestFailure());
        assertFalse(h.recordRequestFailure());
        h.recordRequestSuccess(); // resets the consecutive counter
        assertFalse(h.recordRequestFailure());
        assertFalse(h.recordRequestFailure());
        assertTrue(h.recordRequestFailure(), "third consecutive failure ejects");
        assertFalse(h.isAvailable());
        assertTrue(h.isEjected());
        clock.advance(Duration.ofSeconds(29));
        assertFalse(h.isAvailable());
        clock.advance(Duration.ofSeconds(1));
        assertTrue(h.isAvailable(), "ejection expired");
        assertTrue(h.recordRequestFailure(), "on probation: one failure re-ejects");
        assertFalse(h.isAvailable());
        assertEquals(2, h.ejections());
    }

    @Test
    void activeChecksUseHysteresis() {
        UpstreamHealth h = new UpstreamHealth(health(5, 3, 2), clock);
        assertFalse(h.recordProbe(false));
        assertFalse(h.recordProbe(false));
        assertTrue(h.isAvailable());
        assertTrue(h.recordProbe(false), "third consecutive failure marks it down");
        assertFalse(h.isAvailable());
        assertTrue(h.isActiveDown());
        assertFalse(h.recordProbe(true));
        assertFalse(h.recordProbe(false), "a failure resets the success streak");
        assertFalse(h.recordProbe(true));
        assertTrue(h.recordProbe(true), "two consecutive successes bring it back");
        assertTrue(h.isAvailable());
    }

    @Test
    void withoutConfigurationAnUpstreamIsAlwaysAvailable() {
        UpstreamHealth h = new UpstreamHealth(null, clock);
        for (int i = 0; i < 100; i++) {
            assertFalse(h.recordRequestFailure());
        }
        assertTrue(h.isAvailable());
        assertFalse(h.recordProbe(false));
    }
}
