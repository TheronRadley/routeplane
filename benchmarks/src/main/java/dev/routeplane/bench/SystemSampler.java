package dev.routeplane.bench;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Samples, once per second during the measurement window, the CPU time and resident memory of each child process
 * (from {@code /proc}, Linux only) and scrapes the proxy's {@code /metrics}. CPU is the difference between the first and
 * last reading, so the figure covers exactly the window. Assumes the Linux default of 100 clock ticks per second.
 */
public final class SystemSampler {
    private static final double CLK_TCK = 100.0;

    public static final class Snapshot {
        public final Map<String, Double> cpuSeconds = new TreeMap<>();
        public final Map<String, Long> rssMax = new TreeMap<>();
        public double systemBusyFraction;
        public Map<String, Double> metricsStart = new HashMap<>();
        public Map<String, Double> metricsEnd = new HashMap<>();
        public final List<Map<String, Double>> gaugesPerSecond = new ArrayList<>();
        public double heapUsedMax;
        public double threadsMax;
        public double tcpDownstreamMax;
        public double tcpUpstreamMax;
        public double shadowQueueMax;
        public double windowSeconds;
    }

    private final Map<String, Long> pids;
    private final URI metricsUri;
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(1)).build();
    private final Map<String, Double> cpuStart = new HashMap<>();
    private final Snapshot snap = new Snapshot();
    private long[] sysStart;
    private long startNanos;
    private Thread thread;
    private volatile boolean running;

    public SystemSampler(Map<String, Long> pids, URI metricsUri) {
        this.pids = pids;
        this.metricsUri = metricsUri;
    }

    public void start() {
        startNanos = System.nanoTime();
        sysStart = readSystemCpu();
        for (var e : pids.entrySet()) {
            cpuStart.put(e.getKey(), cpuSeconds(e.getValue()));
        }
        snap.metricsStart = scrape();
        running = true;
        thread = new Thread(this::loop, "bench-sampler");
        thread.setDaemon(true);
        thread.start();
    }

    private void loop() {
        long next = System.nanoTime() + 1_000_000_000L;
        while (running) {
            try {
                long wait = next - System.nanoTime();
                if (wait > 0) {
                    Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000));
                }
            } catch (InterruptedException e) {
                return;
            }
            next += 1_000_000_000L;
            sampleOnce();
        }
    }

    private void sampleOnce() {
        for (var e : pids.entrySet()) {
            snap.rssMax.merge(e.getKey(), rss(e.getValue()), Math::max);
        }
        Map<String, Double> m = scrape();
        Map<String, Double> g = new TreeMap<>();
        double tcpDown = 0;
        double tcpUp = 0;
        double shadowQ = 0;
        for (var e : m.entrySet()) {
            String k = e.getKey();
            if (k.startsWith("routeplane_circuit_breaker_state") || k.startsWith("routeplane_shadow_queue_depth") || k.startsWith("routeplane_shadow_in_flight")
                    || k.startsWith("routeplane_upstream_in_flight") || k.equals("routeplane_active_requests") || k.startsWith("routeplane_upstream_healthy")) {
                g.put(k, e.getValue());
            }
            if (k.startsWith("routeplane_tcp_connections{role=\"downstream\"")) {
                tcpDown = Math.max(tcpDown, e.getValue());
            } else if (k.startsWith("routeplane_tcp_connections{role=\"upstream\"")) {
                tcpUp += e.getValue();
            }
            if (k.startsWith("routeplane_shadow_queue_depth")) {
                shadowQ = Math.max(shadowQ, e.getValue());
            }
        }
        snap.gaugesPerSecond.add(g);
        snap.heapUsedMax = Math.max(snap.heapUsedMax, m.getOrDefault("jvm_memory_bytes_used{area=\"heap\"}", 0.0));
        snap.threadsMax = Math.max(snap.threadsMax, m.getOrDefault("jvm_threads_current", 0.0));
        snap.tcpDownstreamMax = Math.max(snap.tcpDownstreamMax, tcpDown);
        snap.tcpUpstreamMax = Math.max(snap.tcpUpstreamMax, tcpUp);
        snap.shadowQueueMax = Math.max(snap.shadowQueueMax, shadowQ);
    }

    public Snapshot stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        snap.windowSeconds = (System.nanoTime() - startNanos) / 1e9;
        for (var e : pids.entrySet()) {
            snap.cpuSeconds.put(e.getKey(), Math.max(0, cpuSeconds(e.getValue()) - cpuStart.getOrDefault(e.getKey(), 0.0)));
            snap.rssMax.merge(e.getKey(), rss(e.getValue()), Math::max);
        }
        long[] end = readSystemCpu();
        if (sysStart != null && end != null) {
            long busy = end[0] - sysStart[0];
            long total = end[1] - sysStart[1];
            snap.systemBusyFraction = total <= 0 ? 0 : (double) busy / total;
        }
        snap.metricsEnd = scrape();
        return snap;
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private Map<String, Double> scrape() {
        if (metricsUri == null) {
            return new HashMap<>();
        }
        try {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(metricsUri).timeout(Duration.ofSeconds(2)).build(), HttpResponse.BodyHandlers.ofString());
            return parsePrometheus(r.body());
        } catch (IOException e) {
            return new HashMap<>();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new HashMap<>();
        }
    }

    /** Parses Prometheus text into {@code name{labels} -> value}; comment lines and malformed lines are skipped. */
    public static Map<String, Double> parsePrometheus(String text) {
        Map<String, Double> out = new HashMap<>();
        for (String line : text.split("\n")) {
            if (line.isEmpty() || line.charAt(0) == '#') {
                continue;
            }
            int sp = line.lastIndexOf(' ');
            if (sp < 0) {
                continue;
            }
            try {
                String v = line.substring(sp + 1);
                double d = v.equals("+Inf") ? Double.POSITIVE_INFINITY : v.equals("-Inf") ? Double.NEGATIVE_INFINITY : Double.parseDouble(v);
                out.put(line.substring(0, sp), d);
            } catch (NumberFormatException ignored) {
                // skip
            }
        }
        return out;
    }

    static double cpuSeconds(long pid) {
        try {
            String s = Files.readString(Path.of("/proc/" + pid + "/stat"));
            String[] f = s.substring(s.lastIndexOf(')') + 2).split(" ");
            return (Long.parseLong(f[11]) + Long.parseLong(f[12])) / CLK_TCK;
        } catch (IOException | RuntimeException e) {
            return 0;
        }
    }

    static long rss(long pid) {
        try {
            for (String line : Files.readAllLines(Path.of("/proc/" + pid + "/status"))) {
                if (line.startsWith("VmRSS:")) {
                    return Long.parseLong(line.split("\\s+")[1]) * 1024L;
                }
            }
        } catch (IOException | RuntimeException e) {
            return 0;
        }
        return 0;
    }

    /** @return {busy jiffies, total jiffies} from the first line of /proc/stat, or null */
    static long[] readSystemCpu() {
        try {
            String line = Files.readAllLines(Path.of("/proc/stat")).get(0);
            String[] f = line.trim().split("\\s+");
            long total = 0;
            for (int i = 1; i < f.length; i++) {
                total += Long.parseLong(f[i]);
            }
            long idle = Long.parseLong(f[4]) + Long.parseLong(f[5]);
            return new long[] {total - idle, total};
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
