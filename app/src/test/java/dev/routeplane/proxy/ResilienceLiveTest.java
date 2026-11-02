package dev.routeplane.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.routeplane.fault.FaultProfile;
import dev.routeplane.fault.FaultUpstream;
import dev.routeplane.testing.ProxyHarness;
import dev.routeplane.testing.RawClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Retries, budgets, circuit breakers, limits, health and fallbacks, observed through real traffic. */
@Timeout(240)
class ResilienceLiveTest {
    private static final HttpClient CLIENT = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    private static final FaultProfile ALWAYS_503 = FaultProfile.DEFAULT.with("errorRate", 1.0).with("errorStatus", 503);
    private static final FaultProfile ALWAYS_500 = FaultProfile.DEFAULT.with("errorRate", 1.0).with("errorStatus", 500);

    private static HttpResponse<String> get(int port, String path) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String who(HttpResponse<String> r) {
        return r.headers().firstValue("x-upstream-id").orElse("none");
    }

    private static final String FAST_RETRY = "    retry: {maxAttempts: 2, backoff: {initial: 1ms, max: 2ms, jitter: none}, budget: {ratio: 1.0, minRetriesPerSecond: 1000}}\n";

    private static String twoUpstreams(String policy, String extra) {
        return "routes:\n  - id: api\n    match: {pathPrefix: /api}\n    policy: " + policy + "\n"
                + "    upstreams:\n      - {id: a, url: \"http://127.0.0.1:${UP_A}\"}\n      - {id: b, url: \"http://127.0.0.1:${UP_B}\"}\n" + extra;
    }

    // ------------------------------------------------------------------------------------------- retries

    @Test
    void retriesFailedStatusesOnAnotherUpstreamAndExposesTheAmplification(@TempDir Path dir) throws Exception {
        try (ProxyHarness h = new ProxyHarness(dir)) {
            FaultUpstream a = h.upstream("a", ALWAYS_503);
            FaultUpstream b = h.upstream("b");
            h.start(twoUpstreams("round-robin", FAST_RETRY));
            for (int i = 0; i < 20; i++) {
                HttpResponse<String> r = get(h.port(), "/api/x");
                assertEquals(200, r.statusCode(), "request " + i);
                assertEquals("b", who(r));
            }
            assertEquals(10, a.stats().requests());
            assertEquals(20, b.stats().requests());
            assertEquals(10.0, h.metric("routeplane_retries_total{route=\"api\",reason=\"status\"}"));
            assertEquals(10.0, h.metric("routeplane_upstream_requests_total{route=\"api\",upstream=\"b\",attempt=\"retry\"}"));
            double downstream = h.metric("routeplane_requests_total{route=\"api\",status_class=\"2xx\"}");
            double upstreamTotal = h.metric("routeplane_upstream_requests_total{route=\"api\",upstream=\"a\",attempt=\"first\"}")
                    + h.metric("routeplane_upstream_requests_total{route=\"api\",upstream=\"b\",attempt=\"first\"}")
                    + h.metric("routeplane_upstream_requests_total{route=\"api\",upstream=\"b\",attempt=\"retry\"}");
            assertEquals(1.5, upstreamTotal / downstream, 1e-9, "retry amplification = upstream attempts per downstream request");
        }
    }

    @Test
    void retriesConnectFailuresResetsAndTimeoutsForIdempotentRequests(@TempDir Path dir) throws Exception {
        try (ProxyHarness h = new ProxyHarness(dir)) {
            FaultUpstream a = h.upstream("a");
            h.upstream("b");
            h.start(twoUpstreams("round-robin", "    timeouts: {connect: 500ms, response: 300ms, request: 5s}\n" + FAST_RETRY));
            a.setProfile(a.profile().with("refuse", true));
            for (int i = 0; i < 4; i++) {
                assertEquals("b", who(get(h.port(), "/api/x")));
            }
            assertTrue(h.metric("routeplane_retries_total{route=\"api\",reason=\"connect_failed\"}") >= 1);
            a.setProfile(FaultProfile.DEFAULT.with("resetRate", 1.0));
            for (int i = 0; i < 4; i++) {
                assertEquals("b", who(get(h.port(), "/api/x")));
            }
            assertTrue(h.metric("routeplane_retries_total{route=\"api\",reason=\"reset\"}") >= 1);
            a.setProfile(FaultProfile.DEFAULT.with("delayMs", 1500));
            long t0 = System.nanoTime();
            for (int i = 0; i < 4; i++) {
                assertEquals("b", who(get(h.port(), "/api/x")));
            }
            assertTrue(h.metric("routeplane_retries_total{route=\"api\",reason=\"timeout\"}") >= 1);
            assertTrue((System.nanoTime() - t0) / 1_000_000 < 2500, "timeouts were retried quickly, not waited out");
        }
    }

    @Test
    void nonIdempotentMethodsAreNotRetriedByDefault(@TempDir Path dir) throws Exception {
        try (ProxyHarness h = new ProxyHarness(dir)) {
            FaultUpstream a = h.upstream("a", ALWAYS_503);
            FaultUpstream b = h.upstream("b");
            h.start(twoUpstreams("round-robin", FAST_RETRY));
            try (RawClient c = h.raw()) {
                RawClient.Response r = c.request("POST", "/api/x", null, "payload".getBytes());
                assertEquals(503, r.status(), "the failing upstream's own answer is returned; POST is not replayed");
                assertEquals("a", r.header("x-upstream-id"));
            }
            assertEquals(1, a.stats().requests());
            assertEquals(0, b.stats().requests());
            assertEquals(1.0, h.metric("routeplane_retry_skipped_total{route=\"api\",reason=\"method_not_retryable\"}"));
        }
    }

    @Test
    void explicitlyListedMethodsAreRetriedWithTheBufferedBodyReplayed(@TempDir Path dir) throws Exception {
        try (ProxyHarness h = new ProxyHarness(dir)) {
            FaultUpstream a = h.upstream("a", ALWAYS_503);
            FaultUpstream b = h.upstream("b");
            h.start(twoUpstreams("round-robin", "    retry: {maxAttempts: 2, methods: [GET, POST], backoff: {initial: 1ms, max: 2ms, jitter: none}, budget: {ratio: 1.0, minRetriesPerSecond: 100}}\n"));
            try (RawClient c = h.raw()) {
                RawClient.Response r = c.request("POST", "/api/echo", null, "0123456789".getBytes());
                assertEquals(200, r.status());
                assertEquals("b", r.header("x-upstream-id"));
                assertEquals(10, JsonParser.parseString(r.text()).getAsJsonObject().get("bodyBytes").getAsInt());
            }
            assertEquals(1, a.stats().requests());
            assertEquals(10, b.recorded().get(0).bodyBytes(), "the whole body reached the second upstream");
        }
    }

    @Test
    void bodiesThatCannotBeReplayedAreNeverRetried(@TempDir Path dir) throws Exception {
        try (ProxyHarness h = new ProxyHarness(dir)) {
            FaultUpstream a = h.upstream("a", ALWAYS_503);
            FaultUpstream b = h.upstream("b");
            h.start("server: {host: 127.0.0.1, port: 0, maxBufferedBodyBytes: 1024}\nadmin: {host: 127.0.0.1, port: 0}\n"
                    + twoUpstreams("round-robin", "    retry: {maxAttempts: 3, methods: [POST], backoff: {initial: 1ms, max: 2ms}}\n"));
            try (RawClient c = h.raw()) {
                RawClient.Response r = c.request("POST", "/api/x", null, new byte[5000]);
                assertEquals(503, r.status());
            }
            assertEquals(1, a.stats().requests() + b.stats().requests(), "exactly one attempt: the 5000-byte body was streamed and is gone");
            assertEquals(1.0, h.metric("routeplane_retry_skipped_total{route=\"api\",reason=\"body_not_replayable\"}"));
        }
    }

    @Test
    void theRetryBudgetCapsAmplificationUnderAnOutage(@TempDir Path dir) throws Exception {
        try (ProxyHarness h = new ProxyHarness(dir)) {
            FaultUpstream a = h.upstream("a", ALWAYS_503);
            h.start("""
                    routes:
                      - id: api
                        match: {pathPrefix: /api}
                        upstreams: [{id: a, url: "http://127.0.0.1:${UP_A}"}]
                        retry: {maxAttempts: 3, backoff: {initial: 1ms, max: 2ms, jitter: none}, budget: {ratio: 0.1, minRetriesPerSecond: 1, window: 5s}}
                    """);
            int requests = 100;
            for (int i = 0; i < requests; i++) {
                assertEquals(503, get(h.port(), "/api/x").statusCode());
            }
            long upstreamRequests = a.stats().requests();
            assertTrue(upstreamRequests >= requests, "at least one attempt per request");
            assertTrue(upstreamRequests <= requests + 12, "amplification stays near 1.1x instead of 3x: " + upstreamRequests);
            assertTrue(h.metric("routeplane_retry_budget_exhausted_total{route=\"api\"}") > 0);
        }
    }

    @Test
    void retriesNeverOutliveTheRequestDeadline(@TempDir Path dir) throws Exception {
        try (ProxyHarness h = new ProxyHarness(dir)) {
            FaultUpstream a = h.upstream("a", ALWAYS_503);
            h.start("""
                    routes:
                      - id: api
                        match: {pathPrefix: /api}
                        upstreams: [{id: a, url: "http://127.0.0.1:${UP_A}"}]
                        timeouts: {connect: 100ms, response: 500ms, request: 500ms}
                        retry: {maxAttempts: 5, backoff: {initial: 400ms, max: 5s, multiplier: 2, jitter: none}, budget: {ratio: 1.0, minRetriesPerSecond: 100}}
                    """);
            long t0 = System.nanoTime();
            HttpResponse<String> r = get(h.port(), "/api/x");
            long ms = (System.nanoTime() - t0) / 1_000_000;
            assertEquals(503, r.statusCode());
            assertTrue(ms < 800, "the 5-attempt policy was cut short by the 500 ms deadline: " + ms + " ms");
            assertEquals(2, a.stats().requests(), "second attempt fitted; a third would have exceeded the deadline");
            assertTrue(h.metric("routeplane_retry_skipped_total{route=\"api\",reason=\"deadline\"}") >= 1);
        }
    }

    @Test
    void backoffDelaysTheRetry(@TempDir Path dir) throws Exception {
        try (ProxyHarness h = new ProxyHarness(dir)) {
            h.upstream("a", ALWAYS_503);
            h.upstream("b");
            h.start(twoUpstreams("round-robin", "    retry: {maxAttempts: 2, backoff: {initial: 250ms, max: 250ms, jitter: none}, budget: {ratio: 1.0, minRetriesPerSecond: 100}}\n"));
            long t0 = System.nanoTime();
            assertEquals(200, get(h.port(), "/api/x").statusCode());
            long ms = (System.nanoTime() - t0) / 1_000_000;
            assertTrue(ms >= 240 && ms < 1500, "first request went to the failing upstream and waited out the backoff: " + ms + " ms");
        }
    }

    // ------------------------------------------------------------------------------------- circuit breaker

    private static final String BREAKER = "    circuitBreaker: {failureRateThreshold: 50, minimumRequests: 5, slidingWindow: {type: count, size: 10}, openDuration: 1s, halfOpenMaxProbes: 2, recoverySuccessThreshold: 2}\n";

    @Test
    void breakerOpensRejectsFastProbesAndClosesAgain(@TempDir Path dir) throws Exception {
        try (ProxyHarness h = new ProxyHarness(dir)) {
            FaultUpstream a = h.upstream("a", ALWAYS_500);
            h.start("routes:\n  - id: api\n    match: {pathPrefix: /api}\n    upstreams: [{id: a, url: \"http://127.0.0.1:${UP_A}\"}]\n" + BREAKER);
            for (int i = 0; i < 5; i++) {
                HttpResponse<String> r = get(h.port(), "/api/x");
                assertEquals(500, r.statusCode(), "upstream errors pass through while the breaker is closed");
                assertEquals(false, r.headers().firstValue("x-routeplane-error").isPresent());
            }
            assertEquals(5, a.stats().requests());
            long t0 = System.nanoTime();
            HttpResponse<String> rejected = get(h.port(), "/api/x");
            assertEquals(503, rejected.statusCode());
            assertEquals("breaker_open", rejected.headers().firstValue("x-routeplane-error").orElse(""));
            assertEquals("1", rejected.headers().firstValue("retry-after").orElse(""));
            assertTrue((System.nanoTime() - t0) / 1_000_000 < 200);
            for (int i = 0; i < 10; i++) {
                assertEquals(503, get(h.port(), "/api/x").statusCode());
            }
            assertEquals(5, a.stats().requests(), "an open breaker keeps all traffic away from the upstream");
            assertEquals(1.0, h.metric("routeplane_circuit_breaker_state{route=\"api\",upstream=\"a\"}"));

            Thread.sleep(1100);
            a.setProfile(FaultProfile.DEFAULT);
            assertEquals(200, get(h.port(), "/api/x").statusCode(), "first half-open probe");
            assertEquals(200, get(h.port(), "/api/x").statusCode(), "second half-open probe closes the breaker");
            assertEquals(0.0, h.metric("routeplane_circuit_breaker_state{route=\"api\",upstream=\"a\"}"));
            assertEquals(200, get(h.port(), "/api/x").statusCode());

            assertEquals(1.0, h.metric("routeplane_circuit_breaker_transitions_total{route=\"api\",upstream=\"a\",from=\"closed\",to=\"open\"}"));
            assertEquals(1.0, h.metric("routeplane_circuit_breaker_transitions_total{route=\"api\",upstream=\"a\",from=\"open\",to=\"half_open\"}"));
            assertEquals(1.0, h.metric("routeplane_circuit_breaker_transitions_total{route=\"api\",upstream=\"a\",from=\"half_open\",to=\"closed\"}"));
            assertTrue(h.metric("routeplane_circuit_breaker_rejections_total{route=\"api\",upstream=\"a\"}") >= 11);
            JsonArray circuits = JsonParser.parseString(h.admin("GET", "/admin/circuits").body()).getAsJsonArray();
            JsonObject c = circuits.get(0).getAsJsonObject();
            assertEquals("closed", c.get("state").getAsString());
            List<String> reasons = new ArrayList<>();
            c.getAsJsonArray("recentTransitions").forEach(t -> reasons.add(t.getAsJsonObject().get("reason").getAsString()));
            assertEquals(List.of("failure_rate_exceeded", "open_duration_elapsed", "recovered"), reasons);
        }
    }

    @Test
    void aFailedProbeReopensTheBreaker(@TempDir Path dir) throws Exception {
        try (ProxyHarness h = new ProxyHarness(dir)) {
            FaultUpstream a = h.upstream("a", ALWAYS_500);
            h.start("routes:\n  - id: api\n    match: {pathPrefix: /api}\n    upstreams: [{id: a, url: \"http://127.0.0.1:${UP_A}\"}]\n" + BREAKER);
            for (int i = 0; i < 5; i++) {
                get(h.port(), "/api/x");
            }
            Thread.sleep(1100);
            assertEquals(500, get(h.port(), "/api/x").statusCode(), "the probe reaches the still-broken upstream");
            assertEquals(503, get(h.port(), "/api/x").statusCode(), "and the breaker is open again");
            assertEquals(6, a.stats().requests());
            assertEquals(2.0, h.metric("routeplane_circuit_breaker_transitions_total{route=\"api\",upstream=\"a\",from=\"closed\",to=\"open\"}") + h.metric("routeplane_circuit_breaker_transitions_total{route=\"api\",upstream=\"a\",from=\"half_open\",to=\"open\"}"));
        }
    }

    @Test
    void breakersAreIsolatedPerUpstream(@TempDir Path dir) throws Exception {
        try (ProxyHarness h = new ProxyHarness(dir)) {
            FaultUpstream a = h.upstream("a", ALWAYS_500);
            FaultUpstream b = h.upstream("b");
            h.start(twoUpstreams("round-robin", BREAKER));
            int failures = 0;
            for (int i = 0; i < 60; i++) {
                HttpResponse<String> r = get(h.port(), "/api/x");
                if (r.statusCode() != 200) {
                    failures++;
                }
            }
            assertEquals(5, failures, "only the five failures that opened a's breaker; b was never affected");
            assertEquals(5, a.stats().requests());
            assertEquals(55, b.stats().requests());
            assertEquals("open", JsonParser.parseString(h.admin("GET", "/admin/upstreams").body()).getAsJsonArray().get(0).getAsJsonObject().get("circuit").getAsString());
        }
    }

    @Test
    void onlyConfiguredStatusesCountAsBreakerFailures(@TempDir Path dir) throws Exception {
        try (ProxyHarness h = new ProxyHarness(dir)) {
            FaultUpstream a = h.upstream("a", FaultProfile.DEFAULT.with("errorRate", 1.0).with("errorStatus", 404));
            h.start("routes:\n  - id: api\n    match: {pathPrefix: /api}\n    upstreams: [{id: a, url: \"http://127.0.0.1:${UP_A}\"}]\n" + BREAKER);
            for (int i = 0; i < 40; i++) {
                assertEquals(404, get(h.port(), "/api/x").statusCode());
            }
            assertEquals(0.0, h.metric("routeplane_circuit_breaker_state{route=\"api\",upstream=\"a\"}"), "4xx are not failures by default");
            a.setProfile(FaultProfile.DEFAULT.with("errorRate", 1.0).with("errorStatus", 503));
            h.rewrite("routes:\n  - id: api\n    match: {pathPrefix: /api}\n    upstreams: [{id: a, url: \"http://127.0.0.1:${UP_A}\"}]\n"
                    + "    circuitBreaker: {minimumRequests: 5, slidingWindow: {type: count, size: 10}, failureStatuses: [500]}\n");
            assertTrue(h.app().configs().reload("test").success());
            for (int i = 0; i < 40; i++) {
                assertEquals(503, get(h.port(), "/api/x").statusCode());
            }
            assertEquals(0.0, h.metric("routeplane_circuit_breaker_state{route=\"api\",upstream=\"a\"}"), "503 is not in failureStatuses: [500]");
        }
    }

    // -------------------------------------------------------------------------- limits, health, fallbacks

    @Test
    void perUpstreamConcurrencyLimitsRejectOverflowImmediately(@TempDir Path dir) throws Exception {
        try (ProxyHarness h = new ProxyHarness(dir)) {
            h.upstream("a", FaultProfile.DEFAULT.with("delayMs", 600));
            h.start("routes:\n  - id: api\n    match: {pathPrefix: /api}\n    upstreams: [{id: a, url: \"http://127.0.0.1:${UP_A}\", maxConcurrent: 2}]\n");
            ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor();
            List<CompletableFuture<HttpResponse<String>>> fs = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                fs.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        return get(h.port(), "/api/x");
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }, ex));
            }
            Thread.sleep(300);
            assertEquals(1.0, h.metric("routeplane_upstream_pool_utilization_ratio{route=\"api\",upstream=\"a\"}"), "pool utilisation is visible while busy");
            int ok = 0;
            int limited = 0;
            for (var f : fs) {
                HttpResponse<String> r = f.get();
                if (r.statusCode() == 200) {
                    ok++;
                } else {
                    assertEquals(503, r.statusCode());
                    assertEquals("concurrency_limit", r.headers().firstValue("x-routeplane-error").orElse(""));
                    limited++;
                }
            }
            ex.shutdown();
            assertEquals(2, ok);
            assertEquals(4, limited);
        }
    }

    @Test
    void passiveEjectionStopsSendingTrafficToAFailingUpstream(@TempDir Path dir) throws Exception {
        try (ProxyHarness h = new ProxyHarness(dir)) {
            FaultUpstream a = h.upstream("a", ALWAYS_500);
            FaultUpstream b = h.upstream("b");
            h.start(twoUpstreams("round-robin", "    health: {passive: {consecutiveFailures: 3, ejectionTime: 30s}}\n"));
            int failures = 0;
            for (int i = 0; i < 30; i++) {
                if (get(h.port(), "/api/x").statusCode() != 200) {
                    failures++;
                }
            }
            assertEquals(3, failures);
            assertEquals(3, a.stats().requests(), "a was ejected after three consecutive failures");
            assertEquals(27, b.stats().requests());
            assertEquals(1.0, h.metric("routeplane_upstream_ejections_total{route=\"api\",upstream=\"a\"}"));
            assertEquals(1.0, h.metric("routeplane_upstream_ejected{route=\"api\",upstream=\"a\"}"));
        }
    }

    @Test
    void activeHealthChecksRemoveAndRestoreUpstreams(@TempDir Path dir) throws Exception {
        try (ProxyHarness h = new ProxyHarness(dir)) {
            FaultUpstream a = h.upstream("a");
            FaultUpstream b = h.upstream("b");
            h.start(twoUpstreams("round-robin", "    health: {active: {path: /health, interval: 200ms, timeout: 150ms, unhealthyThreshold: 2, healthyThreshold: 2}}\n"));
            a.setProfile(ALWAYS_500);
            assertTrue(ProxyHarness.eventually(Duration.ofSeconds(10), () -> h.metric("routeplane_upstream_healthy{route=\"api\",upstream=\"a\"}") == 0.0), "a is marked down by probes");
            long clientBefore = a.recorded().stream().filter(r -> r.target().startsWith("/api/x")).count();
            for (int i = 0; i < 20; i++) {
                HttpResponse<String> r = get(h.port(), "/api/x");
                assertEquals(200, r.statusCode());
                assertEquals("b", who(r));
            }
            long clientAfter = a.recorded().stream().filter(r -> r.target().startsWith("/api/x")).count();
            assertEquals(clientBefore, clientAfter, "no client traffic reached a while it was marked down (only health probes did)");
            a.setProfile(FaultProfile.DEFAULT);
            assertTrue(ProxyHarness.eventually(Duration.ofSeconds(10), () -> h.metric("routeplane_upstream_healthy{route=\"api\",upstream=\"a\"}") == 1.0), "a is restored once probes succeed");
            boolean sawA = false;
            for (int i = 0; i < 10; i++) {
                sawA |= who(get(h.port(), "/api/x")).equals("a");
            }
            assertTrue(sawA);
            assertTrue(h.metric("routeplane_health_checks_total{route=\"api\",upstream=\"a\",result=\"failed\"}") >= 2);
            assertTrue(b.stats().requests() > 0);
        }
    }

    @Test
    void whenNoUpstreamIsAvailableTheProxyFailsFastOrServesTheConfiguredFallback(@TempDir Path dir) throws Exception {
        try (ProxyHarness h = new ProxyHarness(dir)) {
            FaultUpstream a = h.upstream("a", ALWAYS_500);
            h.upstream("f");
            String primary = "routes:\n  - id: api\n    match: {pathPrefix: /api}\n    upstreams: [{id: a, url: \"http://127.0.0.1:${UP_A}\"}]\n"
                    + "    health: {passive: {consecutiveFailures: 1, ejectionTime: 30s}}\n";
            h.start(primary);
            assertEquals(500, get(h.port(), "/api/x").statusCode());
            HttpResponse<String> none = get(h.port(), "/api/x");
            assertEquals(503, none.statusCode());
            assertEquals("no_upstream", none.headers().firstValue("x-routeplane-error").orElse(""));
            assertEquals("1", none.headers().firstValue("retry-after").orElse(""));

            h.rewrite(primary + "    fallback:\n      response: {status: 503, body: \"under maintenance\", contentType: \"text/plain\"}\n");
            assertTrue(h.app().configs().reload("test").success());
            HttpResponse<String> staticResp = get(h.port(), "/api/x");
            assertEquals(503, staticResp.statusCode());
            assertEquals("under maintenance", staticResp.body());
            assertEquals("static", staticResp.headers().firstValue("x-routeplane-fallback").orElse(""));
            assertEquals(false, staticResp.headers().firstValue("x-routeplane-error").isPresent());

            h.rewrite(primary + "    fallback:\n      upstreams: [{id: f, url: \"http://127.0.0.1:${UP_F}\"}]\n");
            assertTrue(h.app().configs().reload("test").success());
            HttpResponse<String> viaFallback = get(h.port(), "/api/x");
            assertEquals(200, viaFallback.statusCode());
            assertEquals("f", who(viaFallback));
            assertTrue(h.metric("routeplane_fallback_total{route=\"api\",kind=\"upstream\"}") >= 1);
            assertTrue(a.stats().requests() >= 1);
        }
    }
}
