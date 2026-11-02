package dev.routeplane.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.routeplane.fault.FaultProfile;
import dev.routeplane.fault.FaultUpstream;
import dev.routeplane.testing.ProxyHarness;
import dev.routeplane.testing.RawClient;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Admin API protection, configuration reload semantics, and operational endpoints. */
@Timeout(240)
class AdminAndConfigTest {
    private static final String TOKEN = "0123456789abcdef-super-secret";

    private static String routeTo(String upstreamEnv, String extraRoute) {
        return "routes:\n  - id: api\n    match: {pathPrefix: /api}\n    upstreams: [{id: u, url: \"http://127.0.0.1:${" + upstreamEnv + "}\"}]\n" + extraRoute;
    }

    private static HttpResponse<String> http(String method, String url, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).method(method, HttpRequest.BodyPublishers.noBody());
        for (int i = 0; i < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        return HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build().send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    // ------------------------------------------------------------------------------------- endpoints

    @Test
    void operationalEndpointsServeJsonAndPrometheusText(@TempDir Path dir) throws Exception {
        try (ProxyHarness h = new ProxyHarness(dir)) {
            h.upstream("a");
            h.start(routeTo("UP_A", "    circuitBreaker: {minimumRequests: 5, slidingWindow: {type: count, size: 10}}\n"));
            h.get("/api/x");
            assertEquals("live", JsonParser.parseString(h.admin("GET", "/health/live").body()).getAsJsonObject().get("status").getAsString());
            JsonObject ready = JsonParser.parseString(h.admin("GET", "/health/ready").body()).getAsJsonObject();
            assertEquals("ready", ready.get("status").getAsString());
            assertEquals(1, ready.get("configVersion").getAsInt());
            JsonArray routes = JsonParser.parseString(h.admin("GET", "/admin/routes").body()).getAsJsonArray();
            assertEquals("api", routes.get(0).getAsJsonObject().get("id").getAsString());
            assertEquals("u", routes.get(0).getAsJsonObject().getAsJsonArray("upstreams").get(0).getAsJsonObject().get("id").getAsString());
            JsonObject up = JsonParser.parseString(h.admin("GET", "/admin/upstreams").body()).getAsJsonArray().get(0).getAsJsonObject();
            assertTrue(up.get("available").getAsBoolean());
            assertEquals("closed", up.get("circuit").getAsString());
            assertEquals("closed", JsonParser.parseString(h.admin("GET", "/admin/circuits").body()).getAsJsonArray().get(0).getAsJsonObject().get("state").getAsString());
            JsonObject cfg = JsonParser.parseString(h.admin("GET", "/admin/config").body()).getAsJsonObject();
            assertEquals(1, cfg.get("version").getAsInt());
            assertEquals(64, cfg.get("sha256").getAsString().length());
            assertEquals("127.0.0.1", cfg.getAsJsonObject("config").getAsJsonObject("server").get("host").getAsString());
            HttpResponse<String> metrics = h.admin("GET", "/metrics");
            assertTrue(metrics.headers().firstValue("content-type").orElse("").startsWith("text/plain; version=0.0.4"));
            assertTrue(metrics.body().contains("# TYPE routeplane_requests_total counter"));
            assertTrue(metrics.body().contains("routeplane_requests_total{route=\"api\",status_class=\"2xx\"} 1"));
            assertTrue(metrics.body().contains("jvm_memory_bytes_used{area=\"heap\"}"));
            assertTrue(metrics.body().contains("process_cpu_seconds_total"));
            assertEquals(404, h.admin("GET", "/nope").statusCode());
        }
    }

    @Test
    void adminEndpointsRequireTheBearerTokenWhenOneIsConfigured(@TempDir Path dir) throws Exception {
        try (ProxyHarness h = new ProxyHarness(dir)) {
            h.upstream("a");
            h.start("server: {host: 127.0.0.1, port: 0}\nadmin:\n  host: 127.0.0.1\n  port: 0\n  auth: {bearerToken: \"" + TOKEN + "\"}\n" + routeTo("UP_A", ""));
            String base = "http://127.0.0.1:" + h.adminPort();
            for (String path : new String[] {"/admin/routes", "/admin/upstreams", "/admin/circuits", "/admin/config", "/metrics"}) {
                HttpResponse<String> r = http("GET", base + path);
                assertEquals(401, r.statusCode(), path);
                assertEquals("Bearer", r.headers().firstValue("www-authenticate").orElse(""));
                assertEquals(401, http("GET", base + path, "Authorization", "Bearer wrong-token-value-here").statusCode(), path);
                assertEquals(401, http("GET", base + path, "Authorization", "Basic " + TOKEN).statusCode());
                assertEquals(200, http("GET", base + path, "Authorization", "Bearer " + TOKEN).statusCode(), path);
            }
            assertEquals(401, http("POST", base + "/admin/reload").statusCode());
            assertEquals(200, http("GET", base + "/health/live").statusCode(), "probes need no credentials");
            assertEquals(200, http("GET", base + "/health/ready").statusCode());
            String config = http("GET", base + "/admin/config", "Authorization", "Bearer " + TOKEN).body();
            assertFalse(config.contains(TOKEN), "the token must never be echoed");
            assertTrue(config.contains("[REDACTED]"));
            assertTrue(h.logs().contains("admin_auth_failed"));
            assertFalse(h.logs().contains(TOKEN));
        }
    }

    @Test
    void adminRejectsForeignHostHeadersAndCrossOriginStateChanges(@TempDir Path dir) throws Exception {
        try (ProxyHarness h = new ProxyHarness(dir)) {
            h.upstream("a");
            h.start(routeTo("UP_A", ""));
            try (RawClient c = new RawClient(h.adminPort(), 5000)) {
                c.send("GET /admin/routes HTTP/1.1\r\nHost: attacker.example:9090\r\n\r\n");
                assertEquals(403, c.read(false).status());
            }
            try (RawClient c = new RawClient(h.adminPort(), 5000)) {
                c.send("GET /admin/routes HTTP/1.1\r\nHost: localhost:" + h.adminPort() + "\r\n\r\n");
                assertEquals(200, c.read(false).status());
            }
            String base = "http://127.0.0.1:" + h.adminPort();
            assertEquals(403, http("POST", base + "/admin/reload", "Origin", "https://evil.example").statusCode(), "browsers add Origin to cross-site POSTs");
            assertEquals(405, http("GET", base + "/admin/reload").statusCode());
            assertEquals(405, http("POST", base + "/admin/routes").statusCode());
            assertEquals(405, http("DELETE", base + "/metrics").statusCode());
            assertEquals(200, http("POST", base + "/admin/reload").statusCode());
        }
    }

    @Test
    void adminCanBeServedOverTls(@TempDir Path dir) throws Exception {
        Path ks = dir.resolve("admin.p12");
        Process p = new ProcessBuilder(System.getProperty("java.home") + "/bin/keytool", "-genkeypair", "-alias", "admin", "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "2", "-dname", "CN=localhost", "-ext", "san=dns:localhost,ip:127.0.0.1", "-keystore", ks.toString(), "-storetype", "PKCS12",
                "-storepass", "changeit", "-keypass", "changeit").redirectErrorStream(true).start();
        String keytoolOut = new String(p.getInputStream().readAllBytes());
        assertEquals(0, p.waitFor(), "keytool failed: " + keytoolOut);
        try (ProxyHarness h = new ProxyHarness(dir)) {
            h.upstream("a");
            h.env("KS_PASS", "changeit");
            h.start("server: {host: 127.0.0.1, port: 0}\nadmin:\n  host: 127.0.0.1\n  port: 0\n  auth: {bearerToken: \"" + TOKEN + "\"}\n"
                    + "  tls: {keyStore: \"" + ks + "\", keyStorePassword: \"${KS_PASS}\"}\n" + routeTo("UP_A", ""));
            SSLContext trustAll = SSLContext.getInstance("TLS");
            trustAll.init(null, new TrustManager[] {new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] chain, String authType) {
                }

                public void checkServerTrusted(X509Certificate[] chain, String authType) {
                }

                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }}, null);
            HttpClient tls = HttpClient.newBuilder().sslContext(trustAll).build();
            HttpResponse<String> r = tls.send(HttpRequest.newBuilder(URI.create("https://127.0.0.1:" + h.adminPort() + "/admin/routes"))
                    .header("Authorization", "Bearer " + TOKEN).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, r.statusCode());
            assertTrue(r.body().contains("\"api\""));
            assertThrows(IOException.class, () -> http("GET", "http://127.0.0.1:" + h.adminPort() + "/admin/routes", "Authorization", "Bearer " + TOKEN), "plain HTTP is not served on a TLS listener");
        }
    }

    // --------------------------------------------------------------------------------------------- reload

    @Test
    void reloadSwapsRoutesAtomicallyAndReportsVersions(@TempDir Path dir) throws Exception {
        try (ProxyHarness h = new ProxyHarness(dir)) {
            FaultUpstream a = h.upstream("a");
            FaultUpstream b = h.upstream("b");
            h.start(routeTo("UP_A", ""));
            assertEquals("a", h.get("/api/x").headers().firstValue("x-upstream-id").orElse(""));
            assertEquals(404, h.get("/new/x").statusCode());
            h.rewrite(routeTo("UP_B", "  - id: fresh\n    match: {pathPrefix: /new}\n    upstreams: [{id: u, url: \"http://127.0.0.1:${UP_A}\"}]\n"));
            JsonObject r = JsonParser.parseString(h.admin("POST", "/admin/reload").body()).getAsJsonObject();
            assertTrue(r.get("success").getAsBoolean());
            assertTrue(r.get("changed").getAsBoolean());
            assertEquals(2, r.get("version").getAsInt());
            assertEquals("b", h.get("/api/x").headers().firstValue("x-upstream-id").orElse(""));
            assertEquals(200, h.get("/new/x").statusCode());
            assertEquals(2, JsonParser.parseString(h.admin("GET", "/admin/config").body()).getAsJsonObject().get("version").getAsInt());
            assertEquals(2.0, h.metric("routeplane_config_version"));
            // reloading an identical file is a no-op that does not bump the version
            JsonObject same = JsonParser.parseString(h.admin("POST", "/admin/reload").body()).getAsJsonObject();
            assertTrue(same.get("success").getAsBoolean());
            assertFalse(same.get("changed").getAsBoolean());
            assertEquals(2, same.get("version").getAsInt());
            assertTrue(a.stats().requests() >= 1 && b.stats().requests() >= 1);
        }
    }

    @Test
    void anInvalidReloadIsRejectedWithPositionedErrorsAndTheOldConfigKeepsServing(@TempDir Path dir) throws Exception {
        try (ProxyHarness h = new ProxyHarness(dir)) {
            h.upstream("a");
            h.start(routeTo("UP_A", ""));
            h.rewrite("routes:\n  - id: api\n    match: {pathPrefix: api}\n    upstreams: [{id: u, url: \"ftp://nowhere\"}]\n    polcy: round-robin\n");
            HttpResponse<String> resp = h.admin("POST", "/admin/reload");
            assertEquals(422, resp.statusCode());
            JsonObject r = JsonParser.parseString(resp.body()).getAsJsonObject();
            assertFalse(r.get("success").getAsBoolean());
            assertEquals("rejected", r.get("outcome").getAsString());
            String errors = r.getAsJsonArray("errors").toString();
            assertTrue(errors.contains("pathPrefix") && errors.contains("must start with '/'"), errors);
            assertTrue(errors.contains("unknown key 'polcy'") && errors.contains("did you mean 'policy'"), errors);
            assertTrue(errors.matches("(?s).*routeplane\\.yaml:\\d+:\\d+.*"), "errors carry file, line and column: " + errors);
            assertEquals(200, h.get("/api/x").statusCode(), "the previous configuration is still active");
            assertEquals(1, JsonParser.parseString(h.admin("GET", "/admin/config").body()).getAsJsonObject().get("version").getAsInt());
            assertEquals(1.0, h.metric("routeplane_config_reloads_total{result=\"failed\"}"));
            h.rewrite("routes: [\n");
            assertEquals(422, h.admin("POST", "/admin/reload").statusCode(), "even unparseable YAML is a clean rejection");
            h.rewrite("");
            assertEquals(422, h.admin("POST", "/admin/reload").statusCode());
            assertEquals(200, h.get("/api/x").statusCode());
            String last = JsonParser.parseString(h.admin("GET", "/admin/config").body()).getAsJsonObject().getAsJsonObject("lastReload").toString();
            assertTrue(last.contains("\"success\":false"));
        }
    }

    @Test
    void changesToStartupOnlySettingsAreRefusedExplicitly(@TempDir Path dir) throws Exception {
        try (ProxyHarness h = new ProxyHarness(dir)) {
            h.upstream("a");
            h.start(routeTo("UP_A", ""));
            h.rewrite("server: {host: 127.0.0.1, port: 0, maxHeaderBytes: 4096}\nadmin: {host: 127.0.0.1, port: 0}\n" + routeTo("UP_A", ""));
            HttpResponse<String> resp = h.admin("POST", "/admin/reload");
            assertEquals(409, resp.statusCode());
            JsonObject r = JsonParser.parseString(resp.body()).getAsJsonObject();
            assertEquals("restart_required", r.get("outcome").getAsString());
            assertTrue(r.getAsJsonArray("errors").toString().contains("server"));
            assertEquals(200, h.get("/api/x").statusCode());
        }
    }

    @Test
    void reloadKeepsStateOfUnchangedUpstreamsButResetsChangedOnes(@TempDir Path dir) throws Exception {
        try (ProxyHarness h = new ProxyHarness(dir)) {
            FaultUpstream a = h.upstream("a", FaultProfile.DEFAULT.with("errorRate", 1.0).with("errorStatus", 500));
            String breaker = "    circuitBreaker: {minimumRequests: 3, slidingWindow: {type: count, size: 5}, openDuration: 30s}\n";
            String cfg = "routes:\n  - id: api\n    match: {pathPrefix: /api}\n    upstreams: [{id: u, url: \"http://127.0.0.1:${UP_A}\", weight: %d, maxConcurrent: %d}]\n" + breaker;
            h.start(cfg.formatted(1, 100));
            for (int i = 0; i < 3; i++) {
                h.get("/api/x");
            }
            assertEquals(1.0, h.metric("routeplane_circuit_breaker_state{route=\"api\",upstream=\"u\"}"), "breaker opened");
            h.rewrite(cfg.formatted(5, 100)); // weight changes only
            assertTrue(h.app().configs().reload("test").success());
            assertEquals(1.0, h.metric("routeplane_circuit_breaker_state{route=\"api\",upstream=\"u\"}"), "the open breaker survived a reload that did not touch the upstream");
            assertEquals("breaker_open", h.get("/api/x").headers().firstValue("x-routeplane-error").orElse(""));
            h.rewrite(cfg.formatted(5, 50)); // concurrency limit changes: a different upstream spec
            assertTrue(h.app().configs().reload("test").success());
            assertEquals(0.0, h.metric("routeplane_circuit_breaker_state{route=\"api\",upstream=\"u\"}"), "a changed upstream starts with a fresh breaker");
            assertEquals(500, h.get("/api/x").statusCode());
            assertTrue(a.stats().requests() >= 4);
        }
    }

    @Test
    void reloadIsAtomicUnderConcurrentTraffic(@TempDir Path dir) throws Exception {
        try (ProxyHarness h = new ProxyHarness(dir)) {
            h.upstream("a");
            h.upstream("b");
            h.start(routeTo("UP_A", ""));
            AtomicBoolean stop = new AtomicBoolean();
            AtomicInteger ok = new AtomicInteger();
            AtomicInteger bad = new AtomicInteger();
            List<String> problems = java.util.Collections.synchronizedList(new ArrayList<>());
            ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor();
            List<Future<?>> fs = new ArrayList<>();
            for (int t = 0; t < 8; t++) {
                fs.add(ex.submit(() -> {
                    while (!stop.get()) {
                        try {
                            HttpResponse<String> r = h.get("/api/x");
                            String who = r.headers().firstValue("x-upstream-id").orElse("");
                            if (r.statusCode() == 200 && (who.equals("a") || who.equals("b"))) {
                                ok.incrementAndGet();
                            } else {
                                bad.incrementAndGet();
                                problems.add(r.statusCode() + " from '" + who + "'");
                            }
                        } catch (Exception e) {
                            bad.incrementAndGet();
                            problems.add(e.toString());
                        }
                    }
                }));
            }
            int applied = 0;
            for (int i = 0; i < 40; i++) {
                if (i % 7 == 6) {
                    h.rewrite("routes: [{id: x\n"); // invalid: must be rejected without any visible effect
                    assertEquals(422, h.admin("POST", "/admin/reload").statusCode());
                } else {
                    h.rewrite(routeTo(i % 2 == 0 ? "UP_B" : "UP_A", ""));
                    if (JsonParser.parseString(h.admin("POST", "/admin/reload").body()).getAsJsonObject().get("changed").getAsBoolean()) {
                        applied++;
                    }
                }
                Thread.sleep(15);
            }
            stop.set(true);
            for (Future<?> f : fs) {
                f.get();
            }
            ex.shutdown();
            assertTrue(ok.get() > 200, "traffic flowed throughout: " + ok.get());
            assertEquals(0, bad.get(), "no request may fail because of a reload: " + problems.stream().limit(5).toList());
            assertTrue(applied >= 30, "applied " + applied);
        }
    }

    @Test
    void theFileWatcherPicksUpEditsWithoutAnApiCall(@TempDir Path dir) throws Exception {
        try (ProxyHarness h = new ProxyHarness(dir)) {
            h.upstream("a");
            h.upstream("b");
            h.start("server: {host: 127.0.0.1, port: 0, watchConfig: true, watchInterval: 500ms}\nadmin: {host: 127.0.0.1, port: 0}\n" + routeTo("UP_A", ""));
            assertEquals("a", h.get("/api/x").headers().firstValue("x-upstream-id").orElse(""));
            Thread.sleep(600);
            Files.writeString(dir.resolve("routeplane.yaml"), "server: {host: 127.0.0.1, port: 0, watchConfig: true, watchInterval: 500ms}\nadmin: {host: 127.0.0.1, port: 0}\n" + routeTo("UP_B", ""));
            assertTrue(ProxyHarness.eventually(Duration.ofSeconds(8), () -> {
                try {
                    return h.get("/api/x").headers().firstValue("x-upstream-id").orElse("").equals("b");
                } catch (Exception e) {
                    return false;
                }
            }), "the edit was applied by the watcher");
            assertEquals(2, h.app().configs().current().version());
        }
    }

    @Test
    void readinessTurnsRedWhileDraining(@TempDir Path dir) throws Exception {
        try (ProxyHarness h = new ProxyHarness(dir)) {
            h.upstream("a", FaultProfile.DEFAULT.with("delayMs", 700));
            h.start(routeTo("UP_A", ""));
            assertEquals(200, h.admin("GET", "/health/ready").statusCode());
            ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor();
            Future<Integer> slow = ex.submit(() -> h.get("/api/x").statusCode());
            assertTrue(ProxyHarness.eventually(Duration.ofSeconds(5), () -> h.app().proxy().inFlight() == 1));
            h.app().proxy().drain();
            HttpResponse<String> ready = h.admin("GET", "/health/ready");
            assertEquals(503, ready.statusCode());
            assertEquals("draining", JsonParser.parseString(ready.body()).getAsJsonObject().get("status").getAsString());
            HttpResponse<String> refused = h.get("/api/x");
            assertEquals(503, refused.statusCode());
            assertEquals("draining", refused.headers().firstValue("x-routeplane-error").orElse(""));
            assertEquals(200, slow.get(), "in-flight work is not disturbed by draining");
            ex.shutdown();
        }
    }
}
