package dev.routeplane.bench;

import com.google.gson.Gson;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The processes of one run: fault-injection upstreams and (unless the scenario is a direct baseline) the proxy, each in its
 * own JVM so that CPU, memory and GC can be attributed per process. Launch commands come from, in order: the system
 * properties {@code routeplane.bench.proxyCmd} / {@code routeplane.bench.upstreamCmd}, the environment variables
 * {@code ROUTEPLANE_PROXY_CMD} / {@code ROUTEPLANE_UPSTREAM_CMD} (space-separated), or the installDist scripts.
 */
public final class Topology implements AutoCloseable {
    public record Upstream(String id, int port, int controlPort, Process process) {
    }

    private static final Gson GSON = new Gson();
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(2)).build();
    private final Map<String, Upstream> upstreams = new LinkedHashMap<>();
    private Process proxy;
    private int proxyPort;
    private int adminPort;

    public static List<String> command(String sysProp, String envVar, String defaultScript) {
        String v = System.getProperty(sysProp);
        if (v == null || v.isBlank()) {
            v = System.getenv(envVar);
        }
        if (v == null || v.isBlank()) {
            return List.of(defaultScript);
        }
        return List.of(v.trim().split("\\s+"));
    }

    public static Topology start(Scenario s, String proxyConfig, Path workDir) throws IOException, InterruptedException {
        Files.createDirectories(workDir);
        Topology t = new Topology();
        try {
            List<String> upCmd = command("routeplane.bench.upstreamCmd", "ROUTEPLANE_UPSTREAM_CMD", "fault-upstream/build/install/fault-upstream/bin/fault-upstream");
            Map<String, String> env = new LinkedHashMap<>();
            for (Scenario.UpstreamSpec spec : s.upstreams) {
                int port = freePort();
                int control = freePort();
                List<String> cmd = new ArrayList<>(upCmd);
                cmd.addAll(List.of("--id", spec.id(), "--port", Integer.toString(port), "--control-port", Integer.toString(control)));
                Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(workDir.resolve("upstream-" + spec.id() + ".log").toFile()).start();
                t.upstreams.put(spec.id(), new Upstream(spec.id(), port, control, p));
                env.put("UP_" + spec.id().toUpperCase(Locale.ROOT).replace('-', '_'), Integer.toString(port));
            }
            for (Upstream u : t.upstreams.values()) {
                t.awaitHttp("http://127.0.0.1:" + u.controlPort() + "/control/stats", 20, u.process());
                Scenario.UpstreamSpec spec = s.upstreams.stream().filter(x -> x.id().equals(u.id())).findFirst().orElseThrow();
                if (!spec.profile().isEmpty()) {
                    t.control(u.id(), "PUT", "/control/profile", GSON.toJson(spec.profile()));
                }
            }
            if (!s.direct) {
                t.proxyPort = freePort();
                t.adminPort = freePort();
                env.put("PROXY_PORT", Integer.toString(t.proxyPort));
                env.put("ADMIN_PORT", Integer.toString(t.adminPort));
                Path cfg = workDir.resolve("proxy.yaml");
                Files.writeString(cfg, proxyConfig);
                List<String> cmd = new ArrayList<>(command("routeplane.bench.proxyCmd", "ROUTEPLANE_PROXY_CMD", "app/build/install/routeplane/bin/routeplane"));
                cmd.addAll(List.of("serve", "--config", cfg.toString()));
                ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(workDir.resolve("proxy.log").toFile());
                pb.environment().putAll(env);
                pb.environment().put("JAVA_OPTS", String.join(" ", s.proxyJvmOptions));
                t.proxy = pb.start();
                t.awaitHttp("http://127.0.0.1:" + t.adminPort + "/health/ready", 40, t.proxy);
            }
            return t;
        } catch (IOException | InterruptedException | RuntimeException e) {
            t.close();
            throw e;
        }
    }

    public int proxyPort() {
        return proxyPort;
    }

    public int adminPort() {
        return adminPort;
    }

    public Process proxyProcess() {
        return proxy;
    }

    public Map<String, Upstream> upstreams() {
        return upstreams;
    }

    public URI adminUri(String path) {
        return URI.create("http://127.0.0.1:" + adminPort + path);
    }

    /** Calls an upstream's control API; returns the response body. */
    public String control(String upstreamId, String method, String path, String body) throws IOException, InterruptedException {
        Upstream u = upstreams.get(upstreamId);
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + u.controlPort() + path)).timeout(Duration.ofSeconds(5));
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() / 100 != 2) {
            throw new IOException("control " + method + " " + path + " on " + upstreamId + " -> " + r.statusCode() + " " + r.body());
        }
        return r.body();
    }

    public String get(URI uri) throws IOException, InterruptedException {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.ofString());
        return r.body();
    }

    private void awaitHttp(String url, int seconds, Process watched) throws IOException, InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < end) {
            if (!watched.isAlive()) {
                throw new IOException("a child process exited early (exit code " + watched.exitValue() + ") while waiting for " + url + "; see the logs in the work directory");
            }
            try {
                HttpResponse<Void> r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(1)).build(), HttpResponse.BodyHandlers.discarding());
                if (r.statusCode() == 200) {
                    return;
                }
            } catch (IOException ignored) {
                // not up yet
            }
            Thread.sleep(100);
        }
        throw new IOException("timed out after " + seconds + " s waiting for " + url);
    }

    static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            s.setReuseAddress(true);
            return s.getLocalPort();
        }
    }

    @Override
    public void close() {
        List<Process> all = new ArrayList<>();
        if (proxy != null) {
            all.add(proxy);
        }
        upstreams.values().forEach(u -> all.add(u.process()));
        all.forEach(Process::destroy);
        for (Process p : all) {
            try {
                if (!p.waitFor(5, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                    p.waitFor(5, TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                p.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }
    }
}
