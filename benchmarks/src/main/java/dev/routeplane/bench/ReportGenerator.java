package dev.routeplane.bench;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.routeplane.bench.RunSummary.EventMark;
import dev.routeplane.bench.RunSummary.SecondPoint;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Turns the per-run JSON files of a campaign into results.json, results.csv, charts/*.svg, report.md and a self-contained
 * report.html. Everything shown is computed from the recorded runs: medians across repetitions with the min-max range, flags
 * for saturation and low sample counts, and per-phase breakdowns for failure scenarios.
 */
public final class ReportGenerator {
    private static final Gson GSON = ExperimentRunner.GSON;

    private ReportGenerator() {
    }

    /** All repetitions of one scenario variant at one offered rate. */
    record Agg(String key, String scenario, String variant, int tier, List<RunSummary> runs) {
        Double median(Function<RunSummary, Double> f) {
            List<Double> v = runs.stream().map(f).filter(d -> d != null && !d.isNaN()).sorted().toList();
            if (v.isEmpty()) {
                return null;
            }
            int n = v.size();
            return n % 2 == 1 ? v.get(n / 2) : (v.get(n / 2 - 1) + v.get(n / 2)) / 2.0;
        }

        Double min(Function<RunSummary, Double> f) {
            return runs.stream().map(f).filter(d -> d != null && !d.isNaN()).min(Double::compare).orElse(null);
        }

        Double max(Function<RunSummary, Double> f) {
            return runs.stream().map(f).filter(d -> d != null && !d.isNaN()).max(Double::compare).orElse(null);
        }

        RunSummary first() {
            return runs.get(0);
        }
    }

    public static void generate(Path in, Path out) throws IOException {
        List<RunSummary> runs = new ArrayList<>();
        Path runsDir = in.resolve("runs");
        try (var files = Files.list(runsDir)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".json")).sorted().toList()) {
                runs.add(GSON.fromJson(Files.readString(p), RunSummary.class));
            }
        }
        if (runs.isEmpty()) {
            throw new IOException("no run files found in " + runsDir);
        }
        JsonObject campaign = Files.exists(in.resolve("campaign.json")) ? JsonParser.parseString(Files.readString(in.resolve("campaign.json"))).getAsJsonObject() : new JsonObject();
        Files.createDirectories(out.resolve("charts"));
        Map<String, List<Agg>> groups = aggregate(runs);

        Map<String, Object> json = new LinkedHashMap<>();
        json.put("generatedAt", Instant.now().toString());
        json.put("campaign", GSON.fromJson(campaign, Object.class));
        json.put("runs", runs);
        Files.writeString(out.resolve("results.json"), GSON.toJson(json));
        Files.writeString(out.resolve("results.csv"), csv(runs));

        Doc md = new Doc(false);
        Doc html = new Doc(true);
        Map<String, String> charts = new LinkedHashMap<>();
        for (Doc d : List.of(md, html)) {
            build(d, groups, campaign, charts, out);
        }
        for (var e : charts.entrySet()) {
            Files.writeString(out.resolve("charts").resolve(e.getKey() + ".svg"), e.getValue());
        }
        Files.writeString(out.resolve("report.md"), md.toString());
        Files.writeString(out.resolve("report.html"), html.toHtml());
    }

    static Map<String, List<Agg>> aggregate(List<RunSummary> runs) {
        Map<String, Map<Integer, List<RunSummary>>> byKey = new LinkedHashMap<>();
        for (RunSummary r : runs) {
            String key = r.scenario + (r.variant != null && !r.variant.equals("default") ? "@" + r.variant : "");
            byKey.computeIfAbsent(key, k -> new TreeMap<>()).computeIfAbsent(r.tierRps, t -> new ArrayList<>()).add(r);
        }
        Map<String, List<Agg>> out = new LinkedHashMap<>();
        byKey.forEach((key, tiers) -> {
            List<Agg> l = new ArrayList<>();
            tiers.forEach((tier, rs) -> l.add(new Agg(key, rs.get(0).scenario, rs.get(0).variant, tier, rs)));
            out.put(key, l);
        });
        return out;
    }

    // --------------------------------------------------------------------------------------------------- csv

    static String csv(List<RunSummary> runs) {
        StringBuilder sb = new StringBuilder("scenario,variant,tier_rps,run,offered_rps,achieved_rps,ok_rps,success_pct,proxy_generated_pct,proxy_upstream_caused_pct,"
                + "proxy_protective_pct,upstream_originated_pct,transport_pct,p50_ms,p90_ms,p95_ms,p99_ms,p999_ms,max_ms,ok_p50_ms,ok_p99_ms,generator_lag_p99_ms,"
                + "proxy_cpu_pct_of_machine,proxy_cpu_ms_per_request,proxy_rss_mb_max,proxy_heap_mb_max,proxy_gc_count,proxy_gc_seconds,tcp_downstream_max,"
                + "tcp_upstream_max,retry_amplification,shadow_queue_depth_max,recovery_seconds,flags\n");
        for (RunSummary r : runs) {
            sb.append(String.join(",", q(r.scenario), q(r.variant), String.valueOf(r.tierRps), String.valueOf(r.run), n(r.offeredRps), n(r.achievedRps), n(r.okRps),
                    n(r.successRatePct), n(r.proxyGeneratedPct), n(r.proxyUpstreamCausedPct), n(r.proxyProtectivePct), n(r.upstreamOriginatedPct), n(r.transportPct),
                    n(r.latencyAll.p50ms), n(r.latencyAll.p90ms), n(r.latencyAll.p95ms), n(r.latencyAll.p99ms), n(r.latencyAll.p999ms), n(r.latencyAll.maxMs),
                    n(r.latencyOk.p50ms), n(r.latencyOk.p99ms), n(r.generatorLagP99Ms), n(r.proxyCpuPctOfMachine), n(r.proxyCpuMsPerRequest), n(r.proxyRssMbMax),
                    n(r.proxyHeapUsedMbMax), n(r.proxyGcCount), n(r.proxyGcSeconds), n(r.tcpDownstreamMax), n(r.tcpUpstreamMax), n(r.retryAmplification),
                    n(r.shadowQueueDepthMax), n(r.recoverySeconds), q(String.join(" | ", r.flags)))).append('\n');
        }
        return sb.toString();
    }

    private static String q(String s) {
        return "\"" + (s == null ? "" : s.replace("\"", "\"\"")) + "\"";
    }

    private static String n(Double d) {
        return d == null ? "" : String.format(Locale.ROOT, "%.4f", d);
    }

    private static String n(double d) {
        return String.format(Locale.ROOT, "%.4f", d);
    }

    // ------------------------------------------------------------------------------------------------ report

    private static void build(Doc d, Map<String, List<Agg>> groups, JsonObject campaign, Map<String, String> charts, Path out) {
        List<String> steady = groups.entrySet().stream().filter(e -> !e.getValue().get(0).first().kind.equals("failure")).map(Map.Entry::getKey).toList();
        List<String> failure = groups.entrySet().stream().filter(e -> e.getValue().get(0).first().kind.equals("failure")).map(Map.Entry::getKey).toList();
        d.h(1, "Routeplane experiment report");
        d.p("Generated " + Instant.now() + " from " + groups.values().stream().mapToInt(l -> l.stream().mapToInt(a -> a.runs().size()).sum()).sum()
                + " recorded runs. Every number below is computed from the per-run JSON files in `runs/`; nothing is estimated or simulated.");

        d.h(2, "Environment");
        List<List<String>> env = new ArrayList<>();
        if (campaign.has("environment")) {
            campaign.getAsJsonObject("environment").entrySet().forEach(e -> env.add(List.of(e.getKey(), e.getValue().getAsString())));
        }
        if (campaign.has("invocations")) {
            int i = 1;
            for (var el : campaign.getAsJsonArray("invocations")) {
                JsonObject inv = el.getAsJsonObject();
                String text = inv.has("command") ? inv.get("command").getAsString() : "";
                if (inv.has("scenarios")) {
                    text += "  [scenarios: " + inv.get("scenarios").toString().replace("\"", "") + "]";
                }
                if (inv.has("note")) {
                    text += "  NOTE: " + inv.get("note").getAsString();
                }
                env.add(List.of("invocation " + i++, text));
            }
        } else if (campaign.has("command")) {
            env.add(List.of("command line", campaign.get("command").getAsString()));
        }
        d.table(List.of("property", "value"), env);

        d.h(2, "How to read this report");
        d.bullets(List.of(
                "**Open-loop load.** Requests are sent on a fixed schedule independent of responses; latency is measured from the *intended* send time, so stalls are not hidden (coordinated omission). If the generator itself falls behind it says so (flag `GENERATOR-LAG`).",
                "**Shared hardware.** The load generator, the proxy and the upstreams run on the same machine and compete for the same CPUs. Absolute numbers describe *this setup only*; the useful information is the relative difference between experiments measured under identical conditions.",
                "**Percentiles are only reported when the run has enough samples:** p99 needs at least 1,000 responses and p99.9 at least 10,000 (ten observations in the tail). Otherwise the cell shows `n/a`.",
                "**Saturation** is flagged when achieved throughput is below 95% of the offered rate, more than 1% of requests time out, or the generator had to drop requests. Latencies of saturated runs are lower bounds for what a client would experience and are shown but should not be compared with unsaturated runs.",
                "**Repetitions.** Cells show the median over the repetitions with the min-max range in brackets where useful. High variance between repetitions is called out.",
                "**Error attribution.** Responses are classified by origin: *upstream-originated* (error status produced by an upstream and passed through), *proxy-generated, upstream-caused* (502/504 for connect failures, resets, timeouts), *proxy-generated, protective* (503 for open breaker, no healthy upstream, concurrency limit, overload) and *transport* (client-side timeouts and connection errors). Upstream responses cannot carry the proxy marker header, so the split is reliable.",
                "**CPU, memory and GC** come from `/proc` and the proxy's `/metrics`; CPU is expressed as a percentage of the whole machine."));

        if (!steady.isEmpty()) {
            d.h(2, "Steady-state experiments");
            for (String key : steady) {
                List<Agg> tiers = groups.get(key);
                RunSummary f = tiers.get(0).first();
                d.h(3, key);
                if (f.description != null && !f.description.isBlank()) {
                    d.p(f.description.trim());
                }
                List<List<String>> rows = new ArrayList<>();
                for (Agg a : tiers) {
                    rows.add(List.of(String.valueOf(a.tier()), a.runs().size() + "",
                            fmt(a.median(r -> r.achievedRps), 0) + range(a, r -> r.achievedRps, 0),
                            fmt(a.median(r -> r.successRatePct), 1),
                            fmt(a.median(r -> r.latencyAll.p50ms), 2), fmt(a.median(r -> r.latencyAll.p95ms), 2),
                            fmt(a.median(r -> r.latencyAll.p99ms), 2) + range(a, r -> r.latencyAll.p99ms, 1),
                            fmt(a.median(r -> r.latencyAll.p999ms), 1), fmt(a.median(r -> r.latencyAll.maxMs), 1),
                            fmt(a.median(r -> r.proxyCpuPctOfMachine), 1), fmt(a.median(r -> r.proxyRssMbMax), 0),
                            fmt(a.median(r -> r.proxyHeapUsedMbMax), 0), fmt(a.median(r -> r.proxyGcCount), 0),
                            flagSummary(a)));
                }
                d.table(List.of("offered rps", "runs", "achieved rps", "success %", "p50 ms", "p95 ms", "p99 ms", "p99.9 ms", "max ms", "proxy CPU % of machine", "proxy RSS MB",
                        "heap MB", "GCs", "flags"), rows);
                List<String> notes = new ArrayList<>();
                for (Agg a : tiers) {
                    for (String fl : a.first().flags) {
                        notes.add("tier " + a.tier() + " (run 1): " + fl);
                    }
                    Double cv = cv(a, r -> r.latencyAll.p99ms);
                    if (a.runs().size() >= 3 && cv != null && cv > 0.25) {
                        notes.add("tier " + a.tier() + ": HIGH-VARIANCE: p99 varies by " + String.format(Locale.ROOT, "%.0f", cv * 100) + "% (coefficient of variation) across " + a.runs().size() + " runs");
                    }
                }
                if (!notes.isEmpty()) {
                    d.bullets(notes);
                }
            }
            comparison(d, groups, steady, charts);
        }
        if (!failure.isEmpty()) {
            d.h(2, "Failure scenarios");
            d.p("Each scenario applies a controlled fault to an upstream at a fixed time inside the measurement window and (usually) lifts it later. Phase tables split the window into *before*, *during* and *after* the fault; the charts show the success rate and p99 latency second by second with the fault events marked.");
            Map<String, List<String>> byScenario = new LinkedHashMap<>();
            for (String key : failure) {
                byScenario.computeIfAbsent(groups.get(key).get(0).scenario, k -> new ArrayList<>()).add(key);
            }
            for (var e : byScenario.entrySet()) {
                failureSection(d, e.getKey(), e.getValue(), groups, charts);
            }
        }
        d.h(2, "Limitations");
        d.bullets(List.of(
                "Single machine, shared CPUs, small core count: saturation happens early and the load generator is part of the load. Do not read the numbers as capacity figures for other hardware.",
                "Loopback networking only: no network latency, packet loss or bandwidth limits.",
                "Upstreams are the fault-injection upstream, not real applications; their service time is a configured delay.",
                "A handful of repetitions per point: ranges are shown, but this is not a statistical significance test.",
                "The JDK HTTP stack hides connection-pool details; connection counts are the kernel's view (`/proc/net/tcp`) attributed by port.",
                "Active health checks and DNS are not stressed by these scenarios."));
    }

    private static void comparison(Doc d, Map<String, List<Agg>> groups, List<String> steady, Map<String, String> charts) {
        d.h(3, "Comparison across experiments");
        List<Integer> tiers = steady.stream().flatMap(k -> groups.get(k).stream().map(Agg::tier)).distinct().sorted().toList();
        List<Svg.Series> p50 = new ArrayList<>();
        List<Svg.Series> p99 = new ArrayList<>();
        List<Svg.Series> thr = new ArrayList<>();
        List<Svg.Series> ok = new ArrayList<>();
        List<Svg.Series> cpu = new ArrayList<>();
        for (String key : steady) {
            List<Svg.Point> a = new ArrayList<>();
            List<Svg.Point> b = new ArrayList<>();
            List<Svg.Point> c = new ArrayList<>();
            List<Svg.Point> o = new ArrayList<>();
            List<Svg.Point> u = new ArrayList<>();
            for (Agg g : groups.get(key)) {
                a.add(pt(g.tier(), g.median(r -> r.latencyAll.p50ms)));
                b.add(pt(g.tier(), g.median(r -> r.latencyAll.p99ms)));
                c.add(pt(g.tier(), g.median(r -> r.achievedRps)));
                o.add(pt(g.tier(), g.median(r -> r.successRatePct)));
                u.add(pt(g.tier(), g.median(r -> r.proxyCpuPctOfMachine)));
            }
            p50.add(new Svg.Series(key, a));
            p99.add(new Svg.Series(key, b));
            thr.add(new Svg.Series(key, c));
            ok.add(new Svg.Series(key, o));
            cpu.add(new Svg.Series(key, u));
        }
        List<Svg.Point> ideal = new ArrayList<>();
        for (int t : tiers) {
            ideal.add(new Svg.Point(t, t));
        }
        thr.add(new Svg.Series("ideal (achieved = offered)", ideal, true));
        charts.put("latency-p50", Svg.lineChart("Median latency vs offered load", "offered load (requests/s, log scale)", "latency (ms, log scale)", p50, true, true, null, null, null));
        charts.put("latency-p99", Svg.lineChart("p99 latency vs offered load (points past saturation are lower bounds)", "offered load (requests/s, log scale)", "latency (ms, log scale)", p99, true, true, null, null, null));
        charts.put("throughput", Svg.lineChart("Achieved vs offered throughput", "offered load (requests/s, log scale)", "achieved (responses/s, log scale)", thr, true, true, null, null, null));
        charts.put("success", Svg.lineChart("Success rate vs offered load", "offered load (requests/s, log scale)", "successful responses (%)", ok, true, false, null, 0.0, 100.0));
        charts.put("proxy-cpu", Svg.lineChart("Proxy CPU vs offered load", "offered load (requests/s, log scale)", "proxy CPU (% of whole machine)", cpu, true, false, null, null, null));
        for (String c : List.of("latency-p50", "latency-p99", "throughput", "success", "proxy-cpu")) {
            d.svg(c, charts.get(c));
        }
        // overhead table: each experiment against the direct baseline, tier by tier
        String baseline = steady.stream().filter(k -> groups.get(k).get(0).first().direct).findFirst().orElse(null);
        if (baseline != null) {
            List<List<String>> rows = new ArrayList<>();
            for (int t : tiers) {
                Agg base = groups.get(baseline).stream().filter(g -> g.tier() == t).findFirst().orElse(null);
                if (base == null) {
                    continue;
                }
                for (String key : steady) {
                    if (key.equals(baseline)) {
                        continue;
                    }
                    Agg g = groups.get(key).stream().filter(x -> x.tier() == t).findFirst().orElse(null);
                    if (g == null) {
                        continue;
                    }
                    rows.add(List.of(String.valueOf(t), key, delta(g.median(r -> r.latencyAll.p50ms), base.median(r -> r.latencyAll.p50ms)),
                            delta(g.median(r -> r.latencyAll.p99ms), base.median(r -> r.latencyAll.p99ms)),
                            fmt(g.median(r -> r.achievedRps), 0) + " vs " + fmt(base.median(r -> r.achievedRps), 0),
                            fmt(g.median(r -> r.proxyCpuMsPerRequest), 3)));
                }
            }
            d.h(3, "Overhead relative to the direct baseline (" + baseline + ")");
            d.p("Latency differences are *proxy hop cost* (extra milliseconds at the same offered load); rows where either side was saturated are flagged in the per-experiment tables above and should be read with care.");
            d.table(List.of("offered rps", "experiment", "p50 delta", "p99 delta", "achieved rps (this vs direct)", "proxy CPU ms per request"), rows);
        }
    }

    // ---------------------------------------------------------------------------------------- failure scenarios

    private static void failureSection(Doc d, String scenario, List<String> keys, Map<String, List<Agg>> groups, Map<String, String> charts) {
        RunSummary f = groups.get(keys.get(0)).get(0).first();
        d.h(3, scenario);
        if (f.description != null && !f.description.isBlank()) {
            d.p(f.description.trim());
        }
        List<EventMark> ev = f.events;
        if (!ev.isEmpty()) {
            d.table(List.of("t (s)", "event", "detail"), ev.stream().sorted(Comparator.comparingDouble(e -> e.atSeconds))
                    .map(e -> List.of(String.format(Locale.ROOT, "%.1f", e.atSeconds), e.label, e.detail == null ? "" : e.detail)).toList());
        }
        double faultAt = ev.stream().mapToDouble(e -> e.atSeconds).min().orElse(0);
        double clearAt = ev.size() > 1 ? ev.stream().mapToDouble(e -> e.atSeconds).max().orElse(Double.MAX_VALUE) : Double.MAX_VALUE;
        List<List<String>> rows = new ArrayList<>();
        for (String key : keys) {
            Agg a = groups.get(key).get(0);
            String[] names = {"before fault", "during fault", "after recovery event"};
            double[][] bounds = {{0, faultAt}, {faultAt, clearAt}, {clearAt, 1e9}};
            for (int i = 0; i < 3; i++) {
                if (i == 2 && clearAt == Double.MAX_VALUE) {
                    continue;
                }
                final double lo = bounds[i][0];
                final double hi = bounds[i][1];
                rows.add(List.of(a.variant() == null ? "" : a.variant(), names[i], a.runs().size() + "",
                        fmt(a.median(r -> phase(r, lo, hi, p -> p.ok)), 1), fmt(a.median(r -> phase(r, lo, hi, p -> p.upstreamErr)), 1),
                        fmt(a.median(r -> phase(r, lo, hi, p -> p.proxyErr)), 1), fmt(a.median(r -> phase(r, lo, hi, p -> p.transportErr)), 1),
                        fmt(a.median(r -> phaseP99(r, lo, hi)), 1)));
            }
        }
        d.table(List.of("variant", "phase", "runs", "success %", "upstream-originated error %", "proxy-generated error %", "transport error %", "p99 latency ms (worst second)"), rows);
        List<List<String>> extra = new ArrayList<>();
        for (String key : keys) {
            Agg a = groups.get(key).get(0);
            extra.add(List.of(a.variant() == null ? "" : a.variant(),
                    fmt(a.median(r -> r.proxyUpstreamCausedPct), 1) + " / " + fmt(a.median(r -> r.proxyProtectivePct), 1),
                    fmt(a.median(r -> r.retryAmplification), 3),
                    a.median(r -> r.recoverySeconds) == null ? (a.first().recoveryNote == null ? "n/a" : "n/a: " + a.first().recoveryNote) : fmt(a.median(r -> r.recoverySeconds), 1) + " s",
                    fmt(a.median(r -> r.shadowQueueDepthMax), 0),
                    fmt(a.median(r -> delta(r, "routeplane_shadow_dropped_total")), 0),
                    fmt(a.median(r -> delta(r, "routeplane_circuit_breaker_transitions_total")), 0)));
        }
        d.table(List.of("variant", "whole window: proxy-generated % (upstream-caused / protective)", "retry amplification (upstream attempts per request)", "recovery time",
                "shadow queue max", "shadow dropped", "breaker transitions"), extra);
        for (String key : keys) {
            Agg a = groups.get(key).get(0);
            RunSummary r = a.first();
            if (!r.breakerTransitions.isEmpty()) {
                d.p("Breaker transitions in run 1 of `" + key + "`: " + r.breakerTransitions.stream().map(t -> String.format(Locale.ROOT, "%s %s->%s at %.1fs (%s)", t.upstream, t.from, t.to, t.atSeconds, t.reason))
                        .collect(Collectors.joining("; ")));
            }
            for (String fl : r.flags) {
                d.p("Note for `" + key + "`: " + fl);
            }
        }
        // events that happen at (nearly) the same instant share one marker so that their labels do not overprint
        List<Svg.Marker> markers = new ArrayList<>();
        for (EventMark e : ev.stream().sorted(Comparator.comparingDouble(x -> x.atSeconds)).toList()) {
            if (markers.isEmpty() || e.atSeconds - markers.get(markers.size() - 1).x() > 0.5) {
                markers.add(new Svg.Marker(e.atSeconds, e.label));
            }
        }
        List<Svg.Series> okSeries = new ArrayList<>();
        List<Svg.Series> p99Series = new ArrayList<>();
        for (String key : keys) {
            Agg a = groups.get(key).get(0);
            int len = a.first().series.size();
            List<Svg.Point> o = new ArrayList<>();
            List<Svg.Point> l = new ArrayList<>();
            for (int s = 0; s < len; s++) {
                final int idx = s;
                // each value describes the whole second [s, s+1): draw it as a step so that a change is not shown a second early
                Double okPct = a.median(r -> idx < r.series.size() && r.series.get(idx).offered > 0 ? 100.0 * r.series.get(idx).ok / r.series.get(idx).offered : null);
                Double p99 = a.median(r -> idx < r.series.size() ? r.series.get(idx).p99ms : null);
                o.add(pt(s, okPct));
                o.add(pt(s + 1, okPct));
                l.add(pt(s, p99));
                l.add(pt(s + 1, p99));
            }
            okSeries.add(new Svg.Series(a.variant() == null ? key : a.variant(), o));
            p99Series.add(new Svg.Series(a.variant() == null ? key : a.variant(), l));
        }
        String c1 = scenario + "-success";
        String c2 = scenario + "-p99";
        charts.put(c1, Svg.lineChart(scenario + ": successful responses per second", "seconds since the start of the measurement window", "success (% of requests offered in that second)", okSeries, false, false, markers, 0.0, 100.0));
        charts.put(c2, Svg.lineChart(scenario + ": p99 latency per second", "seconds since the start of the measurement window", "p99 latency (ms, log scale)", p99Series, false, true, markers, null, null));
        d.svg(c1, charts.get(c1));
        d.svg(c2, charts.get(c2));
    }

    private static Double delta(RunSummary r, String prefix) {
        double sum = 0;
        for (var e : r.proxyDeltas.entrySet()) {
            if (e.getKey().startsWith(prefix)) {
                sum += e.getValue();
            }
        }
        return sum;
    }

    /**
     * Percentage of the offered requests of the seconds that belong to the phase [from, to) which fell into the category
     * selected by {@code field}. A one-second bucket belongs to the phase that contains its <em>midpoint</em>: an event at
     * 8.03 s makes bucket [8, 9) part of the fault phase (97% of it is), not of the phase before it.
     */
    static Double phase(RunSummary r, double from, double to, Function<SecondPoint, Long> field) {
        long offered = 0;
        long hit = 0;
        for (SecondPoint p : r.series) {
            double mid = p.t + 0.5;
            if (mid >= from && mid < to) {
                offered += p.offered;
                hit += field.apply(p);
            }
        }
        return offered == 0 ? null : 100.0 * hit / offered;
    }

    static Double phaseP99(RunSummary r, double from, double to) {
        Double worst = null;
        for (SecondPoint p : r.series) {
            double mid = p.t + 0.5;
            if (mid >= from && mid < to && p.p99ms != null) {
                worst = worst == null ? p.p99ms : Math.max(worst, p.p99ms);
            }
        }
        return worst;
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private static Svg.Point pt(double x, Double y) {
        return new Svg.Point(x, y == null ? Double.NaN : y);
    }

    private static String fmt(Double v, int digits) {
        return v == null ? "n/a" : String.format(Locale.ROOT, "%." + digits + "f", v);
    }

    private static String range(Agg a, Function<RunSummary, Double> f, int digits) {
        if (a.runs().size() < 2) {
            return "";
        }
        Double lo = a.min(f);
        Double hi = a.max(f);
        return lo == null || hi == null ? "" : " [" + fmt(lo, digits) + "-" + fmt(hi, digits) + "]";
    }

    private static String delta(Double v, Double base) {
        if (v == null || base == null) {
            return "n/a";
        }
        return String.format(Locale.ROOT, "%+.2f ms", v - base);
    }

    private static Double cv(Agg a, Function<RunSummary, Double> f) {
        List<Double> v = a.runs().stream().map(f).filter(x -> x != null).toList();
        if (v.size() < 2) {
            return null;
        }
        double mean = v.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        if (mean == 0) {
            return null;
        }
        double var = v.stream().mapToDouble(x -> (x - mean) * (x - mean)).sum() / (v.size() - 1);
        return Math.sqrt(var) / mean;
    }

    private static String flagSummary(Agg a) {
        List<String> kinds = new ArrayList<>();
        for (RunSummary r : a.runs()) {
            for (String fl : r.flags) {
                String k = fl.substring(0, fl.indexOf(':') < 0 ? fl.length() : fl.indexOf(':'));
                if (!kinds.contains(k)) {
                    kinds.add(k);
                }
            }
        }
        return kinds.isEmpty() ? "" : String.join(", ", kinds);
    }

    /** Builds the same document as Markdown or as HTML. */
    static final class Doc {
        private final boolean html;
        private final StringBuilder sb = new StringBuilder();

        Doc(boolean html) {
            this.html = html;
        }

        void h(int level, String text) {
            if (html) {
                sb.append("<h").append(level).append('>').append(Svg.esc(text)).append("</h").append(level).append(">\n");
            } else {
                sb.append("\n").append("#".repeat(level)).append(' ').append(text).append("\n\n");
            }
        }

        void p(String text) {
            if (html) {
                sb.append("<p>").append(inline(text)).append("</p>\n");
            } else {
                sb.append(text).append("\n\n");
            }
        }

        void bullets(List<String> items) {
            if (html) {
                sb.append("<ul>\n");
                items.forEach(i -> sb.append("<li>").append(inline(i)).append("</li>\n"));
                sb.append("</ul>\n");
            } else {
                items.forEach(i -> sb.append("- ").append(i).append('\n'));
                sb.append('\n');
            }
        }

        void table(List<String> headers, List<List<String>> rows) {
            if (html) {
                sb.append("<table>\n<tr>").append(headers.stream().map(x -> "<th>" + Svg.esc(x) + "</th>").collect(Collectors.joining())).append("</tr>\n");
                for (List<String> r : rows) {
                    sb.append("<tr>").append(r.stream().map(x -> "<td>" + Svg.esc(x) + "</td>").collect(Collectors.joining())).append("</tr>\n");
                }
                sb.append("</table>\n");
            } else {
                sb.append("| ").append(String.join(" | ", headers)).append(" |\n");
                sb.append("|").append(" --- |".repeat(headers.size())).append('\n');
                for (List<String> r : rows) {
                    sb.append("| ").append(r.stream().map(x -> x.replace("|", "\\|")).collect(Collectors.joining(" | "))).append(" |\n");
                }
                sb.append('\n');
            }
        }

        void svg(String name, String content) {
            if (html) {
                sb.append("<div class=\"chart\">").append(content).append("</div>\n");
            } else {
                sb.append("![").append(name).append("](charts/").append(name).append(".svg)\n\n");
            }
        }

        private static String inline(String s) {
            String e = Svg.esc(s);
            e = e.replaceAll("\\*\\*(.+?)\\*\\*", "<strong>$1</strong>");
            e = e.replaceAll("`(.+?)`", "<code>$1</code>");
            e = e.replaceAll("\\*(.+?)\\*", "<em>$1</em>");
            return e;
        }

        @Override
        public String toString() {
            return sb.toString();
        }

        String toHtml() {
            return "<!doctype html>\n<html lang=\"en\"><head><meta charset=\"utf-8\"><title>Routeplane experiment report</title>\n<style>"
                    + "body{font-family:system-ui,sans-serif;max-width:1100px;margin:2em auto;padding:0 1em;line-height:1.45}"
                    + "table{border-collapse:collapse;margin:1em 0;font-size:13px}th,td{border:1px solid #ccc;padding:3px 8px;text-align:right}"
                    + "th{background:#f3f3f3}td:first-child,th:first-child{text-align:left}code{background:#f3f3f3;padding:0 3px}"
                    + ".chart{margin:1em 0}</style></head><body>\n" + sb + "</body></html>\n";
        }
    }
}
