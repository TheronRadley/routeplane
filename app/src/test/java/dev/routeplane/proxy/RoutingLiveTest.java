package dev.routeplane.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Routing policies and route matching exercised with live traffic through the real proxy. */
@Timeout(180)
class RoutingLiveTest {
    private static final HttpClient CLIENT = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    private static HttpResponse<String> send(int port, String path, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        for (int i = 0; i < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        return CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String upstreamOf(HttpResponse<String> r) {
        return r.headers().firstValue("x-upstream-id").orElse("none");
    }

    @Test
    void weightedRoundRobinHonoursWeightsExactly(@TempDir Path dir) throws Exception {
        try (ProxyHarness h = new ProxyHarness(dir)) {
            FaultUpstream a = h.upstream("a");
            FaultUpstream b = h.upstream("b");
            FaultUpstream c = h.upstream("c");
            h.start("""
                    routes:
                      - id: api
                        match: {pathPrefix: /api}
                        policy: weighted-round-robin
                        upstreams:
                          - {id: a, url: "http://127.0.0.1:${UP_A}", weight: 70}
                          - {id: b, url: "http://127.0.0.1:${UP_B}", weight: 20}
                          - {id: c, url: "http://127.0.0.1:${UP_C}", weight: 10}
                    """);
            for (int i = 0; i < 1000; i++) {
                assertEquals(200, send(h.port(), "/api/x").statusCode());
            }
            assertEquals(700, a.stats().requests());
            assertEquals(200, b.stats().requests());
            assertEquals(100, c.stats().requests());
        }
    }

    @Test
    void roundRobinIgnoresWeightsAndCycles(@TempDir Path dir) throws Exception {
        try (ProxyHarness h = new ProxyHarness(dir)) {
            FaultUpstream a = h.upstream("a");
            FaultUpstream b = h.upstream("b");
            FaultUpstream c = h.upstream("c");
            h.start("""
                    routes:
                      - id: api
                        match: {pathPrefix: /api}
                        policy: round-robin
                        upstreams:
                          - {id: a, url: "http://127.0.0.1:${UP_A}", weight: 1}
                          - {id: b, url: "http://127.0.0.1:${UP_B}", weight: 50}
                          - {id: c, url: "http://127.0.0.1:${UP_C}", weight: 1}
                    """);
            StringBuilder seq = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                seq.append(upstreamOf(send(h.port(), "/api/x")));
            }
            assertEquals("abcabc", seq.toString());
            assertEquals(2, a.stats().requests());
            assertEquals(2, b.stats().requests());
            assertEquals(2, c.stats().requests());
        }
    }

    @Test
    void leastActiveSendsMoreTrafficToTheFasterUpstream(@TempDir Path dir) throws Exception {
        try (ProxyHarness h = new ProxyHarness(dir)) {
            FaultUpstream slow = h.upstream("a", FaultProfile.DEFAULT.with("delayMs", 250));
            FaultUpstream fast = h.upstream("b");
            h.start("""
                    routes:
                      - id: api
                        match: {pathPrefix: /api}
                        policy: least-active
                        upstreams:
                          - {id: a, url: "http://127.0.0.1:${UP_A}"}
                          - {id: b, url: "http://127.0.0.1:${UP_B}"}
                    """);
            ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor();
            List<Future<Integer>> fs = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                fs.add(ex.submit(() -> {
                    int ok = 0;
                    for (int j = 0; j < 12; j++) {
                        if (send(h.port(), "/api/x").statusCode() == 200) {
                            ok++;
                        }
                    }
                    return ok;
                }));
            }
            int total = 0;
            for (Future<Integer> f : fs) {
                total += f.get();
            }
            ex.shutdown();
            assertEquals(16 * 12, total);
            long fastN = fast.stats().requests();
            long slowN = slow.stats().requests();
            assertTrue(fastN > slowN * 2, "least-active should favour the quick upstream: fast=" + fastN + " slow=" + slowN);
        }
    }

    @Test
    void hashPolicyPinsEachKeyToOneUpstream(@TempDir Path dir) throws Exception {
        try (ProxyHarness h = new ProxyHarness(dir)) {
            h.upstream("a");
            h.upstream("b");
            h.upstream("c");
            h.start("""
                    routes:
                      - id: api
                        match: {pathPrefix: /api}
                        policy:
                          type: hash
                          options: {key: "header:X-User-Id"}
                        upstreams:
                          - {id: a, url: "http://127.0.0.1:${UP_A}"}
                          - {id: b, url: "http://127.0.0.1:${UP_B}"}
                          - {id: c, url: "http://127.0.0.1:${UP_C}"}
                    """);
            Map<String, String> assignment = new HashMap<>();
            Map<String, Integer> spread = new HashMap<>();
            for (int round = 0; round < 3; round++) {
                for (int u = 0; u < 60; u++) {
                    String user = "user-" + u;
                    String chosen = upstreamOf(send(h.port(), "/api/x", "X-User-Id", user));
                    String previous = assignment.put(user, chosen);
                    if (previous != null) {
                        assertEquals(previous, chosen, user + " must stay on one upstream");
                    }
                    if (round == 0) {
                        spread.merge(chosen, 1, Integer::sum);
                    }
                }
            }
            assertEquals(3, spread.size(), "keys are spread over all upstreams: " + spread);
        }
    }

    @Test
    void routesMatchOnMethodHeaderQueryExactPathAndPriority(@TempDir Path dir) throws Exception {
        try (ProxyHarness h = new ProxyHarness(dir)) {
            for (String n : new String[] {"read", "write", "canary", "v2", "health", "catchall", "pinned"}) {
                h.upstream(n);
            }
            h.start("""
                    routes:
                      - id: catchall
                        match: {pathPrefix: /}
                        upstreams: [{id: u, url: "http://127.0.0.1:${UP_CATCHALL}"}]
                      - id: reads
                        match: {pathPrefix: /svc, methods: [GET]}
                        upstreams: [{id: u, url: "http://127.0.0.1:${UP_READ}"}]
                      - id: writes
                        match: {pathPrefix: /svc, methods: [POST, PUT]}
                        upstreams: [{id: u, url: "http://127.0.0.1:${UP_WRITE}"}]
                      - id: canary
                        match:
                          pathPrefix: /svc
                          headers: [{name: X-Env, equals: canary}]
                        upstreams: [{id: u, url: "http://127.0.0.1:${UP_CANARY}"}]
                      - id: version2
                        match:
                          pathPrefix: /svc
                          query: [{name: v, equals: "2"}]
                        upstreams: [{id: u, url: "http://127.0.0.1:${UP_V2}"}]
                      - id: health
                        match: {path: /svc/health}
                        upstreams: [{id: u, url: "http://127.0.0.1:${UP_HEALTH}"}]
                      - id: pinned
                        priority: 100
                        match: {pathPrefix: /svc, headers: [{name: X-Pin, present: true}]}
                        upstreams: [{id: u, url: "http://127.0.0.1:${UP_PINNED}"}]
                    """);
            int p = h.port();
            assertEquals("read", upstreamOf(send(p, "/svc/items")), "method-constrained prefix beats the catch-all");
            assertEquals("catchall", upstreamOf(send(p, "/other")));
            assertEquals("catchall", upstreamOf(send(p, "/svcx/items")), "prefix matching is segment-aware");
            assertEquals("health", upstreamOf(send(p, "/svc/health")), "exact path beats prefixes");
            assertEquals("canary", upstreamOf(send(p, "/svc/items", "X-Env", "canary")), "header match adds specificity over a method-only match");
            assertEquals("v2", upstreamOf(send(p, "/svc/items?v=2")), "query match adds specificity");
            assertEquals("read", upstreamOf(send(p, "/svc/items?v=3")));
            assertEquals("pinned", upstreamOf(send(p, "/svc/health", "X-Pin", "1")), "explicit priority overrides specificity");
            try (RawClient c = h.raw()) {
                assertEquals("write", c.request("POST", "/svc/items", null, "x".getBytes()).header("x-upstream-id"));
                assertEquals("write", c.request("PUT", "/svc/items", null, "x".getBytes()).header("x-upstream-id"));
                assertEquals("catchall", c.request("DELETE", "/svc/items", null, null).header("x-upstream-id"), "no route constrained to DELETE: falls to the catch-all");
            }
        }
    }

    @Test
    void customPolicyFromServiceLoaderIsUsedEndToEnd(@TempDir Path dir) throws Exception {
        try (ProxyHarness h = new ProxyHarness(dir)) {
            h.upstream("a");
            h.upstream("b");
            h.start("""
                    routes:
                      - id: api
                        match: {pathPrefix: /api}
                        policy: test-last
                        upstreams:
                          - {id: a, url: "http://127.0.0.1:${UP_A}"}
                          - {id: b, url: "http://127.0.0.1:${UP_B}"}
                    """);
            for (int i = 0; i < 5; i++) {
                assertEquals("b", upstreamOf(send(h.port(), "/api/x")));
            }
        }
    }

    @Test
    void aFaultyPolicyDegradesToTheFirstCandidateInsteadOfFailingRequests(@TempDir Path dir) throws Exception {
        try (ProxyHarness h = new ProxyHarness(dir)) {
            h.upstream("a");
            h.upstream("b");
            h.start("""
                    routes:
                      - id: api
                        match: {pathPrefix: /api}
                        policy: test-throw
                        upstreams:
                          - {id: a, url: "http://127.0.0.1:${UP_A}"}
                          - {id: b, url: "http://127.0.0.1:${UP_B}"}
                    """);
            for (int i = 0; i < 5; i++) {
                HttpResponse<String> r = send(h.port(), "/api/x");
                assertEquals(200, r.statusCode());
                assertEquals("a", upstreamOf(r));
            }
            assertEquals(5.0, h.metric("routeplane_policy_errors_total{route=\"api\"}"));
            assertTrue(h.logs().contains("routing_policy_error"));
        }
    }

    @Test
    void clientsCannotChooseUpstreams(@TempDir Path dir) throws Exception {
        try (ProxyHarness h = new ProxyHarness(dir)) {
            FaultUpstream a = h.upstream("a");
            FaultUpstream other = h.upstream("other");
            h.start("""
                    routes:
                      - id: api
                        match: {pathPrefix: /api}
                        upstreams: [{id: a, url: "http://127.0.0.1:${UP_A}"}]
                    """);
            try (RawClient c = h.raw()) {
                // none of these may redirect the request to another destination
                assertEquals("a", c.request("GET", "/api/x", "X-Forwarded-Host: 127.0.0.1:" + other.port() + "\r\nX-Upstream: " + other.port() + "\r\nX-Original-URL: http://127.0.0.1:" + other.port() + "/\r\n", null).header("x-upstream-id"));
                assertEquals(400, c.request("GET", "/api/x", "Host: 127.0.0.1:" + other.port() + "\r\n", null).status(), "a second Host header is rejected");
            }
            assertEquals(0, other.stats().requests());
            assertTrue(a.stats().requests() >= 1);
        }
    }
}
