package dev.routeplane.fault;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(60)
class FaultUpstreamTest {
    private final List<FaultUpstream> started = new ArrayList<>();

    private FaultUpstream start(FaultProfile profile) throws IOException {
        FaultUpstream u = FaultUpstream.start(FaultUpstream.Options.of("t", 0).withProfile(profile).withControlPort(0));
        started.add(u);
        return u;
    }

    @AfterEach
    void stop() {
        started.forEach(FaultUpstream::close);
    }

    @Test
    void servesPayloadsOfTheConfiguredSizeOverKeepAliveConnections() throws Exception {
        FaultUpstream u = start(FaultProfile.DEFAULT.with("payloadMin", 1000).with("payloadMax", 1000));
        try (RawHttp c = new RawHttp("127.0.0.1", u.port(), 5000)) {
            for (int i = 0; i < 5; i++) {
                RawHttp.Response r = c.get("/anything");
                assertEquals(200, r.status());
                assertEquals(1000, r.body().length);
                assertEquals("t", r.header("x-upstream-id"));
            }
        }
        assertEquals(1, u.stats().connections(), "five requests reused one connection");
        assertEquals(5, u.stats().requests());
    }

    @Test
    void headHasLengthButNoBodyAndBigAndStatusPathsWork() throws Exception {
        FaultUpstream u = start(FaultProfile.DEFAULT);
        try (RawHttp c = new RawHttp("127.0.0.1", u.port(), 5000)) {
            RawHttp.Response h = c.request("HEAD", "/big?bytes=5000", null, null);
            assertEquals(200, h.status());
            assertEquals("5000", h.header("content-length"));
            assertEquals(0, h.body().length);
            assertEquals(5000, c.get("/big?bytes=5000").body().length);
            assertEquals(418, c.get("/status/418").status());
        }
    }

    @Test
    void echoReportsHeadersAndDrainsContentLengthAndChunkedBodies() throws Exception {
        FaultUpstream u = start(FaultProfile.DEFAULT);
        try (RawHttp c = new RawHttp("127.0.0.1", u.port(), 5000)) {
            RawHttp.Response r = c.request("POST", "/echo?x=1", "X-Custom: hello\r\n", "0123456789".getBytes());
            JsonObject j = JsonParser.parseString(r.text()).getAsJsonObject();
            assertEquals("POST", j.get("method").getAsString());
            assertEquals("/echo?x=1", j.get("target").getAsString());
            assertEquals(10, j.get("bodyBytes").getAsInt());
            assertEquals("hello", j.getAsJsonObject("headers").getAsJsonArray("X-Custom").get(0).getAsString());
            // chunked request body on the same connection
            c.send("POST /echo HTTP/1.1\r\nHost: t\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhello\r\n6\r\n world\r\n0\r\n\r\n");
            RawHttp.Response r2 = c.readResponse(false);
            assertEquals(11, JsonParser.parseString(r2.text()).getAsJsonObject().get("bodyBytes").getAsInt());
        }
        List<FaultUpstream.RecordedRequest> rec = u.recorded();
        assertEquals(2, rec.size());
        assertEquals("hello", rec.get(0).header("x-custom"));
        assertEquals("hello world", rec.get(1).bodyPrefix());
    }

    @Test
    void delayIsApplied() throws Exception {
        FaultUpstream u = start(FaultProfile.DEFAULT.with("delayMs", 200));
        try (RawHttp c = new RawHttp("127.0.0.1", u.port(), 5000)) {
            long t0 = System.nanoTime();
            c.get("/x");
            long ms = (System.nanoTime() - t0) / 1_000_000;
            assertTrue(ms >= 190, "took " + ms + " ms");
        }
    }

    @Test
    void errorInjectionIsDeterministicForASeedAndMatchesTheRate() throws Exception {
        List<Integer> a = statusSequence(FaultProfile.DEFAULT.with("errorRate", 0.3).with("errorStatus", 503).with("seed", 77), 400);
        List<Integer> b = statusSequence(FaultProfile.DEFAULT.with("errorRate", 0.3).with("errorStatus", 503).with("seed", 77), 400);
        List<Integer> c = statusSequence(FaultProfile.DEFAULT.with("errorRate", 0.3).with("errorStatus", 503).with("seed", 78), 400);
        assertEquals(a, b, "same seed, same arrival order, same faults");
        assertFalse(a.equals(c), "different seed, different faults");
        long errors = a.stream().filter(s -> s == 503).count();
        assertTrue(errors > 90 && errors < 150, "30% of 400 ~ 120 but was " + errors);
    }

    private List<Integer> statusSequence(FaultProfile p, int n) throws Exception {
        FaultUpstream u = start(p);
        List<Integer> out = new ArrayList<>();
        try (RawHttp c = new RawHttp("127.0.0.1", u.port(), 5000)) {
            for (int i = 0; i < n; i++) {
                out.add(c.get("/x").status());
            }
        }
        return out;
    }

    @Test
    void resetClosesWithoutAnyResponseBytes() throws Exception {
        FaultUpstream u = start(FaultProfile.DEFAULT.with("resetRate", 1.0));
        try (RawHttp c = new RawHttp("127.0.0.1", u.port(), 5000)) {
            assertThrows(IOException.class, () -> c.get("/x"));
        }
        assertEquals(1, u.stats().resets());
    }

    @Test
    void earlyCloseEndsTheConnectionWithoutAResponse() throws Exception {
        FaultUpstream u = start(FaultProfile.DEFAULT.with("closeRate", 1.0));
        try (RawHttp c = new RawHttp("127.0.0.1", u.port(), 5000)) {
            IOException e = assertThrows(IOException.class, () -> c.get("/x"));
            assertTrue(e.getMessage().contains("closed") || e.getMessage().contains("reset"), e.getMessage());
        }
        assertEquals(1, u.stats().earlyCloses());
    }

    @Test
    void partialResponsesDeclareMoreThanTheySend() throws Exception {
        FaultUpstream u = start(FaultProfile.DEFAULT.with("partialRate", 1.0).with("payloadMin", 1000).with("payloadMax", 1000));
        try (RawHttp c = new RawHttp("127.0.0.1", u.port(), 5000)) {
            RawHttp.Response r = c.get("/x");
            assertEquals(200, r.status());
            assertEquals("1000", r.header("content-length"));
            assertTrue(r.truncated());
            assertEquals(500, r.body().length);
        }
    }

    @Test
    void slowBodiesAreThrottled() throws Exception {
        FaultUpstream u = start(FaultProfile.DEFAULT.with("slowBytesPerSecond", 5000).with("payloadMin", 2500).with("payloadMax", 2500));
        try (RawHttp c = new RawHttp("127.0.0.1", u.port(), 10_000)) {
            long t0 = System.nanoTime();
            RawHttp.Response r = c.get("/x");
            long ms = (System.nanoTime() - t0) / 1_000_000;
            assertEquals(2500, r.body().length);
            assertTrue(ms >= 400, "2500 bytes at 5000 B/s should take ~500 ms but took " + ms);
        }
    }

    @Test
    void streamingEndpointSendsChunksOverTime() throws Exception {
        FaultUpstream u = start(FaultProfile.DEFAULT);
        try (RawHttp c = new RawHttp("127.0.0.1", u.port(), 10_000)) {
            long t0 = System.nanoTime();
            RawHttp.Response r = c.get("/stream?chunks=4&chunkBytes=100&intervalMs=100");
            long ms = (System.nanoTime() - t0) / 1_000_000;
            assertEquals(400, r.body().length);
            assertEquals("chunked", r.header("transfer-encoding"));
            assertTrue(ms >= 290, "3 gaps of 100 ms but took " + ms);
        }
    }

    @Test
    void refusingMakesTheOsRefuseConnectionsAndRecoveryRestoresThem() throws Exception {
        FaultUpstream u = start(FaultProfile.DEFAULT);
        RawHttp keepAlive = new RawHttp("127.0.0.1", u.port(), 5000);
        assertEquals(200, keepAlive.get("/x").status());
        u.setProfile(u.profile().with("refuse", true));
        assertThrows(ConnectException.class, () -> new RawHttp("127.0.0.1", u.port(), 1000), "listener is gone: connection refused");
        assertThrows(IOException.class, () -> keepAlive.get("/x"), "existing connections were dropped like a crashed process");
        u.setProfile(u.profile().with("refuse", false));
        try (RawHttp again = new RawHttp("127.0.0.1", u.port(), 5000)) {
            assertEquals(200, again.get("/x").status());
        }
    }

    @Test
    void capacityLimitRejectsOverflowWith503() throws Exception {
        FaultUpstream u = start(FaultProfile.DEFAULT.with("delayMs", 300).with("maxConcurrency", 2).with("queueWaitMs", 0));
        HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        List<CompletableFuture<HttpResponse<String>>> fs = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            fs.add(client.sendAsync(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + u.port() + "/x")).build(), HttpResponse.BodyHandlers.ofString()));
        }
        int ok = 0;
        int rejected = 0;
        for (var f : fs) {
            HttpResponse<String> r = f.get();
            if (r.statusCode() == 200) {
                ok++;
            } else {
                assertEquals(503, r.statusCode());
                assertEquals("capacity", r.headers().firstValue("x-fault").orElse(""));
                rejected++;
            }
        }
        assertEquals(2, ok);
        assertEquals(4, rejected);
        assertEquals(4, u.stats().capacityRejections());
        assertEquals(2, u.stats().ok(), "only the two admitted requests were served");
    }

    @Test
    void queueingAbsorbsABurstWithinTheWaitBudget() throws Exception {
        FaultUpstream u = start(FaultProfile.DEFAULT.with("delayMs", 100).with("maxConcurrency", 1).with("queueWaitMs", 2000));
        HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        List<CompletableFuture<HttpResponse<String>>> fs = new ArrayList<>();
        long t0 = System.nanoTime();
        for (int i = 0; i < 4; i++) {
            fs.add(client.sendAsync(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + u.port() + "/x")).build(), HttpResponse.BodyHandlers.ofString()));
        }
        for (var f : fs) {
            assertEquals(200, f.get().statusCode());
        }
        assertTrue((System.nanoTime() - t0) / 1_000_000 >= 380, "serialised by capacity 1");
    }

    @Test
    void gradualRecoveryDecaysTheErrorRateToZero() throws Exception {
        FaultUpstream u = start(FaultProfile.DEFAULT.with("errorRate", 1.0).with("errorStatus", 503));
        try (RawHttp c = new RawHttp("127.0.0.1", u.port(), 5000)) {
            assertEquals(503, c.get("/x").status());
            u.startRecovery(1000);
            int early = 0;
            int late = 0;
            long t0 = System.nanoTime();
            while ((System.nanoTime() - t0) / 1_000_000 < 1300) {
                long t = (System.nanoTime() - t0) / 1_000_000;
                boolean err = c.get("/x").status() == 503;
                if (t < 300 && err) {
                    early++;
                }
                if (t > 1050 && err) {
                    late++;
                }
                Thread.sleep(5);
            }
            assertTrue(early > 10, "mostly failing at the start of recovery: " + early);
            assertEquals(0, late, "healthy after the recovery window");
        }
    }

    @Test
    void controlApiReadsAndPatchesTheProfile() throws Exception {
        FaultUpstream u = start(FaultProfile.DEFAULT);
        HttpClient client = HttpClient.newHttpClient();
        String base = "http://127.0.0.1:" + u.controlPort();
        HttpResponse<String> patch = client.send(HttpRequest.newBuilder(URI.create(base + "/control/profile"))
                .PUT(HttpRequest.BodyPublishers.ofString("{\"errorRate\":1.0,\"errorStatus\":502}", StandardCharsets.UTF_8)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, patch.statusCode());
        try (RawHttp c = new RawHttp("127.0.0.1", u.port(), 5000)) {
            assertEquals(502, c.get("/x").status());
        }
        HttpResponse<String> bad = client.send(HttpRequest.newBuilder(URI.create(base + "/control/profile"))
                .PUT(HttpRequest.BodyPublishers.ofString("{\"nope\":1}")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(400, bad.statusCode());
        assertTrue(bad.body().contains("unknown profile field"));
        HttpResponse<String> stats = client.send(HttpRequest.newBuilder(URI.create(base + "/control/stats")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(1, JsonParser.parseString(stats.body()).getAsJsonObject().get("injectedErrors").getAsInt());
        client.send(HttpRequest.newBuilder(URI.create(base + "/control/reset")).POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        try (RawHttp c = new RawHttp("127.0.0.1", u.port(), 5000)) {
            assertEquals(200, c.get("/x").status());
        }
    }

    @Test
    void invalidProfilesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> FaultProfile.DEFAULT.with("errorRate", 0.7).with("resetRate", 0.7));
        assertThrows(IllegalArgumentException.class, () -> FaultProfile.DEFAULT.with("errorStatus", 42));
        assertThrows(IllegalArgumentException.class, () -> FaultProfile.DEFAULT.with("bogus", 1));
    }

    @SuppressWarnings("unused")
    private static void unused() throws ExecutionException {
    }
}
