package dev.routeplane.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
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
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(120)
class ProxyForwardingTest {
    private static final String ONE_ROUTE = """
            routes:
              - id: api
                match: {pathPrefix: /api}
                upstreams:
                  - {id: a, url: "http://127.0.0.1:${UP_A}"}
                timeouts: {connect: 1s, response: 5s, request: 20s}
            """;

    /** What the upstream saw, as lower-cased header map plus the JSON document. */
    private static Map<String, String> echoHeaders(String json) {
        JsonObject o = JsonParser.parseString(json).getAsJsonObject();
        Map<String, String> m = new HashMap<>();
        for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("headers").entrySet()) {
            m.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue().getAsJsonArray().get(0).getAsString());
        }
        return m;
    }

    @Test
    void forwardsEverySupportedMethodWithPathQueryAndBody(@TempDir Path dir) throws Exception {
        try (ProxyHarness h = new ProxyHarness(dir)) {
            h.upstream("a");
            h.start(ONE_ROUTE);
            try (RawClient c = h.raw()) {
                for (String m : new String[] {"POST", "PUT", "PATCH", "DELETE"}) {
                    RawClient.Response r = c.request(m, "/api/echo?x=1&y=%20z", null, "payload-12".getBytes());
                    assertEquals(200, r.status(), m);
                    JsonObject o = JsonParser.parseString(r.text()).getAsJsonObject();
                    assertEquals(m, o.get("method").getAsString());
                    assertEquals("/api/echo?x=1&y=%20z", o.get("target").getAsString(), "path and raw query are forwarded unchanged");
                    assertEquals(10, o.get("bodyBytes").getAsInt(), m);
                }
                for (String m : new String[] {"GET", "OPTIONS"}) {
                    RawClient.Response r = c.request(m, "/api/echo", null, null);
                    assertEquals(200, r.status(), m);
                    assertEquals(m, JsonParser.parseString(r.text()).getAsJsonObject().get("method").getAsString());
                }
                RawClient.Response head = c.request("HEAD", "/api/big?bytes=1234", null, null);
                assertEquals(200, head.status());
                assertEquals("1234", head.header("content-length"), "HEAD keeps the upstream Content-Length");
                assertEquals(0, head.body().length);
                assertEquals(1234, c.get("/api/big?bytes=1234").body().length, "the connection is still usable after HEAD");
            }
        }
    }

    @Test
    void propagatesUpstreamStatusesUnchangedAndDoesNotMarkThemAsProxyGenerated(@TempDir Path dir) throws Exception {
        try (ProxyHarness h = new ProxyHarness(dir)) {
            h.upstream("a");
            h.start(ONE_ROUTE);
            try (RawClient c = h.raw()) {
                for (int status : new int[] {200, 204, 304, 400, 404, 418, 429, 500, 502, 503, 504}) {
                    RawClient.Response r = c.get("/api/status/" + status);
                    assertEquals(status, r.status(), "status " + status + " must pass through");
                    assertNull(r.header("x-routeplane-error"), "an upstream error is not proxy-generated: " + status);
                    assertEquals("a", r.header("x-upstream-id"));
                    if (status != 204 && status != 304) {
                        assertTrue(r.text().contains("\"status\":" + status));
                    }
                }
            }
        }
    }

    @Test
    void streamsLargeRequestBodiesWithContentLengthAndChunked(@TempDir Path dir) throws Exception {
        try (ProxyHarness h = new ProxyHarness(dir)) {
            FaultUpstream up = h.upstream("a");
            h.start(ONE_ROUTE);
            byte[] five = new byte[5 * 1024 * 1024];
            java.util.Arrays.fill(five, (byte) 'x');
            try (RawClient c = h.raw()) {
                RawClient.Response r = c.request("POST", "/api/echo", null, five);
                assertEquals(200, r.status());
                assertEquals(five.length, JsonParser.parseString(r.text()).getAsJsonObject().get("bodyBytes").getAsInt());
                // chunked request body of unknown length: 3 MB in 64 KiB chunks
                c.send("POST /api/echo HTTP/1.1\r\nHost: t\r\nTransfer-Encoding: chunked\r\n\r\n");
                byte[] chunk = new byte[65536];
                java.util.Arrays.fill(chunk, (byte) 'y');
                for (int i = 0; i < 48; i++) {
                    c.send(Integer.toHexString(chunk.length) + "\r\n");
                    c.send(chunk);
                    c.send("\r\n");
                }
                c.send("0\r\n\r\n");
                RawClient.Response r2 = c.read(false);
                assertEquals(200, r2.status());
                assertEquals(48 * 65536, JsonParser.parseString(r2.text()).getAsJsonObject().get("bodyBytes").getAsInt());
            }
            var seen = up.recorded();
            assertEquals("chunked", seen.get(1).header("transfer-encoding").toLowerCase(Locale.ROOT), "unknown-length bodies stay chunked towards the upstream");
            assertEquals("5242880", seen.get(0).header("content-length"), "known-length bodies keep their exact length");
        }
    }

    @Test
    void streamsLargeResponsesWithoutBufferingThemAndAppliesBackpressure(@TempDir Path dir) throws Exception {
        try (ProxyHarness h = new ProxyHarness(dir)) {
            FaultUpstream up = h.upstream("a");
            h.start(ONE_ROUTE);
            try (RawClient c = h.raw()) {
                RawClient.Response big = c.get("/api/big?bytes=40000000");
                assertEquals(40_000_000, big.body().length);
                assertEquals("40000000", big.header("content-length"));
            }
            // A client that reads 1 MB and then stops: the upstream must be held back by TCP backpressure through the proxy
            // instead of the proxy buffering the rest (the stream would be ~65 MB).
            long before = up.stats().bytesSent();
            try (RawClient c = h.raw()) {
                c.send("GET /api/stream?chunks=1000&chunkBytes=65536 HTTP/1.1\r\nHost: t\r\n\r\n");
                c.in().readNBytes(1024 * 1024);
                Thread.sleep(1500);
                long sent = up.stats().bytesSent() - before;
                assertTrue(sent < 40L * 1024 * 1024, "upstream sent " + sent / 1024 / 1024 + " MiB although the client stopped reading after 1 MiB");
            }
            assertTrue(ProxyHarness.eventually(Duration.ofSeconds(10), () -> h.app().proxy().inFlight() == 0), "the abandoned request is cleaned up");
            assertTrue(ProxyHarness.eventually(Duration.ofSeconds(10), () -> up.stats().active() == 0), "the upstream exchange was cancelled");
        }
    }

    @Test
    void reusesUpstreamConnectionsAcrossRequests(@TempDir Path dir) throws Exception {
        try (ProxyHarness h = new ProxyHarness(dir)) {
            FaultUpstream up = h.upstream("a");
            h.start(ONE_ROUTE);
            HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
            for (int i = 0; i < 40; i++) {
                HttpResponse<String> r = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + h.port() + "/api/x")).build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, r.statusCode());
            }
            assertEquals(40, up.stats().requests());
            assertTrue(up.stats().connections() <= 2, "40 sequential requests used " + up.stats().connections() + " upstream connections");
        }
    }

    @Test
    void stripsHopByHopHeadersAndNeverTrustsClientForwardingHeaders(@TempDir Path dir) throws Exception {
        try (ProxyHarness h = new ProxyHarness(dir)) {
            FaultUpstream up = h.upstream("a");
            h.start(ONE_ROUTE);
            try (RawClient c = h.raw()) {
                RawClient.Response r = c.request("GET", "/api/echo", String.join("",
                        "Connection: keep-alive, X-Secret-Hop\r\n",
                        "X-Secret-Hop: must-not-arrive\r\n",
                        "Keep-Alive: timeout=5\r\n",
                        "TE: trailers\r\n",
                        "Proxy-Authorization: Basic Zm9vOmJhcg==\r\n",
                        "X-Forwarded-For: 6.6.6.6\r\n",
                        "X-Forwarded-Proto: https\r\n",
                        "X-Forwarded-Host: evil.example\r\n",
                        "Forwarded: for=evil\r\n",
                        "X-Real-IP: 1.2.3.4\r\n",
                        "X-Routeplane-Error: forged\r\n",
                        "Via: 1.1 some-cdn\r\n",
                        "Authorization: Bearer app-level-token\r\n",
                        "Cookie: a=1; b=2\r\n",
                        "X-Custom: kept\r\n"), null);
                assertEquals(200, r.status());
                Map<String, String> seen = echoHeaders(r.text());
                for (String gone : new String[] {"x-secret-hop", "keep-alive", "te", "proxy-authorization", "forwarded", "x-real-ip", "x-routeplane-error"}) {
                    assertFalse(seen.containsKey(gone), gone + " must not reach the upstream: " + seen);
                }
                assertFalse(seen.containsKey("connection"), "Connection is hop-by-hop: " + seen);
                assertEquals("127.0.0.1", seen.get("x-forwarded-for"), "the TCP peer, not the client-supplied value");
                assertEquals("http", seen.get("x-forwarded-proto"));
                assertEquals("test.local", seen.get("x-forwarded-host"), "the original Host is carried in X-Forwarded-Host");
                assertEquals("127.0.0.1:" + up.port(), seen.get("host"), "Host towards the upstream is the upstream's own authority");
                assertTrue(seen.get("via").startsWith("1.1 some-cdn, 1.1 routeplane-"), seen.get("via"));
                assertEquals("Bearer app-level-token", seen.get("authorization"), "application credentials are forwarded to the primary upstream");
                assertEquals("a=1; b=2", seen.get("cookie"));
                assertEquals("kept", seen.get("x-custom"));
                assertEquals("", seen.get("user-agent"), "no User-Agent is invented for the client (JDK default is suppressed)");
                assertTrue(seen.get("x-request-id").matches("[0-9a-f]{32}"), seen.get("x-request-id"));
                assertEquals(seen.get("x-request-id"), r.header("x-request-id"), "the same id is returned to the client");
            }
        }
    }

    @Test
    void stripsHopByHopAndForgedHeadersFromUpstreamResponses(@TempDir Path dir) throws Exception {
        try (ProxyHarness h = new ProxyHarness(dir)) {
            h.upstream("a");
            h.start(ONE_ROUTE);
            try (RawClient c = h.raw()) {
                String q = "h=Connection:%20close,%20X-Hop&h=X-Hop:%20secret&h=X-Routeplane-Error:%20forged&h=Keep-Alive:%20timeout%3D5"
                        + "&h=Set-Cookie:%20a%3D1&h=Set-Cookie:%20b%3D2&h=X-Custom:%20yes&h=Trailer:%20X-T&h=Upgrade:%20h2c";
                RawClient.Response r = c.get("/api/headers?" + q);
                assertEquals(200, r.status());
                assertNull(r.header("x-hop"));
                assertNull(r.header("keep-alive"));
                assertNull(r.header("x-routeplane-error"), "an upstream cannot forge the proxy-generated marker");
                assertNull(r.header("trailer"));
                assertNull(r.header("upgrade"));
                assertEquals(2, r.headers().get("set-cookie").size(), "multi-valued headers survive");
                assertEquals("yes", r.header("x-custom"));
                assertTrue(r.header("via").startsWith("1.1 routeplane-"));
                // the proxy's Connection handling is its own: the connection stays usable
                assertEquals(200, c.get("/api/x").status());
            }
        }
    }

    @Test
    void correlationIdsAreAcceptedWhenSafeAndReplacedOtherwise(@TempDir Path dir) throws Exception {
        try (ProxyHarness h = new ProxyHarness(dir)) {
            h.upstream("a");
            h.start(ONE_ROUTE);
            try (RawClient c = h.raw()) {
                RawClient.Response ok = c.request("GET", "/api/echo", "X-Request-Id: client-trace-42\r\n", null);
                assertEquals("client-trace-42", ok.header("x-request-id"));
                assertEquals("client-trace-42", echoHeaders(ok.text()).get("x-request-id"));
                RawClient.Response bad = c.request("GET", "/api/echo", "X-Request-Id: has spaces and \"quotes\"\r\n", null);
                assertNotEquals("has spaces and \"quotes\"", bad.header("x-request-id"));
                assertTrue(bad.header("x-request-id").matches("[0-9a-f]{32}"));
                RawClient.Response none = c.get("/api/echo");
                assertTrue(none.header("x-request-id").matches("[0-9a-f]{32}"));
                assertNotEquals(bad.header("x-request-id"), none.header("x-request-id"));
            }
            assertTrue(ProxyHarness.eventually(Duration.ofSeconds(5), () -> h.logs().contains("\"request_id\":\"client-trace-42\"")), "the id appears in the access log");
        }
    }

    @Test
    void stripPrefixAndUpstreamBasePathAreApplied(@TempDir Path dir) throws Exception {
        try (ProxyHarness h = new ProxyHarness(dir)) {
            h.upstream("a");
            h.start("""
                    routes:
                      - id: svc
                        stripPrefix: true
                        match: {pathPrefix: /svc}
                        upstreams:
                          - {id: a, url: "http://127.0.0.1:${UP_A}/internal/v2/"}
                    """);
            try (RawClient c = h.raw()) {
                RawClient.Response r = c.get("/svc/echo?k=v");
                assertEquals("/internal/v2/echo?k=v", JsonParser.parseString(r.text()).getAsJsonObject().get("target").getAsString());
                assertEquals("/internal/v2/", JsonParser.parseString(c.get("/svc/echo").text()).getAsJsonObject().get("target").getAsString().substring(0, 13));
            }
        }
    }

    @Test
    void configuredForwardedHeaderAndTrustedProxyAppendModes(@TempDir Path dir) throws Exception {
        try (ProxyHarness h = new ProxyHarness(dir)) {
            h.upstream("a");
            h.start("""
                    server:
                      host: 127.0.0.1
                      port: 0
                      forwarding:
                        mode: trusted-append
                        trustedProxies: ["127.0.0.0/8"]
                        emitForwardedHeader: true
                    admin: {host: 127.0.0.1, port: 0}
                    routes:
                      - id: api
                        match: {pathPrefix: /api}
                        upstreams: [{id: a, url: "http://127.0.0.1:${UP_A}"}]
                    """);
            try (RawClient c = h.raw()) {
                Map<String, String> seen = echoHeaders(c.request("GET", "/api/echo", "X-Forwarded-For: 203.0.113.9\r\nX-Forwarded-Proto: https\r\n", null).text());
                assertEquals("203.0.113.9, 127.0.0.1", seen.get("x-forwarded-for"), "a trusted peer's chain is extended");
                assertEquals("https", seen.get("x-forwarded-proto"));
                assertTrue(seen.get("forwarded").startsWith("for=127.0.0.1;proto=https;host=\"test.local\""), seen.get("forwarded"));
                Map<String, String> junk = echoHeaders(c.request("GET", "/api/echo", "X-Forwarded-For: <script>\r\n", null).text());
                assertEquals("127.0.0.1", junk.get("x-forwarded-for"), "a malformed chain from a trusted peer is discarded");
            }
        }
    }

    @Test
    void untrustedPeersCannotInfluenceForwardingEvenInTrustedAppendMode(@TempDir Path dir) throws Exception {
        try (ProxyHarness h = new ProxyHarness(dir)) {
            h.upstream("a");
            h.start("""
                    server:
                      host: 127.0.0.1
                      port: 0
                      forwarding:
                        mode: trusted-append
                        trustedProxies: ["10.99.0.0/16"]
                    admin: {host: 127.0.0.1, port: 0}
                    routes:
                      - id: api
                        match: {pathPrefix: /api}
                        upstreams: [{id: a, url: "http://127.0.0.1:${UP_A}"}]
                    """);
            try (RawClient c = h.raw()) {
                Map<String, String> seen = echoHeaders(c.request("GET", "/api/echo", "X-Forwarded-For: 203.0.113.9\r\nX-Forwarded-Host: evil\r\n", null).text());
                assertEquals("127.0.0.1", seen.get("x-forwarded-for"));
                assertEquals("test.local", seen.get("x-forwarded-host"));
            }
        }
    }

    @Test
    void forwardingCanBeSwitchedOff(@TempDir Path dir) throws Exception {
        try (ProxyHarness h = new ProxyHarness(dir)) {
            h.upstream("a");
            h.start("""
                    server: {host: 127.0.0.1, port: 0, forwarding: {mode: "off"}}
                    admin: {host: 127.0.0.1, port: 0}
                    routes:
                      - id: api
                        match: {pathPrefix: /api}
                        upstreams: [{id: a, url: "http://127.0.0.1:${UP_A}"}]
                    """);
            try (RawClient c = h.raw()) {
                Map<String, String> seen = echoHeaders(c.request("GET", "/api/echo", "X-Forwarded-For: 1.1.1.1\r\n", null).text());
                assertFalse(seen.containsKey("x-forwarded-for"));
                assertFalse(seen.containsKey("x-forwarded-proto"));
            }
        }
    }

    @Test
    void preservesHostWhenConfigured(@TempDir Path dir) throws Exception {
        try (ProxyHarness h = new ProxyHarness(dir)) {
            h.upstream("a");
            h.start("""
                    server: {host: 127.0.0.1, port: 0, hostHeader: preserve}
                    admin: {host: 127.0.0.1, port: 0}
                    routes:
                      - id: api
                        match: {pathPrefix: /api}
                        upstreams: [{id: a, url: "http://127.0.0.1:${UP_A}"}]
                    """);
            try (RawClient c = h.raw()) {
                assertEquals("test.local", echoHeaders(c.get("/api/echo").text()).get("host"));
            }
        }
    }

    @SuppressWarnings("unused")
    private static FaultProfile unused() {
        return FaultProfile.DEFAULT;
    }
}
