package dev.routeplane.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.routeplane.fault.FaultProfile;
import dev.routeplane.fault.FaultUpstream;
import dev.routeplane.testing.ProxyHarness;
import dev.routeplane.testing.RawClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Everything at once under concurrency: misbehaving upstreams, retries, breakers, limits, passive health, a slow
 * shadow target, mixed request shapes. The assertions are structural invariants, not performance numbers: nothing hangs,
 * nothing leaks, no internal errors, and the books balance once the load stops.
 */
@Timeout(180)
class ProxyStressTest {

    @Test
    void invariantsHoldUnderMixedLoadAndMisbehavingUpstreams(@TempDir Path dir) throws Exception {
        try (ProxyHarness h = new ProxyHarness(dir)) {
            h.upstream("a", FaultProfile.DEFAULT.with("errorRate", 0.15).with("errorStatus", 503).with("jitterMs", 20).with("seed", 11));
            h.upstream("b", FaultProfile.DEFAULT.with("resetRate", 0.04).with("partialRate", 0.02).with("delayMs", 5).with("jitterMs", 10).with("seed", 12));
            h.upstream("c", FaultProfile.DEFAULT.with("payloadMin", 100).with("payloadMax", 20_000).with("seed", 13));
            h.upstream("shadow", FaultProfile.DEFAULT.with("delayMs", 300));
            h.start("""
                    routes:
                      - id: api
                        match: {pathPrefix: /api}
                        policy: weighted-round-robin
                        upstreams:
                          - {id: a, url: "http://127.0.0.1:${UP_A}", weight: 3, maxConcurrent: 40}
                          - {id: b, url: "http://127.0.0.1:${UP_B}", weight: 3, maxConcurrent: 40}
                          - {id: c, url: "http://127.0.0.1:${UP_C}", weight: 2, maxConcurrent: 40}
                        timeouts: {connect: 500ms, response: 2s, request: 4s}
                        retry: {maxAttempts: 3, methods: [GET, HEAD, POST], backoff: {initial: 5ms, max: 50ms, jitter: full}, budget: {ratio: 0.3, minRetriesPerSecond: 20}}
                        circuitBreaker: {failureRateThreshold: 60, minimumRequests: 30, slidingWindow: {type: count, size: 60}, openDuration: 1s, halfOpenMaxProbes: 2, recoverySuccessThreshold: 2}
                        health: {passive: {consecutiveFailures: 8, ejectionTime: 1s}}
                        shadow:
                          methods: [GET]
                          targets: [{id: sh, url: "http://127.0.0.1:${UP_SHADOW}", samplePercent: 50, timeout: 500ms, maxConcurrent: 4, queueCapacity: 16}]
                    """);
            int threads = 32;
            AtomicBoolean stop = new AtomicBoolean();
            AtomicLong sent = new AtomicLong();
            AtomicLong worstMillis = new AtomicLong();
            Map<Integer, AtomicLong> byStatus = new ConcurrentHashMap<>();
            List<String> problems = java.util.Collections.synchronizedList(new ArrayList<>());
            ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor();
            List<Future<?>> fs = new ArrayList<>();
            int platformThreadsBefore = Thread.activeCount();
            for (int t = 0; t < threads; t++) {
                final int seed = t;
                fs.add(ex.submit(() -> {
                    SplittableRandom r = new SplittableRandom(seed);
                    while (!stop.get()) {
                        long t0 = System.nanoTime();
                        try (RawClient c = new RawClient(h.port(), 8000)) {
                            for (int k = 0; k < 5 && !stop.get(); k++) {
                                int kind = r.nextInt(10);
                                RawClient.Response resp;
                                if (kind < 7) {
                                    resp = c.get("/api/x");
                                } else if (kind < 8) {
                                    resp = c.request("POST", "/api/echo", null, new byte[r.nextInt(2000)]);
                                } else if (kind < 9) {
                                    resp = c.get("/api/big?bytes=" + (10_000 + r.nextInt(200_000)));
                                } else {
                                    resp = c.request("HEAD", "/api/x", null, null);
                                }
                                sent.incrementAndGet();
                                byStatus.computeIfAbsent(resp.status(), s -> new AtomicLong()).incrementAndGet();
                                if (resp.truncated()) {
                                    // truncation is legitimate when an upstream dies mid-body, but it must be an honest truncation
                                    if (resp.sawTerminator()) {
                                        problems.add("a truncated body claimed to be complete");
                                    }
                                    break; // the connection is no longer usable
                                }
                                if (resp.status() != 200 && resp.header("connection") != null && resp.header("connection").equalsIgnoreCase("close")) {
                                    break;
                                }
                            }
                        } catch (Exception e) {
                            // a connection the proxy deliberately aborted (mid-body upstream failure) is acceptable
                        }
                        worstMillis.accumulateAndGet((System.nanoTime() - t0) / 1_000_000, Math::max);
                    }
                }));
            }
            Thread.sleep(8000);
            stop.set(true);
            for (Future<?> f : fs) {
                f.get();
            }
            ex.shutdown();

            assertTrue(sent.get() > 500, "the load generator made progress: " + sent.get());
            for (int status : byStatus.keySet()) {
                assertTrue(List.of(200, 500, 502, 503, 504).contains(status), "unexpected status " + status + " in " + new TreeMap<>(byStatus));
            }
            assertTrue(problems.isEmpty(), problems.toString());
            assertTrue(worstMillis.get() < 5 * 4000 + 6000, "no client waited longer than its connection's worth of deadlines: " + worstMillis.get() + " ms");

            // after the load stops everything must drain to zero: no leaked permits, workers or queue entries
            assertTrue(ProxyHarness.eventually(Duration.ofSeconds(15), () -> h.app().proxy().inFlight() == 0), "in-flight requests");
            for (String up : new String[] {"a", "b", "c"}) {
                assertTrue(ProxyHarness.eventually(Duration.ofSeconds(15), () -> h.metric("routeplane_upstream_in_flight{route=\"api\",upstream=\"" + up + "\"}") == 0.0),
                        "concurrency permits of upstream " + up + " leaked: " + h.metric("routeplane_upstream_in_flight{route=\"api\",upstream=\"" + up + "\"}"));
            }
            assertTrue(ProxyHarness.eventually(Duration.ofSeconds(20), () -> h.metric("routeplane_shadow_queue_depth{route=\"api\",target=\"sh\"}") == 0.0
                    && h.metric("routeplane_shadow_in_flight{route=\"api\",target=\"sh\"}") == 0.0), "shadow queue and workers drain");
            assertEquals(0.0, h.metric("routeplane_active_requests"));

            String metrics = h.metrics();
            double internal = metric(metrics, "routeplane_errors_total{route=\"api\",kind=\"internal\"}");
            assertTrue(Double.isNaN(internal) || internal == 0.0, "no internal errors: " + internal);
            assertFalse(h.logs().contains("unhandled_exception"), "no unhandled exceptions");
            assertFalse(h.logs().contains("\"event\":\"fatal_error_in_handler\""));

            // the books balance: every request the proxy completed is accounted for exactly once
            double accounted = 0;
            for (String cls : new String[] {"1xx", "2xx", "3xx", "4xx", "5xx", "other"}) {
                double v = metric(metrics, "routeplane_requests_total{route=\"api\",status_class=\"" + cls + "\"}");
                accounted += Double.isNaN(v) ? 0 : v;
            }
            assertTrue(accounted >= sent.get(), "completed requests " + accounted + " must cover every response the clients saw (" + sent.get() + ")");
            double hist = metric(metrics, "routeplane_request_duration_seconds_count{route=\"api\"}");
            assertEquals(accounted, hist, "the duration histogram counts exactly the completed requests");
            double firstAttempts = 0;
            for (String up : new String[] {"a", "b", "c"}) {
                firstAttempts += metric(metrics, "routeplane_upstream_requests_total{route=\"api\",upstream=\"" + up + "\",attempt=\"first\"}");
            }
            assertTrue(firstAttempts > 0);
            // memory stayed bounded: platform thread count did not grow with the load
            assertTrue(Thread.activeCount() < platformThreadsBefore + 60, "platform threads: " + Thread.activeCount() + " (was " + platformThreadsBefore + ")");
            // the system is still healthy afterwards
            try (RawClient c = h.raw()) {
                int ok = 0;
                for (int i = 0; i < 20; i++) {
                    if (c.get("/api/x").status() == 200) {
                        ok++;
                    }
                }
                assertTrue(ok >= 10, "the proxy still serves normally after the storm: " + ok + "/20");
            }
        }
    }

    private static double metric(String scrape, String prefix) {
        for (String line : scrape.split("\n")) {
            if (line.startsWith(prefix)) {
                return Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1));
            }
        }
        return Double.NaN;
    }
}
