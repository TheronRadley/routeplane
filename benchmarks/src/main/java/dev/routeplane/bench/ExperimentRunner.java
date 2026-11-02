package dev.routeplane.bench;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.routeplane.bench.RunSummary.BreakerTransition;
import dev.routeplane.bench.RunSummary.EventMark;
import dev.routeplane.bench.RunSummary.SecondPoint;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Runs scenarios: starts the topology, drives the load, applies the fault timeline, samples resources, summarises. */
public final class ExperimentRunner {
    public static final Gson GSON = new GsonBuilder().setPrettyPrinting().serializeSpecialFloatingPointValues().create();
    private static final Set<String> UPSTREAM_CAUSED = Set.of("upstream_timeout", "connect_failed", "upstream_reset", "deadline_exceeded");

    private final Path workRoot;
    private final PrintStream out;

    public ExperimentRunner(Path workRoot, PrintStream out) {
        this.workRoot = workRoot;
        this.out = out;
    }

    /** Runs every tier of {@code s} {@code runs} times, writing each run's JSON into {@code runsDir} as soon as it ends. */
    public List<RunSummary> runScenario(Scenario s, Path runsDir, Integer runsOverride) throws Exception {
        Files.createDirectories(runsDir);
        List<RunSummary> all = new ArrayList<>();
        for (Map.Entry<String, String> variant : s.variants.entrySet()) {
            for (int tier : s.tiers()) {
                int runs = runsOverride != null ? runsOverride : s.runsFor(tier);
                for (int run = 1; run <= runs; run++) {
                    String label = s.id + (s.variants.size() > 1 ? "@" + variant.getKey() : "");
                    out.printf("[%s] tier %d rps, run %d/%d ...%n", label, tier, run, runs);
                    RunSummary r = runOnce(s, variant.getKey(), variant.getValue(), tier, run);
                    Files.writeString(runsDir.resolve(label.replace('@', '_') + "__" + tier + "rps__run" + run + ".json"), GSON.toJson(r));
                    out.printf("[%s]   achieved %.0f rps, ok %.1f%%, p50 %s ms, p99 %s ms%s%n", label, r.achievedRps, r.successRatePct,
                            fmt(r.latencyAll.p50ms), fmt(r.latencyAll.p99ms), r.flags.isEmpty() ? "" : "  flags=" + r.flags);
                    all.add(r);
                    Thread.sleep(1500); // let sockets drain between runs
                }
            }
        }
        return all;
    }

    private static String fmt(Double d) {
        return d == null ? "n/a" : String.format("%.1f", d);
    }

    public RunSummary runOnce(Scenario s, String variant, String proxyConfig, int tier, int runIdx) throws Exception {
        Path work = workRoot.resolve(s.id).resolve(variant).resolve(tier + "rps-run" + runIdx);
        long startedAt = System.currentTimeMillis();
        try (Topology topo = Topology.start(s, proxyConfig, work)) {
            String firstUp = s.upstreams.get(0).id();
            int targetPort = s.direct ? topo.upstreams().get(firstUp).port() : topo.proxyPort();
            URI target = URI.create("http://127.0.0.1:" + targetPort + s.path);
            Map<String, Long> pids = new LinkedHashMap<>();
            if (!s.direct) {
                pids.put("proxy", topo.proxyProcess().pid());
            }
            for (Topology.Upstream u : topo.upstreams().values()) {
                pids.put("upstream-" + u.id(), u.process().pid());
            }
            SystemSampler sampler = new SystemSampler(pids, s.direct ? null : topo.adminUri("/metrics"));
            List<EventMark> events = Collections.synchronizedList(new ArrayList<>());
            ScheduledExecutorService timeline = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "bench-timeline");
                t.setDaemon(true);
                return t;
            });
            long[] windowStartNanos = new long[1];
            long[] windowStartWall = new long[1];
            Map<String, Long> servedStart = new LinkedHashMap<>();
            Map<String, Long> servedEnd = new LinkedHashMap<>();
            SystemSampler.Snapshot[] snapHolder = new SystemSampler.Snapshot[1];
            // The hooks run on the load generator's pacing thread, which must never block (a stall there would show up as
            // latency, exactly as coordinated-omission correction intends). So only cheap bookkeeping happens inline; control
            // calls, the sampler and its first scrape run on a helper thread, in order.
            ExecutorService helper = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "bench-hooks");
                t.setDaemon(true);
                return t;
            });
            List<Future<?>> pending = new ArrayList<>();
            LoadGenerator.Hooks hooks = new LoadGenerator.Hooks() {
                @Override
                public void windowStarted(long nanos) {
                    windowStartNanos[0] = nanos;
                    windowStartWall[0] = System.currentTimeMillis();
                    for (Scenario.TimelineEvent e : s.timeline) {
                        timeline.schedule(() -> applyEvent(topo, e, events, nanos), e.at().toMillis(), TimeUnit.MILLISECONDS);
                    }
                    pending.add(helper.submit(() -> {
                        readServed(topo, servedStart);
                        sampler.start();
                    }));
                }

                @Override
                public void windowEnded(long nanos) {
                    pending.add(helper.submit(() -> {
                        snapHolder[0] = sampler.stop();
                        readServed(topo, servedEnd);
                    }));
                }
            };
            LoadGenerator.Config cfg = new LoadGenerator.Config(target, s.method, s.bodyBytes, tier, s.warmup, s.duration, s.timeout, s.maxInFlight);
            LoadGenerator.Result lr = new LoadGenerator().run(cfg, hooks);
            timeline.shutdownNow();
            for (Future<?> f : pending) {
                f.get();
            }
            helper.shutdown();
            RunSummary r = summarise(s, variant, tier, runIdx, startedAt, lr, snapHolder[0], events, servedStart, servedEnd);
            if (!s.direct) {
                collectBreakerHistory(topo, r, windowStartWall[0]);
            }
            computeRecovery(s, r);
            return r;
        }
    }

    // ---------------------------------------------------------------------------------------------- timeline

    private void applyEvent(Topology topo, Scenario.TimelineEvent e, List<EventMark> events, long windowStartNanos) {
        EventMark mark = new EventMark();
        mark.atSeconds = (System.nanoTime() - windowStartNanos) / 1e9;
        mark.label = e.label();
        try {
            if (e.recoverMillis() != null) {
                topo.control(e.upstream(), "POST", "/control/recover?durationMs=" + e.recoverMillis(), null);
                mark.detail = e.upstream() + ": gradual recovery over " + e.recoverMillis() + " ms";
            } else {
                topo.control(e.upstream(), "PUT", "/control/profile", GSON.toJson(e.profile()));
                mark.detail = e.upstream() + ": " + e.profile();
            }
        } catch (IOException | InterruptedException ex) {
            mark.detail = "FAILED to apply: " + ex;
        }
        events.add(mark);
    }

    private void readServed(Topology topo, Map<String, Long> into) {
        for (Topology.Upstream u : topo.upstreams().values()) {
            try {
                JsonObject o = JsonParser.parseString(topo.control(u.id(), "GET", "/control/stats", null)).getAsJsonObject();
                into.put(u.id(), o.get("requests").getAsLong());
            } catch (IOException | InterruptedException e) {
                into.put(u.id(), -1L);
            }
        }
    }

    // ------------------------------------------------------------------------------------------------- summary

    private RunSummary summarise(Scenario s, String variant, int tier, int runIdx, long startedAt, LoadGenerator.Result lr, SystemSampler.Snapshot snap,
                                 List<EventMark> events, Map<String, Long> servedStart, Map<String, Long> servedEnd) {
        RunSummary r = new RunSummary();
        r.scenario = s.id;
        r.variant = variant;
        r.description = s.description;
        r.kind = s.kind;
        r.direct = s.direct;
        r.tierRps = tier;
        r.run = runIdx;
        r.startedAtMillis = startedAt;
        r.windowSeconds = lr.windowSeconds;
        r.offered = lr.offered;
        r.completed = lr.completed;
        r.ok = lr.ok;
        r.droppedByGenerator = lr.dropped;
        r.outcomes = lr.outcomes;
        r.offeredRps = lr.offered / lr.windowSeconds;
        r.achievedRps = lr.completed / lr.windowSeconds;
        r.okRps = lr.ok / lr.windowSeconds;
        r.latencyAll = lr.latencyAll;
        r.latencyOk = lr.latencyOk;
        r.generatorLagP99Ms = lr.lagP99Ms;
        r.generatorLagMaxMs = lr.lagMaxMs;
        r.generatorCpuPct = lr.cpuSeconds / (lr.windowSeconds * cores()) * 100.0;
        r.series = lr.series;
        r.events = events;
        double off = Math.max(1, lr.offered);
        long proxyCaused = 0;
        long protective = 0;
        long proxyTotal = 0;
        long upstreamOrig = 0;
        long transport = 0;
        for (Map.Entry<String, Long> e : lr.outcomes.entrySet()) {
            String k = e.getKey();
            if (k.startsWith("proxy:")) {
                proxyTotal += e.getValue();
                if (UPSTREAM_CAUSED.contains(k.substring(6))) {
                    proxyCaused += e.getValue();
                } else {
                    protective += e.getValue();
                }
            } else if (k.equals("upstream_5xx") || k.equals("http_4xx")) {
                upstreamOrig += e.getValue();
            } else if (!k.equals("ok") && !k.equals("dropped_by_generator")) {
                transport += e.getValue();
            }
        }
        r.successRatePct = 100.0 * lr.ok / off;
        r.proxyGeneratedPct = 100.0 * proxyTotal / off;
        r.proxyUpstreamCausedPct = 100.0 * proxyCaused / off;
        r.proxyProtectivePct = 100.0 * protective / off;
        r.upstreamOriginatedPct = 100.0 * upstreamOrig / off;
        r.transportPct = 100.0 * transport / off;
        for (String id : servedEnd.keySet()) {
            r.upstreamServed.put(id, Math.max(0, servedEnd.get(id) - servedStart.getOrDefault(id, 0L)));
        }
        if (snap != null) {
            double w = Math.max(0.001, snap.windowSeconds);
            double proxyCpu = snap.cpuSeconds.getOrDefault("proxy", 0.0);
            r.proxyCpuSeconds = proxyCpu;
            r.proxyCpuPctOfMachine = proxyCpu / (w * cores()) * 100.0;
            r.upstreamsCpuPctOfMachine = snap.cpuSeconds.entrySet().stream().filter(e -> e.getKey().startsWith("upstream-")).mapToDouble(Map.Entry::getValue).sum() / (w * cores()) * 100.0;
            r.systemCpuPct = snap.systemBusyFraction * 100.0;
            r.proxyRssMbMax = snap.rssMax.getOrDefault("proxy", 0L) / 1048576.0;
            r.proxyHeapUsedMbMax = snap.heapUsedMax / 1048576.0;
            r.proxyThreadsMax = snap.threadsMax;
            r.tcpDownstreamMax = snap.tcpDownstreamMax;
            r.tcpUpstreamMax = snap.tcpUpstreamMax;
            r.shadowQueueDepthMax = snap.shadowQueueMax;
            r.proxyCpuMsPerRequest = lr.completed == 0 ? 0 : proxyCpu * (lr.windowSeconds / w) * 1000.0 / lr.completed;
            double upstreamAttempts = 0;
            double clientRequests = 0;
            for (Map.Entry<String, Double> e : snap.metricsEnd.entrySet()) {
                String k = e.getKey();
                String name = k.contains("{") ? k.substring(0, k.indexOf('{')) : k;
                double delta = e.getValue() - snap.metricsStart.getOrDefault(k, 0.0);
                if (name.startsWith("jvm_gc_collection_count_total")) {
                    r.proxyGcCount += delta;
                } else if (name.startsWith("jvm_gc_collection_seconds_total")) {
                    r.proxyGcSeconds += delta;
                } else if (name.startsWith("routeplane_") && name.endsWith("_total") && delta != 0) {
                    r.proxyDeltas.put(k, delta);
                    if (name.equals("routeplane_upstream_requests_total")) {
                        upstreamAttempts += delta;
                    } else if (name.equals("routeplane_requests_total")) {
                        clientRequests += delta;
                    }
                }
            }
            r.retryAmplification = clientRequests == 0 ? 0 : upstreamAttempts / clientRequests;
            for (int i = 0; i < r.series.size(); i++) {
                if (i < snap.gaugesPerSecond.size()) {
                    r.series.get(i).gauges.putAll(snap.gaugesPerSecond.get(i));
                }
            }
        }
        flag(r, snap);
        return r;
    }

    private static void flag(RunSummary r, SystemSampler.Snapshot snap) {
        double timeoutPct = 100.0 * (r.outcomes.getOrDefault("timeout", 0L) + r.outcomes.getOrDefault("incomplete", 0L)) / Math.max(1, r.offered);
        if (r.achievedRps < 0.95 * r.tierRps || timeoutPct > 1.0 || r.droppedByGenerator > 0) {
            r.flags.add("SATURATED: achieved " + Math.round(r.achievedRps) + " of " + r.tierRps + " rps offered"
                    + (timeoutPct > 1.0 ? ", " + String.format("%.1f", timeoutPct) + "% timed out" : "")
                    + (r.droppedByGenerator > 0 ? ", generator dropped " + r.droppedByGenerator : ""));
        }
        if (r.generatorLagP99Ms > 20.0) {
            r.flags.add("GENERATOR-LAG: the load generator sent requests up to " + String.format("%.0f", r.generatorLagP99Ms) + " ms (p99) after their intended time; latencies at this rate include that delay");
        }
        if (snap != null && snap.systemBusyFraction > 0.92) {
            r.flags.add("MACHINE-CPU-SATURATED: all CPUs were " + String.format("%.0f", snap.systemBusyFraction * 100) + "% busy (generator, proxy and upstreams share them)");
        }
        if (r.completed < LoadGenerator.minSamples(0.99)) {
            r.flags.add("FEW-SAMPLES: " + r.completed + " responses; p99 and p99.9 are not reported");
        } else if (r.completed < LoadGenerator.minSamples(0.999)) {
            r.flags.add("FEW-SAMPLES: " + r.completed + " responses; p99.9 is not reported (needs 10000)");
        }
        if (Math.abs(r.offered - r.tierRps * r.windowSeconds) > 0.02 * r.tierRps * r.windowSeconds) {
            r.flags.add("RATE-MISMATCH: offered " + r.offered + " requests, expected " + Math.round(r.tierRps * r.windowSeconds));
        }
    }

    private void collectBreakerHistory(Topology topo, RunSummary r, long windowStartWall) {
        try {
            JsonArray arr = JsonParser.parseString(topo.get(topo.adminUri("/admin/circuits"))).getAsJsonArray();
            for (JsonElement el : arr) {
                JsonObject c = el.getAsJsonObject();
                for (JsonElement te : c.getAsJsonArray("recentTransitions")) {
                    JsonObject t = te.getAsJsonObject();
                    double at = (t.get("atMillis").getAsLong() - windowStartWall) / 1000.0;
                    if (at < -1) {
                        continue;
                    }
                    BreakerTransition bt = new BreakerTransition();
                    bt.upstream = c.get("upstream").getAsString();
                    bt.atSeconds = at;
                    bt.from = t.get("from").getAsString();
                    bt.to = t.get("to").getAsString();
                    bt.reason = t.get("reason").getAsString();
                    r.breakerTransitions.add(bt);
                }
            }
        } catch (IOException | InterruptedException | RuntimeException e) {
            // admin unreachable: leave the history empty
        }
    }

    /** Recovery time: from the last recovery event until the success rate stays at 99% or better for three consecutive seconds. */
    static void computeRecovery(Scenario s, RunSummary r) {
        if (!s.isFailureScenario()) {
            return;
        }
        double clearAt = -1;
        for (EventMark m : r.events) {
            for (Scenario.TimelineEvent e : s.timeline) {
                if (e.label().equals(m.label) && e.isRecovery()) {
                    clearAt = Math.max(clearAt, m.atSeconds);
                }
            }
        }
        if (clearAt < 0) {
            r.recoveryNote = "no recovery event in this scenario";
            return;
        }
        for (int t = (int) Math.floor(clearAt); t + 2 < r.series.size(); t++) {
            boolean good = true;
            for (int k = 0; k < 3; k++) {
                SecondPoint p = r.series.get(t + k);
                if (p.offered == 0 || (double) p.ok / p.offered < 0.99) {
                    good = false;
                    break;
                }
            }
            if (good) {
                r.recoverySeconds = Math.max(0, t - clearAt);
                r.recoveryNote = "first second with >= 99% success sustained for 3 s, measured from the recovery event (1 s resolution)";
                return;
            }
        }
        r.recoveryNote = "did not return to 99% success within the measurement window";
    }

    static int cores() {
        return Runtime.getRuntime().availableProcessors();
    }
}
