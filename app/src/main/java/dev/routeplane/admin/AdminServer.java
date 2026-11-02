package dev.routeplane.admin;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import dev.routeplane.config.ConfigLoader;
import dev.routeplane.config.RouteplaneConfig.AdminConfig;
import dev.routeplane.observability.Json;
import dev.routeplane.observability.MetricsRegistry;
import dev.routeplane.observability.StructuredLogger;
import dev.routeplane.proxy.AdminViews;
import dev.routeplane.proxy.ConfigManager;
import dev.routeplane.proxy.ConfigView;
import dev.routeplane.proxy.RuntimeState;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

/**
 * The administrative listener, separate from the proxy listener and bound to loopback by default.
 *
 * <p>Protection (docs/security.md): binding to a non-loopback address is refused by configuration validation unless a
 * bearer token and TLS are configured; {@code /admin/*} and {@code /metrics} require the token whenever one is
 * configured (compared in constant time); the {@code Host} header must be on an allow-list for loopback listeners
 * (defence against DNS rebinding); state-changing requests carrying an {@code Origin} header - what browsers add to
 * cross-site requests - are refused (CSRF); {@code POST /admin/reload} takes no parameters and can only re-read the
 * configured file, never a path or body supplied by the caller. Health endpoints are unauthenticated so orchestrators
 * can probe them.
 */
public final class AdminServer implements AutoCloseable {
    private final HttpServer server;
    private final AdminConfig cfg;
    private final ConfigManager configs;
    private final MetricsRegistry metrics;
    private final StructuredLogger log;
    private final BooleanSupplier draining;
    private final byte[] tokenDigest;
    private final Set<String> allowedHosts;
    private final boolean enforceHostCheck;

    private AdminServer(HttpServer server, AdminConfig cfg, ConfigManager configs, MetricsRegistry metrics, StructuredLogger log, BooleanSupplier draining) {
        this.server = server;
        this.cfg = cfg;
        this.configs = configs;
        this.metrics = metrics;
        this.log = log;
        this.draining = draining;
        this.tokenDigest = cfg.bearerToken() == null ? null : sha256(cfg.bearerToken());
        boolean loopback = ConfigLoader.isLoopback(cfg.host());
        this.enforceHostCheck = loopback || !cfg.allowedHosts().isEmpty();
        this.allowedHosts = new java.util.HashSet<>();
        if (cfg.allowedHosts().isEmpty()) {
            allowedHosts.addAll(List.of("localhost", "127.0.0.1", "[::1]", "::1"));
            if (!cfg.host().equals("0.0.0.0") && !cfg.host().equals("::")) {
                allowedHosts.add(cfg.host().toLowerCase(Locale.ROOT));
            }
        } else {
            cfg.allowedHosts().forEach(h -> allowedHosts.add(h.toLowerCase(Locale.ROOT)));
        }
    }

    public static AdminServer start(AdminConfig cfg, ConfigManager configs, MetricsRegistry metrics, StructuredLogger log,
                                    BooleanSupplier draining) throws IOException {
        InetSocketAddress addr = new InetSocketAddress(InetAddress.getByName(cfg.host()), cfg.port());
        HttpServer server;
        if (cfg.tls() != null) {
            SSLContext ctx = sslContext(cfg);
            HttpsServer https = HttpsServer.create(addr, 64);
            https.setHttpsConfigurator(new HttpsConfigurator(ctx) {
                @Override
                public void configure(HttpsParameters params) {
                    SSLParameters p = ctx.getDefaultSSLParameters();
                    p.setProtocols(new String[] {"TLSv1.3", "TLSv1.2"});
                    params.setSSLParameters(p);
                }
            });
            server = https;
        } else {
            server = HttpServer.create(addr, 64);
        }
        server.setExecutor(Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("admin-", 0).factory()));
        AdminServer a = new AdminServer(server, cfg, configs, metrics, log, draining);
        a.routes();
        server.start();
        return a;
    }

    private static SSLContext sslContext(AdminConfig cfg) throws IOException {
        try (InputStream in = Files.newInputStream(cfg.tls().keyStore())) {
            KeyStore ks = KeyStore.getInstance(cfg.tls().keyStoreType());
            char[] pw = cfg.tls().keyStorePassword().toCharArray();
            ks.load(in, pw);
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, pw);
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(kmf.getKeyManagers(), null, null);
            return ctx;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("cannot initialise admin TLS from " + cfg.tls().keyStore() + ": " + e.getMessage(), e);
        }
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public boolean tls() {
        return cfg.tls() != null;
    }

    // ----------------------------------------------------------------------------------------------- routing

    private void routes() {
        server.createContext("/health/live", ex -> handle(ex, false, false, () -> json(ex, 200, Map.of("status", "live"))));
        server.createContext("/health/ready", ex -> handle(ex, false, false, () -> {
            RuntimeState st = configs.current();
            if (st == null || draining.getAsBoolean()) {
                json(ex, 503, Map.of("status", draining.getAsBoolean() ? "draining" : "starting"));
            } else {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("status", "ready");
                m.put("configVersion", st.version());
                json(ex, 200, m);
            }
        }));
        server.createContext("/metrics", ex -> handle(ex, true, false, () -> {
            byte[] b = metrics.scrape().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "text/plain; version=0.0.4; charset=utf-8");
            send(ex, 200, b);
        }));
        server.createContext("/admin/routes", ex -> handle(ex, true, false, () -> json(ex, 200, AdminViews.routes(configs.current()))));
        server.createContext("/admin/upstreams", ex -> handle(ex, true, false, () -> json(ex, 200, AdminViews.upstreams(configs.current()))));
        server.createContext("/admin/circuits", ex -> handle(ex, true, false, () -> json(ex, 200, AdminViews.circuits(configs.current()))));
        server.createContext("/admin/config", ex -> handle(ex, true, false, () -> {
            RuntimeState st = configs.current();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("version", st.version());
            m.put("sha256", st.fileSha256());
            m.put("loadedAtMillis", st.loadedAtMillis());
            m.put("file", configs.file().toString());
            var last = configs.lastReload();
            m.put("lastReload", last == null ? null : Map.of("success", last.success(), "changed", last.changed(), "outcome", last.outcome(),
                    "errors", last.errors(), "atMillis", last.atMillis()));
            m.put("config", ConfigView.render(st.config()));
            json(ex, 200, m);
        }));
        server.createContext("/admin/reload", ex -> handle(ex, true, true, () -> {
            ConfigManager.ReloadResult r = configs.reload("admin-api");
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("success", r.success());
            m.put("changed", r.changed());
            m.put("version", r.version());
            m.put("outcome", r.outcome());
            m.put("errors", r.errors());
            int status = r.success() ? 200 : switch (r.outcome()) {
                case "rejected" -> 422;
                case "restart_required" -> 409;
                default -> 500;
            };
            json(ex, status, m);
        }));
        server.createContext("/", ex -> handle(ex, false, false, () -> json(ex, 404, Map.of("error", "not_found"))));
    }

    @FunctionalInterface
    private interface Action {
        void run() throws IOException;
    }

    private void handle(HttpExchange ex, boolean auth, boolean mutating, Action action) throws IOException {
        try {
            String method = ex.getRequestMethod();
            if (mutating ? !method.equals("POST") : !(method.equals("GET") || method.equals("HEAD"))) {
                ex.getResponseHeaders().set("Allow", mutating ? "POST" : "GET, HEAD");
                json(ex, 405, Map.of("error", "method_not_allowed"));
                return;
            }
            if (enforceHostCheck && !hostAllowed(ex.getRequestHeaders().getFirst("Host"))) {
                json(ex, 403, Map.of("error", "host_not_allowed", "message", "the Host header is not on the admin allow-list"));
                return;
            }
            if (mutating && ex.getRequestHeaders().getFirst("Origin") != null) {
                json(ex, 403, Map.of("error", "cross_origin_request_refused"));
                return;
            }
            if (auth && tokenDigest != null && !authorized(ex.getRequestHeaders())) {
                ex.getResponseHeaders().set("WWW-Authenticate", "Bearer");
                log.warn("admin_auth_failed", "path", ex.getRequestURI().getPath(), "client", ex.getRemoteAddress().getAddress().getHostAddress());
                json(ex, 401, Map.of("error", "unauthorized"));
                return;
            }
            if (mutating) {
                ex.getRequestBody().readNBytes(1024); // bodies are ignored
            }
            action.run();
        } catch (RuntimeException e) {
            log.error("admin_request_failed", e, "path", ex.getRequestURI().getPath());
            try {
                json(ex, 500, Map.of("error", "internal"));
            } catch (IOException | RuntimeException ignored) {
                // response already started
            }
        } finally {
            ex.close();
        }
    }

    private boolean hostAllowed(String hostHeader) {
        if (hostHeader == null) {
            return false;
        }
        String h = hostHeader.toLowerCase(Locale.ROOT);
        int colon = h.lastIndexOf(':');
        if (colon > 0 && !h.endsWith("]")) {
            h = h.substring(0, colon);
        }
        return allowedHosts.contains(h);
    }

    private boolean authorized(Headers h) {
        String v = h.getFirst("Authorization");
        if (v == null || !v.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return false;
        }
        return MessageDigest.isEqual(sha256(v.substring(7).trim()), tokenDigest);
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void json(HttpExchange ex, int status, Object body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "application/json");
        send(ex, status, Json.write(body, true).getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange ex, int status, byte[] bytes) throws IOException {
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        if (ex.getRequestMethod().equals("HEAD")) {
            ex.getResponseHeaders().set("Content-Length", Integer.toString(bytes.length));
            ex.sendResponseHeaders(status, -1);
            return;
        }
        ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
