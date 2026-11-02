package dev.routeplane.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.routeplane.fault.FaultProfile;
import dev.routeplane.fault.FaultUpstream;
import dev.routeplane.testing.ProxyHarness;
import dev.routeplane.testing.RawClient;
import java.io.IOException;
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
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Timeouts, cancellation, truncation semantics, slow and vanishing clients, overload and graceful drain. */
@Timeout(180)
class ProxyLifecycleTest {

    private static String route(String timeouts, String extra) {
        return "routes:\n  - id: api\n    match: {pathPrefix: /api}\n    upstreams: [{id: a, url: \"http://127.0.0.1:${UP_A}\"}]\n"
                + "    timeouts: " + timeouts + "\n" + extra;
    }

    @Test
    void responseTimeoutYieldsA504WithinTheConfiguredTime(@TempDir Path dir) throws Exception {
        try (ProxyHarness h = new ProxyHarness(dir)) {
            h.upstream("a", FaultProfile.DEFAULT.with("delayMs", 2000));
            h.start(route("{connect: 1s, response: 300ms, request: 10s}", ""));
            try (RawClient c = h.raw()) {
                long t0 = System.nanoTime();
                RawClient.Response r = c.get("/api/x");
                long ms = (System.nanoTime() - t0) / 1_000_000;
                assertEquals(504, r.status());
                assertEquals("upstream_timeout", r.header("x-routeplane-error"));
                assertTrue(ms >= 280 && ms < 1500, "took " + ms + " ms");
            }
            assertEquals(1.0, h.metric("routeplane_timeouts_total{route=\"api\",kind=\"response\"}"));
            assertEquals(1.0, h.metric("routeplane_errors_total{route=\"api\",kind=\"upstream_timeout\"}"));
        }
    }

    @Test
    void connectionRefusedYields502AndTheProxyRecoversWhenTheUpstreamReturns(@TempDir Path dir) throws Exception {
        try (ProxyHarness h = new ProxyHarness(dir)) {
            FaultUpstream up = h.upstream("a");
            h.start(route("{connect: 1s, response: 2s, request: 5s}", ""));
            up.setProfile(up.profile().with("refuse", true));
            try (RawClient c = h.raw()) {
                long t0 = System.nanoTime();
                RawClient.Response r = c.get("/api/x");
                assertEquals(502, r.status());
                assertEquals("connect_failed", r.header("x-routeplane-error"));
                assertTrue((System.nanoTime() - t0) / 1_000_000 < 1000, "refusal is detected immediately");
                up.setProfile(up.profile().with("refuse", false));
                assertEquals(200, c.get("/api/x").status());
            }
        }
    }

    @Test
    void connectionResetAndEarlyCloseBeforeHeadersAreBadGateways(@TempDir Path dir) throws Exception {
        try (ProxyHarness h = new ProxyHarness(dir)) {
            FaultUpstream up = h.upstream("a", FaultProfile.DEFAULT.with("resetRate", 1.0));
            h.start(route("{connect: 1s, response: 2s, request: 5s}", ""));
            try (RawClient c = h.raw()) {
                RawClient.Response r = c.get("/api/x");
                assertEquals(502, r.status());
                assertEquals("upstream_reset", r.header("x-routeplane-error"));
                up.setProfile(FaultProfile.DEFAULT.with("closeRate", 1.0));
                assertEquals(502, c.get("/api/x").status());
            }
        }
    }

    @Test
    void anExpiredDeadlineMidStreamTruncatesTheChunkedResponseInsteadOfCompletingIt(@TempDir Path dir) throws Exception {
        try (ProxyHarness h = new ProxyHarness(dir)) {
            FaultUpstream up = h.upstream("a");
            h.start(route("{connect: 500ms, response: 800ms, request: 800ms}", ""));
            try (RawClient c = h.raw()) {
                long t0 = System.nanoTime();
                RawClient.Response r = c.get("/api/stream?chunks=10&chunkBytes=100&intervalMs=300");
                long ms = (System.nanoTime() - t0) / 1_000_000;
                assertEquals(200, r.status(), "headers had already been sent");
                assertTrue(r.truncated(), "the client must be able to tell the body is incomplete");
                assertFalse(r.sawTerminator(), "no terminating chunk may be written for an aborted response");
                assertTrue(r.body().length >= 100 && r.body().length < 1000, "partial body: " + r.body().length);
                assertTrue(ms < 2500, "deadline enforced after " + ms + " ms");
            }
            assertTrue(ProxyHarness.eventually(Duration.ofSeconds(5), () -> up.stats().active() == 0), "upstream exchange cancelled");
            assertTrue(ProxyHarness.eventually(Duration.ofSeconds(5), () -> h.app().proxy().inFlight() == 0));
            assertEquals(1.0, h.metric("routeplane_errors_total{route=\"api\",kind=\"deadline_exceeded\"}"));
        }
    }

    @Test
    void anUpstreamThatDiesMidChunkedStreamProducesATruncatedNotACompleteResponse(@TempDir Path dir) throws Exception {
        try (ProxyHarness h = new ProxyHarness(dir)) {
            h.upstream("a");
            h.start(route("{connect: 1s, response: 5s, request: 10s}", ""));
            try (RawClient c = h.raw()) {
                RawClient.Response r = c.get("/api/stream?chunks=10&chunkBytes=100&abortAfterChunks=3");
                assertEquals(200, r.status());
                assertTrue(r.truncated());
                assertFalse(r.sawTerminator());
                assertTrue(r.body().length >= 100 && r.body().length <= 300,
                        "the JDK client does not guarantee how much of a stream is delivered before it reports a failure: " + r.body().length);
            }
            assertTrue(ProxyHarness.eventually(Duration.ofSeconds(5), () -> h.metric("routeplane_errors_total{route=\"api\",kind=\"upstream_reset\"}") >= 1.0));
        }
    }

    @Test
    void aTruncatedFixedLengthUpstreamBodyIsNeverPassedOffAsComplete(@TempDir Path dir) throws Exception {
        try (ProxyHarness h = new ProxyHarness(dir)) {
            h.upstream("a", FaultProfile.DEFAULT.with("partialRate", 1.0).with("payloadMin", 1000).with("payloadMax", 1000));
            h.start(route("{connect: 1s, response: 5s, request: 10s}", ""));
            try (RawClient c = h.raw()) {
                RawClient.Response r = c.get("/api/x");
                assertEquals(200, r.status());
                assertEquals("1000", r.header("content-length"));
                assertTrue(r.truncated());
                assertEquals(500, r.body().length);
            }
        }
    }

    @Test
    void aClientThatStallsMidUploadIsCutOffByTheRequestDeadline(@TempDir Path dir) throws Exception {
        try (ProxyHarness h = new ProxyHarness(dir)) {
            FaultUpstream up = h.upstream("a");
            h.start(route("{connect: 500ms, response: 1s, request: 1s}", ""));
            try (RawClient c = h.raw()) {
                c.send("POST /api/echo HTTP/1.1\r\nHost: t\r\nContent-Length: 1000\r\n\r\n0123456789");
                long t0 = System.nanoTime();
                byte[] answer = c.readUntilClosed();
                long ms = (System.nanoTime() - t0) / 1_000_000;
                String text = new String(answer);
                assertTrue(text.startsWith("HTTP/1.1 504") || text.isEmpty(), "got: " + text.lines().findFirst().orElse(""));
                assertTrue(ms < 4000, "cut off after " + ms + " ms");
            }
            assertTrue(ProxyHarness.eventually(Duration.ofSeconds(5), () -> h.app().proxy().inFlight() == 0));
            assertEquals(0, up.stats().ok(), "the upstream never served the incomplete request");
        }
    }

    @Test
    void aClientThatDoesNotReadTheResponseIsCutOffByTheRequestDeadline(@TempDir Path dir) throws Exception {
        try (ProxyHarness h = new ProxyHarness(dir)) {
            FaultUpstream up = h.upstream("a");
            h.start(route("{connect: 500ms, response: 1500ms, request: 1500ms}", ""));
            try (RawClient c = h.raw()) {
                c.socket().setReceiveBufferSize(4096);
                c.send("GET /api/stream?chunks=1000&chunkBytes=65536 HTTP/1.1\r\nHost: t\r\n\r\n");
                // never read
                assertTrue(ProxyHarness.eventually(Duration.ofSeconds(10), () -> h.app().proxy().inFlight() == 0), "blocked writer was unblocked by the deadline");
                assertTrue(ProxyHarness.eventually(Duration.ofSeconds(10), () -> up.stats().active() == 0));
            }
        }
    }

    @Test
    void aClientThatDisconnectsMidUploadIsCleanedUpAndNotBlamedOnTheUpstream(@TempDir Path dir) throws Exception {
        try (ProxyHarness h = new ProxyHarness(dir)) {
            FaultUpstream up = h.upstream("a");
            h.start(route("{connect: 1s, response: 5s, request: 10s}", "    circuitBreaker: {minimumRequests: 1, slidingWindow: {type: count, size: 5}}\n"));
            for (int i = 0; i < 6; i++) {
                try (RawClient c = h.raw()) {
                    c.send("POST /api/echo HTTP/1.1\r\nHost: t\r\nContent-Length: 1000000\r\n\r\n");
                    c.send(new byte[50_000]);
                    Thread.sleep(50);
                } // closes mid-body
            }
            assertTrue(ProxyHarness.eventually(Duration.ofSeconds(10), () -> h.app().proxy().inFlight() == 0), "all abandoned requests were released");
            assertTrue(ProxyHarness.eventually(Duration.ofSeconds(10), () -> h.metric("routeplane_errors_total{route=\"api\",kind=\"client_abort\"}") >= 6.0), h.metrics());
            try (RawClient c = h.raw()) {
                assertEquals(200, c.get("/api/x").status(), "client aborts must not trip the breaker");
            }
            assertEquals(0.0, h.metric("routeplane_circuit_breaker_state{route=\"api\",upstream=\"a\"}"));
            assertEquals(0, up.stats().ok() - 1, "no abandoned upload was served successfully");
        }
    }

    @Test
    void aClientThatDisconnectsMidResponseReleasesTheUpstreamExchange(@TempDir Path dir) throws Exception {
        try (ProxyHarness h = new ProxyHarness(dir)) {
            FaultUpstream up = h.upstream("a");
            h.start(route("{connect: 1s, response: 5s, request: 30s}", ""));
            try (RawClient c = h.raw()) {
                c.send("GET /api/stream?chunks=1000&chunkBytes=65536&intervalMs=5 HTTP/1.1\r\nHost: t\r\n\r\n");
                c.in().readNBytes(100_000);
            }
            assertTrue(ProxyHarness.eventually(Duration.ofSeconds(10), () -> up.stats().active() == 0), "upstream exchange cancelled after the client left");
            assertTrue(ProxyHarness.eventually(Duration.ofSeconds(10), () -> h.app().proxy().inFlight() == 0));
            assertTrue(ProxyHarness.eventually(Duration.ofSeconds(5), () -> h.metric("routeplane_errors_total{route=\"api\",kind=\"client_abort\"}") >= 1.0));
        }
    }

    @Test
    void overloadIsRejectedFastWithRetryAfter(@TempDir Path dir) throws Exception {
        try (ProxyHarness h = new ProxyHarness(dir)) {
            h.upstream("a", FaultProfile.DEFAULT.with("delayMs", 600));
            h.start("""
                    server: {host: 127.0.0.1, port: 0, maxConcurrentRequests: 2}
                    admin: {host: 127.0.0.1, port: 0}
                    """ + route("{connect: 1s, response: 5s, request: 10s}", ""));
            HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
            List<CompletableFuture<HttpResponse<String>>> fs = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                fs.add(client.sendAsync(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + h.port() + "/api/x")).build(), HttpResponse.BodyHandlers.ofString()));
            }
            int ok = 0;
            int shed = 0;
            for (var f : fs) {
                HttpResponse<String> r = f.get();
                if (r.statusCode() == 200) {
                    ok++;
                } else {
                    assertEquals(503, r.statusCode());
                    assertEquals("overload", r.headers().firstValue("x-routeplane-error").orElse(""));
                    assertEquals("1", r.headers().firstValue("retry-after").orElse(""));
                    shed++;
                }
            }
            assertTrue(ok >= 2 && ok <= 4, "admitted " + ok);
            assertTrue(shed >= 4, "shed " + shed);
        }
    }

    @Test
    void gracefulDrainLetsInFlightRequestsFinishAndRefusesNewOnes(@TempDir Path dir) throws Exception {
        try (ProxyHarness h = new ProxyHarness(dir)) {
            h.upstream("a", FaultProfile.DEFAULT.with("delayMs", 800));
            h.start(route("{connect: 1s, response: 5s, request: 10s}", ""));
            ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor();
            Future<Integer> inFlight = ex.submit(() -> {
                try (RawClient c = h.raw()) {
                    return c.get("/api/x").status();
                }
            });
            assertTrue(ProxyHarness.eventually(Duration.ofSeconds(5), () -> h.app().proxy().inFlight() == 1));
            long t0 = System.nanoTime();
            Future<Boolean> shutdown = ex.submit(() -> h.app().shutdown(Duration.ofSeconds(10)));
            assertTrue(ProxyHarness.eventually(Duration.ofSeconds(5), () -> h.app().proxy().isDraining()));
            assertEquals(200, inFlight.get(), "the in-flight request completes normally");
            assertTrue(shutdown.get(), "shutdown reported a clean drain");
            long ms = (System.nanoTime() - t0) / 1_000_000;
            assertTrue(ms >= 500 && ms < 5000, "drain took " + ms + " ms");
            boolean refused = false;
            try (RawClient c = h.raw()) {
                int status = c.get("/api/x").status();
                refused = status == 503;
            } catch (IOException e) {
                refused = true;
            }
            assertTrue(refused, "new requests are refused after the drain");
            ex.shutdownNow();
        }
    }
}
