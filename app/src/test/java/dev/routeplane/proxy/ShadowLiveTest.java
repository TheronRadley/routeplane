package dev.routeplane.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Shadowing must be opt-in, bounded, sanitised, and invisible to the primary response. */
@Timeout(240)
class ShadowLiveTest {
    private static final HttpClient CLIENT = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    private static HttpResponse<String> get(int port, String path, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        for (int i = 0; i < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        return CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String route(String shadowBlock) {
        return "routes:\n  - id: api\n    match: {pathPrefix: /api}\n    upstreams: [{id: p, url: \"http://127.0.0.1:${UP_P}\"}]\n"
                + "    timeouts: {connect: 500ms, response: 3s, request: 10s}\n    shadow:\n" + shadowBlock;
    }

    private static String target(String extra) {
        return "      targets:\n        - {id: s1, url: \"http://127.0.0.1:${UP_S}\", " + extra + "}\n";
    }

    @Test
    void mirrorsEverySampledRequestWithSanitisedHeadersAndLeavesThePrimaryUntouched(@TempDir Path dir) throws Exception {
        try (ProxyHarness h = new ProxyHarness(dir)) {
            FaultUpstream primary = h.upstream("p");
            FaultUpstream shadow = h.upstream("s");
            h.start(route(target("samplePercent: 100")));
            HttpResponse<String> r = get(h.port(), "/api/echo?x=1",
                    "Authorization", "Bearer secret-token", "Cookie", "session=abc", "X-Api-Key", "k-123", "X-Auth-Token", "t", "X-Custom", "keep-me", "X-Request-Id", "trace-77");
            assertEquals(200, r.statusCode());
            assertTrue(ProxyHarness.eventually(Duration.ofSeconds(5), () -> shadow.stats().requests() == 1), "the shadow target received the request");
            var seen = shadow.recorded().get(0);
            assertEquals("/api/echo?x=1", seen.target());
            for (String gone : new String[] {"Authorization", "Cookie", "X-Api-Key", "X-Auth-Token"}) {
                assertNull(seen.header(gone), gone + " must be removed from shadow traffic");
            }
            assertEquals("keep-me", seen.header("X-Custom"));
            assertEquals("1", seen.header("X-Routeplane-Shadow"), "shadow traffic is identifiable");
            assertEquals("trace-77", seen.header("X-Request-Id"), "correlation id links primary and shadow");
            // the primary still received the credentials: sanitising applies to the shadow copy only
            assertEquals("Bearer secret-token", primary.recorded().get(0).header("Authorization"));
            assertEquals(1.0, h.metric("routeplane_shadow_sampled_total{route=\"api\",target=\"s1\"}"));
            assertTrue(ProxyHarness.eventually(Duration.ofSeconds(5), () -> h.metric("routeplane_shadow_requests_total{route=\"api\",target=\"s1\"}") == 1.0));
        }
    }

    @Test
    void anAllowListReplacesTheStripListAndConfiguredHeadersAreAdded(@TempDir Path dir) throws Exception {
        try (ProxyHarness h = new ProxyHarness(dir)) {
            h.upstream("p");
            FaultUpstream shadow = h.upstream("s");
            h.start(route(target("samplePercent: 100") + "      headers:\n        allow: [x-custom, user-agent]\n        set: {X-Shadow-Env: staging}\n"));
            get(h.port(), "/api/x", "X-Custom", "v", "X-Other", "dropped", "Authorization", "nope");
            assertTrue(ProxyHarness.eventually(Duration.ofSeconds(5), () -> shadow.stats().requests() == 1));
            var seen = shadow.recorded().get(0);
            assertEquals("v", seen.header("X-Custom"));
            assertNull(seen.header("X-Other"), "not on the allow list");
            assertNull(seen.header("Authorization"));
            assertEquals("staging", seen.header("X-Shadow-Env"));
            assertEquals("1", seen.header("X-Routeplane-Shadow"));
        }
    }

    @Test
    void randomSamplingApproximatesThePercentage(@TempDir Path dir) throws Exception {
        try (ProxyHarness h = new ProxyHarness(dir)) {
            h.upstream("p");
            FaultUpstream shadow = h.upstream("s");
            h.start(route(target("samplePercent: 20")));
            for (int i = 0; i < 1000; i++) {
                assertEquals(200, get(h.port(), "/api/x").statusCode());
            }
            assertTrue(ProxyHarness.eventually(Duration.ofSeconds(10), () -> shadow.stats().requests() >= 150));
            Thread.sleep(300);
            long n = shadow.stats().requests();
            assertTrue(n >= 150 && n <= 250, "20% of 1000 requests, got " + n);
        }
    }

    @Test
    void deterministicSamplingDecidesPerKeyAndIsStable(@TempDir Path dir) throws Exception {
        try (ProxyHarness h = new ProxyHarness(dir)) {
            h.upstream("p");
            FaultUpstream shadow = h.upstream("s");
            h.start(route(target("samplePercent: 30, sampling: deterministic, sampleKey: \"header:X-User\"")));
            for (int round = 0; round < 2; round++) {
                for (int u = 0; u < 300; u++) {
                    get(h.port(), "/api/x", "X-User", "user-" + u);
                }
            }
            Thread.sleep(800);
            Map<String, Integer> perUser = new HashMap<>();
            for (var rec : shadow.recorded()) {
                perUser.merge(rec.header("X-User"), 1, Integer::sum);
            }
            for (var e : perUser.entrySet()) {
                assertEquals(2, e.getValue(), e.getKey() + " is either always or never sampled");
            }
            double share = perUser.size() / 300.0;
            assertTrue(share > 0.2 && share < 0.4, "about 30% of the keys were sampled: " + share);
            // requests without the key cannot be decided deterministically and are skipped, not guessed
            get(h.port(), "/api/x");
            assertTrue(ProxyHarness.eventually(Duration.ofSeconds(3), () -> h.metric("routeplane_shadow_skipped_total{route=\"api\",target=\"s1\",reason=\"no_sample_key\"}") == 1.0));
        }
    }

    @Test
    void writesAreNotMirroredUnlessExplicitlyEnabledAndTheirBodiesAreReplayed(@TempDir Path dir) throws Exception {
        try (ProxyHarness h = new ProxyHarness(dir)) {
            FaultUpstream primary = h.upstream("p");
            FaultUpstream shadow = h.upstream("s");
            h.start(route(target("samplePercent: 100")));
            try (RawClient c = h.raw()) {
                assertEquals(200, c.request("POST", "/api/echo", null, "0123456789".getBytes()).status());
            }
            Thread.sleep(300);
            assertEquals(0, shadow.stats().requests(), "POST is not mirrored by default");
            assertEquals(1.0, h.metric("routeplane_shadow_skipped_total{route=\"api\",target=\"s1\",reason=\"method\"}"));

            h.rewrite(route(target("samplePercent: 100") + "      methods: [GET, POST]\n      allowUnsafeMethods: true\n"));
            assertTrue(h.app().configs().reload("test").success());
            try (RawClient c = h.raw()) {
                assertEquals(200, c.request("POST", "/api/echo", null, "0123456789".getBytes()).status());
            }
            assertTrue(ProxyHarness.eventually(Duration.ofSeconds(5), () -> shadow.stats().requests() == 1));
            assertEquals(10, shadow.recorded().get(0).bodyBytes(), "the buffered body was replayed to the shadow");
            assertEquals("0123456789", shadow.recorded().get(0).bodyPrefix());
            assertEquals(2, primary.stats().requests());
        }
    }

    @Test
    void oversizedAndStreamedBodiesAreSkippedWithoutAffectingThePrimary(@TempDir Path dir) throws Exception {
        try (ProxyHarness h = new ProxyHarness(dir)) {
            FaultUpstream primary = h.upstream("p");
            FaultUpstream shadow = h.upstream("s");
            h.start("server: {host: 127.0.0.1, port: 0, maxBufferedBodyBytes: 2048}\nadmin: {host: 127.0.0.1, port: 0}\n"
                    + route(target("samplePercent: 100") + "      methods: [POST]\n      allowUnsafeMethods: true\n      maxBodyBytes: 100\n"));
            try (RawClient c = h.raw()) {
                assertEquals(200, c.request("POST", "/api/echo", null, new byte[500]).status());
                assertEquals(200, c.request("POST", "/api/echo", null, new byte[50]).status());
                c.send("POST /api/echo HTTP/1.1\r\nHost: t\r\nTransfer-Encoding: chunked\r\n\r\n");
                byte[] chunk = new byte[1000];
                for (int i = 0; i < 5; i++) {
                    c.send(Integer.toHexString(chunk.length) + "\r\n");
                    c.send(chunk);
                    c.send("\r\n");
                }
                c.send("0\r\n\r\n");
                assertEquals(200, c.read(false).status());
            }
            assertTrue(ProxyHarness.eventually(Duration.ofSeconds(5), () -> shadow.stats().requests() == 1), "only the 50-byte body was mirrored");
            assertEquals(1.0, h.metric("routeplane_shadow_skipped_total{route=\"api\",target=\"s1\",reason=\"body_too_large\"}"));
            assertEquals(1.0, h.metric("routeplane_shadow_skipped_total{route=\"api\",target=\"s1\",reason=\"body_not_replayable\"}"));
            assertEquals(3, primary.stats().requests());
        }
    }

    @Test
    void aDeadShadowTargetNeverAffectsThePrimary(@TempDir Path dir) throws Exception {
        try (ProxyHarness h = new ProxyHarness(dir)) {
            FaultUpstream primary = h.upstream("p");
            FaultUpstream shadow = h.upstream("s");
            h.start(route(target("samplePercent: 100")));
            shadow.setProfile(shadow.profile().with("refuse", true));
            long worst = 0;
            for (int i = 0; i < 100; i++) {
                long t0 = System.nanoTime();
                HttpResponse<String> r = get(h.port(), "/api/x");
                worst = Math.max(worst, (System.nanoTime() - t0) / 1_000_000);
                assertEquals(200, r.statusCode());
                assertEquals("p", r.headers().firstValue("x-upstream-id").orElse(""));
            }
            assertTrue(worst < 1500, "worst primary latency with a dead shadow: " + worst + " ms");
            assertEquals(100, primary.stats().requests());
            assertTrue(ProxyHarness.eventually(Duration.ofSeconds(10), () -> h.metric("routeplane_shadow_failures_total{route=\"api\",target=\"s1\",kind=\"connect_failed\"}") >= 50.0));
        }
    }

    @Test
    void aSlowShadowTargetIsBoundedByItsQueueWorkersAndTimeoutsWhilePrimaryStaysFast(@TempDir Path dir) throws Exception {
        try (ProxyHarness h = new ProxyHarness(dir)) {
            h.upstream("p");
            h.upstream("s", FaultProfile.DEFAULT.with("delayMs", 2000));
            h.start(route("      targets:\n        - {id: s1, url: \"http://127.0.0.1:${UP_S}\", samplePercent: 100, timeout: 300ms, maxConcurrent: 2, queueCapacity: 4, queueMaxWait: 10s}\n"));
            long worst = 0;
            int maxDepth = 0;
            int maxInFlight = 0;
            for (int i = 0; i < 80; i++) {
                long t0 = System.nanoTime();
                assertEquals(200, get(h.port(), "/api/x").statusCode());
                worst = Math.max(worst, (System.nanoTime() - t0) / 1_000_000);
                if (i % 10 == 0) {
                    maxDepth = Math.max(maxDepth, (int) h.metric("routeplane_shadow_queue_depth{route=\"api\",target=\"s1\"}"));
                    maxInFlight = Math.max(maxInFlight, (int) h.metric("routeplane_shadow_in_flight{route=\"api\",target=\"s1\"}"));
                }
            }
            assertTrue(worst < 1000, "primary latency is independent of the shadow: " + worst + " ms");
            assertTrue(maxDepth <= 4, "queue depth never exceeds its capacity: " + maxDepth);
            assertTrue(maxInFlight <= 2, "never more shadow requests in flight than workers: " + maxInFlight);
            assertTrue(h.metric("routeplane_shadow_dropped_total{route=\"api\",target=\"s1\",reason=\"queue_full\"}") > 0, "overflow is dropped explicitly");
            assertTrue(ProxyHarness.eventually(Duration.ofSeconds(15), () -> h.metric("routeplane_shadow_queue_depth{route=\"api\",target=\"s1\"}") == 0.0
                    && h.metric("routeplane_shadow_in_flight{route=\"api\",target=\"s1\"}") == 0.0), "everything drains");
            assertTrue(h.metric("routeplane_shadow_failures_total{route=\"api\",target=\"s1\",kind=\"upstream_timeout\"}") > 0, "shadow timeouts are enforced");
        }
    }

    @Test
    void shadowErrorsAndGarbageNeverChangeThePrimaryResponse(@TempDir Path dir) throws Exception {
        try (ProxyHarness h = new ProxyHarness(dir)) {
            h.upstream("p");
            FaultUpstream shadow = h.upstream("s");
            h.start(route(target("samplePercent: 100")));
            String baseline = get(h.port(), "/api/big?bytes=3000").body();
            assertEquals(3000, baseline.length());
            for (FaultProfile bad : List.of(FaultProfile.DEFAULT.with("errorRate", 1.0).with("errorStatus", 500),
                    FaultProfile.DEFAULT.with("partialRate", 1.0).with("payloadMin", 1000).with("payloadMax", 1000),
                    FaultProfile.DEFAULT.with("resetRate", 1.0), FaultProfile.DEFAULT.with("closeRate", 1.0))) {
                shadow.setProfile(bad);
                for (int i = 0; i < 10; i++) {
                    HttpResponse<String> r = get(h.port(), "/api/big?bytes=3000");
                    assertEquals(200, r.statusCode());
                    assertEquals(baseline, r.body());
                    assertEquals("p", r.headers().firstValue("x-upstream-id").orElse(""));
                }
            }
            assertTrue(ProxyHarness.eventually(Duration.ofSeconds(10), () -> h.metrics().contains("routeplane_shadow_failures_total{route=\"api\",target=\"s1\",kind=\"status_5xx\"}")));
        }
    }

    @Test
    void comparisonReportsMatchesAndMismatches(@TempDir Path dir) throws Exception {
        try (ProxyHarness h = new ProxyHarness(dir)) {
            h.upstream("p", FaultProfile.DEFAULT.with("payloadMin", 300).with("payloadMax", 300));
            FaultUpstream shadow = h.upstream("s", FaultProfile.DEFAULT.with("payloadMin", 300).with("payloadMax", 300));
            h.start(route(target("samplePercent: 100") + "      compare: {enabled: true, body: true, headers: [content-type]}\n"));
            for (int i = 0; i < 5; i++) {
                get(h.port(), "/api/x");
            }
            assertTrue(ProxyHarness.eventually(Duration.ofSeconds(10), () -> h.metric("routeplane_shadow_comparisons_total{route=\"api\",target=\"s1\",result=\"match\"}") == 5.0), h.metrics());
            shadow.setProfile(shadow.profile().with("payloadMin", 400).with("payloadMax", 400));
            for (int i = 0; i < 3; i++) {
                get(h.port(), "/api/x");
            }
            assertTrue(ProxyHarness.eventually(Duration.ofSeconds(10), () -> h.metric("routeplane_shadow_comparisons_total{route=\"api\",target=\"s1\",result=\"body_mismatch\"}") == 3.0));
            shadow.setProfile(FaultProfile.DEFAULT.with("errorRate", 1.0).with("errorStatus", 500));
            for (int i = 0; i < 2; i++) {
                get(h.port(), "/api/x");
            }
            assertTrue(ProxyHarness.eventually(Duration.ofSeconds(10), () -> h.metric("routeplane_shadow_comparisons_total{route=\"api\",target=\"s1\",result=\"status_mismatch\"}") == 2.0));
            assertTrue(h.logs().contains("shadow_mismatch"));
            assertFalse(h.logs().contains("abcdefghijklmnopqrstuvwxyz"), "mismatch logs never contain payload bytes");
        }
    }

    @Test
    void routesWithoutAShadowSectionNeverMirrorAnything(@TempDir Path dir) throws Exception {
        try (ProxyHarness h = new ProxyHarness(dir)) {
            h.upstream("p");
            FaultUpstream bystander = h.upstream("s");
            h.start("routes:\n  - id: api\n    match: {pathPrefix: /api}\n    upstreams: [{id: p, url: \"http://127.0.0.1:${UP_P}\"}]\n");
            for (int i = 0; i < 20; i++) {
                get(h.port(), "/api/x");
            }
            Thread.sleep(200);
            assertEquals(0, bystander.stats().requests());
        }
    }

    @Test
    void severalTargetsSampleIndependently(@TempDir Path dir) throws Exception {
        try (ProxyHarness h = new ProxyHarness(dir)) {
            h.upstream("p");
            FaultUpstream s1 = h.upstream("s");
            FaultUpstream s2 = h.upstream("t");
            h.start(route("      targets:\n        - {id: s1, url: \"http://127.0.0.1:${UP_S}\", samplePercent: 100}\n        - {id: s2, url: \"http://127.0.0.1:${UP_T}\", samplePercent: 0}\n"));
            for (int i = 0; i < 20; i++) {
                get(h.port(), "/api/x");
            }
            assertTrue(ProxyHarness.eventually(Duration.ofSeconds(5), () -> s1.stats().requests() == 20));
            assertEquals(0, s2.stats().requests());
            Set<String> ids = new HashSet<>();
            s1.recorded().forEach(r -> ids.add(r.header("X-Request-Id")));
            assertEquals(20, ids.size());
        }
    }
}
