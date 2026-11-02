package dev.routeplane.observability;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ESTABLISHED TCP connections per local and remote port, read from {@code /proc/net/tcp[6]} (Linux only).
 *
 * <p>The JDK HTTP server and client do not expose connection counts, so the kernel's view is used instead: connections
 * whose local port is the proxy port are downstream clients; connections whose remote port is an upstream's port are
 * that upstream's pool (active plus idle keep-alive). Attribution is by port only. Results are cached for one second
 * so frequent scrapes stay cheap.
 */
public final class TcpStats {
    public record Counts(Map<Integer, Integer> byLocalPort, Map<Integer, Integer> byRemotePort) {
    }

    private final Path[] sources;
    private volatile long cachedAtNanos;
    private volatile Counts cached;

    public TcpStats() {
        this(Path.of("/proc/net/tcp"), Path.of("/proc/net/tcp6"));
    }

    TcpStats(Path... sources) {
        this.sources = sources;
    }

    public boolean available() {
        return Files.isReadable(sources[0]);
    }

    public Counts snapshot() {
        long now = System.nanoTime();
        Counts c = cached;
        if (c != null && now - cachedAtNanos < 1_000_000_000L) {
            return c;
        }
        Map<Integer, Integer> local = new HashMap<>();
        Map<Integer, Integer> remote = new HashMap<>();
        for (Path p : sources) {
            try {
                List<String> lines = Files.readAllLines(p);
                for (int i = 1; i < lines.size(); i++) {
                    String[] f = lines.get(i).trim().split("\\s+");
                    if (f.length > 3 && f[3].equals("01")) {
                        local.merge(port(f[1]), 1, Integer::sum);
                        remote.merge(port(f[2]), 1, Integer::sum);
                    }
                }
            } catch (IOException | RuntimeException e) {
                // table unavailable (non-Linux or restricted); report what we have
            }
        }
        cached = new Counts(local, remote);
        cachedAtNanos = now;
        return cached;
    }

    static int port(String addr) {
        return Integer.parseInt(addr.substring(addr.lastIndexOf(':') + 1), 16);
    }
}
