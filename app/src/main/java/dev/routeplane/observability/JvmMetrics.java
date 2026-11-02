package dev.routeplane.observability;

import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadMXBean;
import java.nio.file.Files;
import java.nio.file.Path;

/** JVM and process metrics read at scrape time: memory, GC, threads, CPU, file descriptors, resident set size. */
public final class JvmMetrics {
    private JvmMetrics() {
    }

    public static void register(MetricsRegistry r, String version) {
        long startMillis = ManagementFactory.getRuntimeMXBean().getStartTime();
        r.collector(sink -> {
            MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
            MemoryUsage heap = mem.getHeapMemoryUsage();
            MemoryUsage nonHeap = mem.getNonHeapMemoryUsage();
            sink.gauge("jvm_memory_bytes_used", "Used JVM memory by area", heap.getUsed(), "area", "heap");
            sink.gauge("jvm_memory_bytes_used", "Used JVM memory by area", nonHeap.getUsed(), "area", "nonheap");
            sink.gauge("jvm_memory_bytes_committed", "Committed JVM memory by area", heap.getCommitted(), "area", "heap");
            sink.gauge("jvm_memory_bytes_committed", "Committed JVM memory by area", nonHeap.getCommitted(), "area", "nonheap");
            if (heap.getMax() >= 0) {
                sink.gauge("jvm_memory_bytes_max", "Maximum JVM memory by area", heap.getMax(), "area", "heap");
            }
            for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
                sink.counter("jvm_gc_collection_count_total", "Garbage collections", gc.getCollectionCount(), "gc", gc.getName());
                sink.counter("jvm_gc_collection_seconds_total", "Approximate accumulated GC time", gc.getCollectionTime() / 1000.0, "gc", gc.getName());
            }
            ThreadMXBean threads = ManagementFactory.getThreadMXBean();
            sink.gauge("jvm_threads_current", "Live platform threads (virtual threads are not counted)", threads.getThreadCount());
            sink.gauge("jvm_threads_peak", "Peak platform threads", threads.getPeakThreadCount());
            sink.gauge("jvm_classes_loaded", "Currently loaded classes", ManagementFactory.getClassLoadingMXBean().getLoadedClassCount());
            sink.gauge("process_start_time_seconds", "Process start time", startMillis / 1000.0);
            var os = ManagementFactory.getOperatingSystemMXBean();
            if (os instanceof com.sun.management.OperatingSystemMXBean sun) {
                long cpu = sun.getProcessCpuTime();
                if (cpu >= 0) {
                    sink.counter("process_cpu_seconds_total", "Total user and system CPU time of the process", cpu / 1e9);
                }
                double load = sun.getProcessCpuLoad();
                if (load >= 0) {
                    sink.gauge("process_cpu_load_ratio", "Recent CPU use of the process as a fraction of all CPUs", load);
                }
                double sys = sun.getCpuLoad();
                if (sys >= 0) {
                    sink.gauge("system_cpu_load_ratio", "Recent CPU use of the whole machine", sys);
                }
            }
            if (os instanceof com.sun.management.UnixOperatingSystemMXBean unix) {
                sink.gauge("process_open_fds", "Open file descriptors", unix.getOpenFileDescriptorCount());
            }
            long rss = residentSetBytes();
            if (rss >= 0) {
                sink.gauge("process_resident_memory_bytes", "Resident set size", rss);
            }
            sink.gauge("routeplane_build_info", "Build information", 1, "version", version, "java", System.getProperty("java.version", "?"));
        });
    }

    /** VmRSS from /proc/self/status (Linux), or -1. */
    static long residentSetBytes() {
        try {
            for (String line : Files.readAllLines(Path.of("/proc/self/status"))) {
                if (line.startsWith("VmRSS:")) {
                    String[] parts = line.split("\\s+");
                    return Long.parseLong(parts[1]) * 1024L;
                }
            }
        } catch (IOException | RuntimeException e) {
            return -1;
        }
        return -1;
    }
}
