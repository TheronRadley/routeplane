package dev.routeplane.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.routeplane.observability.MetricsRegistry.Counter;
import dev.routeplane.observability.MetricsRegistry.Gauge;
import dev.routeplane.observability.MetricsRegistry.Histogram;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class MetricsRegistryTest {

    @Test
    void rendersCountersGaugesAndHistogramsInPrometheusTextFormat() {
        MetricsRegistry r = new MetricsRegistry();
        Counter c = r.counter("demo_requests_total", "Requests", "route", "code");
        c.labels("api", "2xx").add(3);
        c.labels("api", "5xx").inc();
        Gauge g = r.gauge("demo_inflight", "In flight");
        g.labels().set(2);
        g.labels().inc();
        Histogram h = r.histogram("demo_seconds", "Latency", new double[] {0.1, 0.5}, "route");
        h.labels("api").observeNanos(50_000_000L);
        h.labels("api").observeNanos(300_000_000L);
        h.labels("api").observeNanos(2_000_000_000L);
        String out = r.scrape();
        assertTrue(out.contains("# HELP demo_requests_total Requests\n# TYPE demo_requests_total counter\n"), out);
        assertTrue(out.contains("demo_requests_total{route=\"api\",code=\"2xx\"} 3\n"));
        assertTrue(out.contains("demo_requests_total{route=\"api\",code=\"5xx\"} 1\n"));
        assertTrue(out.contains("# TYPE demo_inflight gauge\ndemo_inflight 3\n"));
        assertTrue(out.contains("demo_seconds_bucket{route=\"api\",le=\"0.1\"} 1\n"));
        assertTrue(out.contains("demo_seconds_bucket{route=\"api\",le=\"0.5\"} 2\n"), "buckets are cumulative");
        assertTrue(out.contains("demo_seconds_bucket{route=\"api\",le=\"+Inf\"} 3\n"));
        assertTrue(out.contains("demo_seconds_count{route=\"api\"} 3\n"));
        assertTrue(out.contains("demo_seconds_sum{route=\"api\"} 2.35\n"), out);
    }

    @Test
    void escapesLabelValues() {
        MetricsRegistry r = new MetricsRegistry();
        r.counter("demo_total", "x", "v").labels("a\"b\\c\nd").inc();
        assertTrue(r.scrape().contains("demo_total{v=\"a\\\"b\\\\c\\nd\"} 1"));
    }

    @Test
    void capsTheNumberOfSeriesPerFamilyAndCountsWhatItDrops() {
        MetricsRegistry r = new MetricsRegistry(5);
        Counter c = r.counter("demo_total", "x", "id");
        for (int i = 0; i < 100; i++) {
            c.labels("id-" + i).inc();
        }
        String out = r.scrape();
        long series = out.lines().filter(l -> l.startsWith("demo_total{")).count();
        assertEquals(5, series, "no more series than the cap, however many label values are offered");
        assertTrue(out.contains("routeplane_metric_series_dropped_total 95"), out);
        // already existing series keep working
        c.labels("id-0").inc();
        assertTrue(r.scrape().contains("demo_total{id=\"id-0\"} 2"));
    }

    @Test
    void sameNameWithADifferentShapeIsRejected() {
        MetricsRegistry r = new MetricsRegistry();
        Counter first = r.counter("demo_total", "x", "a");
        assertTrue(first == r.counter("demo_total", "x", "a"), "re-registering the same shape returns the same family");
        assertThrows(IllegalStateException.class, () -> r.counter("demo_total", "x", "a", "b"));
        assertThrows(IllegalStateException.class, () -> r.gauge("demo_total", "x", "a"));
        assertThrows(IllegalArgumentException.class, () -> first.labels("only", "two"));
    }

    @Test
    void aBrokenCollectorDoesNotBreakTheScrape() {
        MetricsRegistry r = new MetricsRegistry();
        r.counter("demo_total", "x").labels().inc();
        r.collector(sink -> {
            throw new IllegalStateException("boom");
        });
        r.collector(sink -> sink.gauge("demo_ok", "ok", 1, "k", "v"));
        String out = r.scrape();
        assertTrue(out.contains("demo_total 1"));
        assertTrue(out.contains("demo_ok{k=\"v\"} 1"));
        assertTrue(out.contains("routeplane_metrics_collector_errors_total"));
    }

    @Test
    void countersAreExactUnderConcurrentUpdates() throws Exception {
        MetricsRegistry r = new MetricsRegistry();
        Counter c = r.counter("demo_total", "x", "k");
        Histogram h = r.histogram("demo_seconds", "x", new double[] {1}, "k");
        int threads = 16;
        int each = 50_000;
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
                    c.labels("a").inc();
                    h.labels("a").observeNanos(1000);
                }
            });
        }
        go.countDown();
        ex.shutdown();
        assertTrue(ex.awaitTermination(60, TimeUnit.SECONDS));
        assertEquals(threads * (long) each, c.labels("a").value());
        assertEquals(threads * (long) each, h.labels("a").count());
    }

    @Test
    void histogramBucketsMustIncrease() {
        assertThrows(IllegalArgumentException.class, () -> new MetricsRegistry().histogram("h", "x", new double[] {1, 1}));
    }
}
