package dev.routeplane.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.routeplane.testing.ManualClock;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class LatencyWindowTest {
    private final ManualClock clock = new ManualClock();

    @Test
    void percentilesAreOnlyReportedWhenEnoughSamplesBackThem() {
        assertEquals(20, LatencyWindow.minSamples(0.5));
        assertEquals(100, LatencyWindow.minSamples(0.9));
        assertEquals(200, LatencyWindow.minSamples(0.95));
        assertEquals(1000, LatencyWindow.minSamples(0.99));
        assertEquals(10000, LatencyWindow.minSamples(0.999));
        LatencyWindow w = new LatencyWindow(clock, 60_000, 6);
        for (int i = 1; i <= 50; i++) {
            w.recordNanos(i * 1_000_000L);
        }
        LatencyWindow.Snapshot s = w.snapshot();
        assertEquals(50, s.samples());
        assertTrue(!Double.isNaN(s.quantileSeconds(0.5)), "p50 needs 20 samples");
        assertTrue(Double.isNaN(s.quantileSeconds(0.9)), "p90 needs 100");
        assertTrue(Double.isNaN(s.quantileSeconds(0.99)), "p99 needs 1000");
        assertTrue(Double.isNaN(s.quantileSeconds(0.999)));
    }

    @Test
    void percentilesAreAccurateForAKnownDistribution() {
        LatencyWindow w = new LatencyWindow(clock, 60_000, 6);
        for (int i = 1; i <= 20_000; i++) {
            w.recordNanos(i * 1_000L); // 1 us .. 20 ms uniformly
        }
        LatencyWindow.Snapshot s = w.snapshot();
        assertEquals(0.010, s.quantileSeconds(0.5), 0.0003);
        assertEquals(0.018, s.quantileSeconds(0.9), 0.0004);
        assertEquals(0.0198, s.quantileSeconds(0.99), 0.0004);
        assertEquals(0.01998, s.quantileSeconds(0.999), 0.0004);
    }

    @Test
    void oldSamplesSlideOutOfTheWindow() {
        LatencyWindow w = new LatencyWindow(clock, 60_000, 6);
        for (int i = 0; i < 100; i++) {
            w.recordNanos(5_000_000_000L); // 5 s outliers
        }
        clock.advance(Duration.ofSeconds(30));
        for (int i = 0; i < 100; i++) {
            w.recordNanos(1_000_000L);
        }
        assertEquals(200, w.snapshot().samples());
        assertTrue(w.snapshot().quantileSeconds(0.95) > 1.0, "outliers still inside the window");
        clock.advance(Duration.ofSeconds(40));
        LatencyWindow.Snapshot later = w.snapshot();
        assertEquals(100, later.samples(), "the 5 s samples are older than 60 s now");
        assertTrue(later.quantileSeconds(0.5) < 0.01, "p50 is reportable with 100 samples");
        assertTrue(Double.isNaN(later.quantileSeconds(0.95)), "p95 is not: 100 samples are too few for it to mean anything");
        clock.advance(Duration.ofSeconds(120));
        assertEquals(0, w.snapshot().samples());
    }

    @Test
    void concurrentRecordingLosesNothing() throws Exception {
        LatencyWindow w = new LatencyWindow(clock, 60_000, 6);
        int threads = 8;
        int each = 40_000;
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService ex = Executors.newFixedThreadPool(threads);
        for (int t = 0; t < threads; t++) {
            ex.submit(() -> {
                try {
                    go.await();
                } catch (InterruptedException e) {
                    return;
                }
                for (int i = 0; i < each; i++) {
                    w.recordNanos(1_000_000L + i);
                }
            });
        }
        go.countDown();
        ex.shutdown();
        assertTrue(ex.awaitTermination(60, TimeUnit.SECONDS));
        assertEquals((long) threads * each, w.snapshot().samples());
    }
}
