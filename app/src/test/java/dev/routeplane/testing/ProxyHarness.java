package dev.routeplane.testing;

import dev.routeplane.RouteplaneApp;
import dev.routeplane.fault.FaultProfile;
import dev.routeplane.fault.FaultUpstream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Starts fault upstreams and a real proxy from a YAML configuration. Upstream ports are injected through the
 * {@code ${UP_<NAME>}} environment substitution of the configuration itself, so tests exercise the real parsing path.
 */
public final class ProxyHarness implements AutoCloseable {
    public static final String HEADER = "server:\n  host: 127.0.0.1\n  port: 0\nadmin:\n  host: 127.0.0.1\n  port: 0\nlogging:\n  level: info\n";

    private final Path dir;
    private final Map<String, FaultUpstream> upstreams = new HashMap<>();
    private final Map<String, String> env = new HashMap<>();
    private final ByteArrayOutputStream logs = new ByteArrayOutputStream();
    private final List<AutoCloseable> extra = new ArrayList<>();
    private RouteplaneApp app;
    private Path configFile;
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build();

    public ProxyHarness(Path dir) {
        this.dir = dir;
    }

    /** Starts a fault upstream and exposes its port as {@code ${UP_<NAME>}} (upper case). */
    public FaultUpstream upstream(String name, FaultProfile profile) throws IOException {
        FaultUpstream u = FaultUpstream.start(FaultUpstream.Options.of(name, 0).withProfile(profile).withControlPort(0));
        upstreams.put(name, u);
        env.put("UP_" + name.toUpperCase(), Integer.toString(u.port()));
        return u;
    }

    public FaultUpstream upstream(String name) throws IOException {
        return upstream(name, FaultProfile.DEFAULT);
    }

    public FaultUpstream up(String name) {
        return upstreams.get(name);
    }

    public ProxyHarness env(String key, String value) {
        env.put(key, value);
        return this;
    }

    /** Writes {@code yaml} (with {@link #HEADER} prepended unless it declares its own server section) and starts the proxy. */
    public RouteplaneApp start(String yaml) throws IOException {
        String full = yaml.startsWith("server:") || yaml.contains("\nserver:") ? yaml : HEADER + yaml;
        configFile = dir.resolve("routeplane.yaml");
        Files.writeString(configFile, full);
        app = RouteplaneApp.start(configFile, env::get, logs);
        return app;
    }

    public void rewrite(String yaml) throws IOException {
        String full = yaml.startsWith("server:") || yaml.contains("\nserver:") ? yaml : HEADER + yaml;
        Files.writeString(configFile, full);
    }

    public RouteplaneApp app() {
        return app;
    }

    public int port() {
        return app.proxyPort();
    }

    public int adminPort() {
        return app.adminPort();
    }

    public RawClient raw() throws IOException {
        return new RawClient(app.proxyPort(), 10_000);
    }

    public String logs() {
        return logs.toString(StandardCharsets.UTF_8);
    }

    public HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port() + path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    public HttpResponse<String> admin(String method, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + adminPort() + path))
                .method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
    }

    public String metrics() {
        try {
            return admin("GET", "/metrics").body();
        } catch (Exception e) {
            throw new IllegalStateException("cannot scrape /metrics", e);
        }
    }

    /** Value of the first sample whose line starts with {@code prefix} (name plus labels), or NaN. Unchecked so it can be used in polling lambdas. */
    public double metric(String prefix) {
        for (String line : metrics().split("\n")) {
            if (line.startsWith(prefix)) {
                return Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1));
            }
        }
        return Double.NaN;
    }

    public static boolean eventually(Duration timeout, BooleanSupplier condition) throws InterruptedException {
        long end = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < end) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(20);
        }
        return condition.getAsBoolean();
    }

    @Override
    public void close() {
        if (app != null) {
            app.shutdown(Duration.ofSeconds(2));
        }
        for (FaultUpstream u : upstreams.values()) {
            u.close();
        }
        for (AutoCloseable c : extra) {
            try {
                c.close();
            } catch (Exception ignored) {
                // best effort
            }
        }
    }
}
