package dev.routeplane.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Runs the real thing end to end at tiny scale: the proxy and the fault upstreams as separate JVM processes. */
@Timeout(180)
class RunnerSmokeTest {
    @BeforeEach
    void launchers() {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String cp = System.getProperty("java.class.path");
        System.setProperty("routeplane.bench.proxyCmd", java + " -Xmx256m -Djdk.httpclient.allowRestrictedHeaders=host -Dsun.net.httpserver.nodelay=true -cp " + cp + " dev.routeplane.Main");
        System.setProperty("routeplane.bench.upstreamCmd", java + " -Xmx128m -cp " + cp + " dev.routeplane.fault.FaultUpstreamMain");
    }

    @AfterEach
    void clear() {
        System.clearProperty("routeplane.bench.proxyCmd");
        System.clearProperty("routeplane.bench.upstreamCmd");
    }

    private static final String CONFIG = """
            server: {host: 127.0.0.1, port: "${PROXY_PORT}"}
            admin: {host: 127.0.0.1, port: "${ADMIN_PORT}"}
            logging: {level: warn, accessLog: {enabled: false}}
            routes:
              - id: api
                match: {pathPrefix: /api}
                policy: round-robin
                upstreams:
                  - {id: a, url: "http://127.0.0.1:${UP_A}"}
                  - {id: b, url: "http://127.0.0.1:${UP_B}"}
                timeouts: {connect: 500ms, response: 1s, request: 1500ms}
                retry: {maxAttempts: 2, backoff: {initial: 2ms, max: 10ms}, budget: {ratio: 1.0, minRetriesPerSecond: 1000}}
                circuitBreaker: {minimumRequests: 10, slidingWindow: {type: count, size: 20}, openDuration: 1s}
            """;

    @Test
    void aSteadyRunProducesAFullyPopulatedSummary(@TempDir Path dir) throws Exception {
        Assumptions.assumeTrue(Files.exists(Path.of("/proc/self/stat")), "resource sampling needs Linux");
        Scenario s = ScenarioLoader.parse("id: smoke\nkind: steady\nupstreams: [{id: a, profile: {delayMs: 3}}, {id: b, profile: {delayMs: 3}}]\nproxy:\n  config: |\n"
                + CONFIG.indent(4) + "load: {path: /api/x, rates: [50], warmup: 2s, duration: 4s, timeout: 3s}\n", "smoke");
        RunSummary r = new ExperimentRunner(dir, System.out).runOnce(s, "default", s.variants.get("default"), 50, 1);
        assertEquals(200, r.offered, "50 rps over 4 s");
        assertEquals(200, r.ok);
        assertEquals(100.0, r.successRatePct);
        assertTrue(r.latencyAll.p50ms != null && r.latencyAll.p50ms >= 3 && r.latencyAll.p50ms < 100, "p50 " + r.latencyAll.p50ms);
        assertTrue(r.proxyCpuSeconds > 0 && r.proxyRssMbMax > 20 && r.proxyHeapUsedMbMax > 0, "resource sampling worked: cpu=" + r.proxyCpuSeconds + " rss=" + r.proxyRssMbMax);
        assertTrue(r.upstreamServed.get("a") > 0 && r.upstreamServed.get("b") > 0, "round-robin reached both upstreams: " + r.upstreamServed);
        assertEquals(1.0, r.retryAmplification, 0.05, "no failures, no retries");
        assertEquals(4, r.series.size());
        assertTrue(r.proxyDeltas.keySet().stream().anyMatch(k -> k.startsWith("routeplane_requests_total{route=\"api\"")));
        assertTrue(r.flags.stream().anyMatch(f -> f.startsWith("FEW-SAMPLES")), "200 responses are too few for p99: " + r.flags);
        assertTrue(Files.exists(dir.resolve("smoke/default/50rps-run1/proxy.log")), "child logs are kept for debugging");
    }

    @Test
    void aFailureRunAppliesTheTimelineAndSeparatesProxyGeneratedErrors(@TempDir Path dir) throws Exception {
        Assumptions.assumeTrue(Files.exists(Path.of("/proc/self/stat")), "resource sampling needs Linux");
        Scenario s = ScenarioLoader.parse("id: smokefail\nkind: failure\nupstreams: [{id: a, profile: {delayMs: 3}}, {id: b, profile: {delayMs: 3}}]\nproxy:\n  config: |\n"
                + CONFIG.indent(4) + "load: {path: /api/x, rate: 100, warmup: 2s, duration: 8s, timeout: 3s}\n"
                + "timeline:\n  - {at: 2s, upstream: a, set: {errorRate: 1.0, errorStatus: 503}, label: a-503}\n  - {at: 5s, upstream: a, set: {errorRate: 0.0}, label: a-ok, recovery: true}\n", "smokefail");
        RunSummary r = new ExperimentRunner(dir, System.out).runOnce(s, "default", s.variants.get("default"), 100, 1);
        assertEquals(2, r.events.size(), "both timeline events were applied: " + r.events);
        assertTrue(r.events.get(0).atSeconds >= 1.9 && r.events.get(0).atSeconds < 3.0, "first event at ~2 s: " + r.events.get(0).atSeconds);
        assertEquals(800, r.offered);
        // The route retries GETs, so the client never sees upstream a's 503s: the fault shows up as retry amplification instead.
        assertEquals(800, r.ok, "retries masked the failing upstream from clients: " + r.outcomes);
        assertTrue(r.retryAmplification > 1.0 && r.retryAmplification < 1.5, "a few retries happened before the breaker opened and cut them off: " + r.retryAmplification);
        assertTrue(r.proxyDeltas.keySet().stream().anyMatch(k -> k.startsWith("routeplane_retries_total")), r.proxyDeltas.keySet().toString());
        assertTrue(r.breakerTransitions.stream().anyMatch(t -> t.upstream.equals("a") && t.to.equals("open")), "the breaker of a opened during the outage: " + r.breakerTransitions.size());
        assertTrue(r.recoverySeconds != null || r.recoveryNote != null);
    }
}
