package dev.routeplane.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.routeplane.fault.FaultUpstream;
import dev.routeplane.testing.ProxyHarness;
import dev.routeplane.testing.RawClient;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Malformed, ambiguous and hostile requests: every one must be refused before anything reaches an upstream. */
@Timeout(120)
class ProxyHardeningTest {
    private static final String CONFIG = """
            server:
              host: 127.0.0.1
              port: 0
              maxHeaderBytes: 2048
              maxRequestBodyBytes: 1000
            admin: {host: 127.0.0.1, port: 0}
            routes:
              - id: api
                match: {pathPrefix: /api}
                upstreams: [{id: a, url: "http://127.0.0.1:${UP_A}"}]
            """;

    private record Outcome(int status, boolean closed, String connection) {
    }

    /** Sends raw bytes and reports the status (or -1 for no response) and whether the server closed the connection. */
    private static Outcome probe(int port, String raw) throws IOException {
        try (RawClient c = new RawClient(port, 3000)) {
            c.send(raw);
            byte[] all = c.readUntilClosed();
            String text = new String(all, StandardCharsets.ISO_8859_1);
            if (text.isEmpty()) {
                return new Outcome(-1, true, null);
            }
            int status = Integer.parseInt(text.split(" ", 3)[1]);
            String conn = null;
            for (String line : text.split("\r\n")) {
                if (line.toLowerCase().startsWith("connection:")) {
                    conn = line.substring(11).trim();
                }
            }
            return new Outcome(status, true, conn);
        }
    }

    @Test
    void refusesAmbiguousFramingAndMalformedRequestsWithoutTouchingTheUpstream(@TempDir Path dir) throws Exception {
        try (ProxyHarness h = new ProxyHarness(dir)) {
            FaultUpstream up = h.upstream("a");
            h.start(CONFIG);
            Map<String, Integer> expected = new LinkedHashMap<>();
            // framing / smuggling
            expected.put("POST /api/x HTTP/1.1\r\nHost: t\r\nContent-Length: 4\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n", 400);
            expected.put("POST /api/x HTTP/1.1\r\nHost: t\r\nContent-Length: 3\r\nContent-Length: 3\r\n\r\nabc", 400);
            expected.put("POST /api/x HTTP/1.1\r\nHost: t\r\nContent-Length: abc\r\n\r\n", 400);
            expected.put("POST /api/x HTTP/1.1\r\nHost: t\r\nContent-Length: +3\r\n\r\nabc", 400);
            expected.put("POST /api/x HTTP/1.1\r\nHost: t\r\nContent-Length: -1\r\n\r\n", 400);
            expected.put("POST /api/x HTTP/1.1\r\nHost: t\r\nTransfer-Encoding: chunked, identity\r\n\r\n0\r\n\r\n", 501);
            expected.put("POST /api/x HTTP/1.1\r\nHost: t\r\nTransfer-Encoding: xchunked\r\n\r\n0\r\n\r\n", 501);
            // request line / target
            expected.put("GET http://169.254.169.254/latest/meta-data HTTP/1.1\r\nHost: t\r\n\r\n", 400);
            expected.put("GET /api/x HTTP/2.0\r\nHost: t\r\n\r\n", 505);
            expected.put("get /api/x HTTP/1.1\r\nHost: t\r\n\r\n", 400);
            expected.put("TRACE /api/x HTTP/1.1\r\nHost: t\r\n\r\n", 405);
            expected.put("PROPFIND /api/x HTTP/1.1\r\nHost: t\r\n\r\n", 501);
            expected.put("GET /api%2fadmin HTTP/1.1\r\nHost: t\r\n\r\n", 400);
            expected.put("GET /api/%00 HTTP/1.1\r\nHost: t\r\n\r\n", 400);
            expected.put("GET /api/%0d%0aX:1 HTTP/1.1\r\nHost: t\r\n\r\n", 400);
            expected.put("GET /api\\admin HTTP/1.1\r\nHost: t\r\n\r\n", 400);
            expected.put("GET /api/x?a=%zz HTTP/1.1\r\nHost: t\r\n\r\n", 400);
            expected.put("GET /api/" + "a".repeat(9000) + " HTTP/1.1\r\nHost: t\r\n\r\n", 414);
            // headers
            expected.put("GET /api/x HTTP/1.1\r\n\r\n", 400);
            expected.put("GET /api/x HTTP/1.1\r\nHost: a\r\nHost: b\r\n\r\n", 400);
            expected.put("GET /api/x HTTP/1.1\r\nHost: a/b\r\n\r\n", 400);
            expected.put("GET /api/x HTTP/1.1\r\nHost: t\r\nX-Bad: a\u0000b\r\n\r\n", 400);
            expected.put("GET /api/x HTTP/1.1\r\nHost: t\r\nX-Big: " + "a".repeat(3000) + "\r\n\r\n", 431);
            StringBuilder many = new StringBuilder("GET /api/x HTTP/1.1\r\nHost: t\r\n");
            for (int i = 0; i < 120; i++) {
                many.append("X-H").append(i).append(": v\r\n");
            }
            expected.put(many.append("\r\n").toString(), 431);
            // body limits
            expected.put("POST /api/x HTTP/1.1\r\nHost: t\r\nContent-Length: 1001\r\n\r\n" + "x".repeat(1001), 413);
            List<String> failures = new ArrayList<>();
            for (Map.Entry<String, Integer> e : expected.entrySet()) {
                Outcome o = probe(h.port(), e.getKey());
                String label = e.getKey().lines().findFirst().orElse("").replaceAll("a{50,}", "a...");
                if (o.status() != e.getValue()) {
                    failures.add(label + " -> " + o.status() + " (expected " + e.getValue() + ")");
                } else if (!"close".equalsIgnoreCase(o.connection())) {
                    failures.add(label + " -> status ok but Connection was '" + o.connection() + "', expected close");
                }
            }
            assertTrue(failures.isEmpty(), "unexpected outcomes:\n" + String.join("\n", failures));
            assertEquals(0, up.stats().requests(), "nothing malformed may reach the upstream");
        }
    }

    @Test
    void connectMethodAndBareLineFeedsNeverReachRouting(@TempDir Path dir) throws Exception {
        try (ProxyHarness h = new ProxyHarness(dir)) {
            FaultUpstream up = h.upstream("a");
            h.start(CONFIG);
            Outcome connect = probe(h.port(), "CONNECT example.com:443 HTTP/1.1\r\nHost: example.com:443\r\n\r\n");
            assertTrue(connect.status() == -1 || connect.status() == 405 || connect.status() == 400 || connect.status() == 501, "CONNECT -> " + connect.status());
            Outcome lf = probe(h.port(), "GET /api/x HTTP/1.1\nHost: t\n\n");
            assertTrue(lf.status() == -1 || lf.status() == 400, "bare LF -> " + lf.status());
            assertEquals(0, up.stats().requests());
        }
    }

    @Test
    void dotSegmentsAreResolvedBeforeRoutingSoTraversalCannotEscapeTheMatchedPrefix(@TempDir Path dir) throws Exception {
        try (ProxyHarness h = new ProxyHarness(dir)) {
            FaultUpstream up = h.upstream("a");
            h.start(CONFIG);
            try (RawClient c = h.raw()) {
                assertEquals(404, c.get("/api/../admin/secret").status(), "normalised to /admin/secret, which has no route");
                assertEquals(404, c.get("/api/%2e%2e/admin").status());
                assertEquals(404, c.get("/%2e%2e/%2e%2e/etc/passwd").status());
                RawClient.Response ok = c.get("/api/a/../b/./c?x=1");
                assertEquals(200, ok.status());
            }
            assertEquals("/api/b/c?x=1", up.recorded().get(up.recorded().size() - 1).target(), "the upstream sees the normalised path that was routed");
        }
    }

    @Test
    void forwardingLoopsAreDetected(@TempDir Path dir) throws Exception {
        try (ProxyHarness h = new ProxyHarness(dir)) {
            h.upstream("a");
            h.start(CONFIG);
            try (RawClient c = h.raw()) {
                String via = c.get("/api/x").header("via");
                assertNotNull(via);
                String token = via.substring(via.lastIndexOf("routeplane-"));
                RawClient.Response r = c.request("GET", "/api/x", "Via: 1.1 " + token + ", 1.1 " + token + "\r\n", null);
                assertEquals(508, r.status());
            }
        }
    }

    @Test
    void errorResponsesCarryTheProxyMarkerAndACorrelationId(@TempDir Path dir) throws Exception {
        try (ProxyHarness h = new ProxyHarness(dir)) {
            h.upstream("a");
            h.start(CONFIG);
            try (RawClient c = h.raw()) {
                RawClient.Response r = c.get("/nothing");
                assertEquals(404, r.status());
                assertEquals("no_route", r.header("x-routeplane-error"));
                assertTrue(r.text().contains("\"requestId\":\"" + r.header("x-request-id") + "\""));
                assertEquals("application/json", r.header("content-type"));
            }
        }
    }
}
