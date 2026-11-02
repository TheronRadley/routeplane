package dev.routeplane.bench;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Facts about the machine and runtime that a reader needs to interpret the numbers. Best effort, Linux oriented. */
public final class Environment {
    private Environment() {
    }

    public static Map<String, Object> capture() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("capturedAt", Instant.now().toString());
        m.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version") + " " + System.getProperty("os.arch"));
        m.put("kernel", firstLine(Path.of("/proc/version")));
        m.put("cpuModel", cpuModel());
        m.put("availableProcessors", Runtime.getRuntime().availableProcessors());
        m.put("memTotalMb", memTotalMb());
        m.put("cgroupCpuMax", firstLine(Path.of("/sys/fs/cgroup/cpu.max")));
        m.put("cgroupMemoryMax", firstLine(Path.of("/sys/fs/cgroup/memory.max")));
        m.put("runnerJava", System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ", " + System.getProperty("java.vm.name") + ")");
        m.put("loadGenerator", "dev.routeplane.bench.LoadGenerator: open-loop, JDK HttpClient (HTTP/1.1), virtual-thread executor, latency measured from intended send time");
        m.put("topology", "load generator, proxy and upstreams run on the SAME machine as separate processes; they compete for the same CPUs");
        m.put("git", git());
        return m;
    }

    static String cpuModel() {
        try {
            for (String l : Files.readAllLines(Path.of("/proc/cpuinfo"))) {
                if (l.startsWith("model name")) {
                    return l.substring(l.indexOf(':') + 1).trim();
                }
            }
        } catch (IOException ignored) {
            // not Linux
        }
        return "unknown";
    }

    static long memTotalMb() {
        try {
            for (String l : Files.readAllLines(Path.of("/proc/meminfo"))) {
                if (l.startsWith("MemTotal:")) {
                    return Long.parseLong(l.split("\\s+")[1]) / 1024;
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // not Linux
        }
        return -1;
    }

    private static String firstLine(Path p) {
        try {
            return Files.readAllLines(p).get(0).trim();
        } catch (IOException | RuntimeException e) {
            return "n/a";
        }
    }

    private static String git() {
        try {
            Process p = new ProcessBuilder("git", "rev-parse", "--short", "HEAD").redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor();
            return out.isEmpty() ? "n/a" : out;
        } catch (IOException | InterruptedException e) {
            return "n/a";
        }
    }
}
