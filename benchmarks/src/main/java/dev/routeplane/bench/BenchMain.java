package dev.routeplane.bench;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code routeplane-bench} command line.
 *
 * <pre>
 *   list    [--scenarios DIR]
 *   run     --scenario FILE [--out DIR] [--runs N] [--rates 100,500] [--warmup 3s] [--duration 10s]
 *   matrix  [--scenarios DIR] [--out DIR] [--only id,id] [--runs N] [--rates ...] [--warmup ...] [--duration ...]
 *   report  --in DIR [--out DIR]
 * </pre>
 *
 * Options after the command override the scenario files, which is how smoke runs are made short.
 */
public final class BenchMain {
    private BenchMain() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0 || args[0].equals("help") || args[0].equals("--help")) {
            System.out.println(USAGE);
            return;
        }
        Map<String, String> o = options(args);
        switch (args[0]) {
            case "list" -> {
                for (Scenario s : ScenarioLoader.loadAll(Path.of(o.getOrDefault("scenarios", "experiments/scenarios")))) {
                    System.out.printf("%-26s %-8s variants=%-34s tiers=%s%n  %s%n", s.id, s.kind, s.variants.keySet(), s.tiers(), s.description);
                }
            }
            case "run", "matrix" -> run(args[0], o);
            case "report" -> {
                Path in = Path.of(require(o, "in"));
                Path out = Path.of(o.getOrDefault("out", in.toString()));
                ReportGenerator.generate(in, out);
                System.out.println("report written to " + out.toAbsolutePath());
            }
            default -> {
                System.err.println("unknown command '" + args[0] + "'\n" + USAGE);
                System.exit(2);
            }
        }
        System.exit(0);
    }

    private static void run(String command, Map<String, String> o) throws Exception {
        List<Scenario> scenarios = new ArrayList<>();
        if (command.equals("run")) {
            scenarios.add(ScenarioLoader.load(Path.of(require(o, "scenario"))));
        } else {
            Set<String> only = o.containsKey("only") ? new HashSet<>(Arrays.asList(o.get("only").split(","))) : null;
            for (Scenario s : ScenarioLoader.loadAll(Path.of(o.getOrDefault("scenarios", "experiments/scenarios")))) {
                if (only == null || only.contains(s.id)) {
                    scenarios.add(s);
                }
            }
        }
        for (Scenario s : scenarios) {
            if (o.containsKey("rates")) {
                s.rates = Arrays.stream(o.get("rates").split(",")).map(Integer::parseInt).toList();
                s.rate = s.rates.get(0);
            }
            if (o.containsKey("warmup")) {
                s.warmup = ScenarioLoader.dur(o.get("warmup"), s.warmup);
            }
            if (o.containsKey("duration")) {
                s.duration = ScenarioLoader.dur(o.get("duration"), s.duration);
            }
        }
        Integer runs = o.containsKey("runs") ? Integer.parseInt(o.get("runs")) : null;
        Path out = Path.of(o.getOrDefault("out", "experiments/results/latest"));
        Path runsDir = out.resolve("runs");
        Files.createDirectories(runsDir);
        // campaign.json accumulates one record per invocation: a campaign can be resumed (for example after fixing a scenario)
        // without losing the history of what was run when, and why.
        Path meta = out.resolve("campaign.json");
        Map<String, Object> campaign = readCampaign(meta);
        @SuppressWarnings("unchecked")
        List<Object> invocations = (List<Object>) campaign.computeIfAbsent("invocations", k -> new ArrayList<>());
        Map<String, Object> invocation = new LinkedHashMap<>();
        invocation.put("command", String.join(" ", o.entrySet().stream().filter(e -> !e.getKey().equals("note")).map(e -> "--" + e.getKey() + " " + e.getValue()).toList()));
        if (o.containsKey("note")) {
            invocation.put("note", o.get("note"));
        }
        invocation.put("scenarios", scenarios.stream().map(s -> s.id).toList());
        invocation.put("startedAtMillis", System.currentTimeMillis());
        invocations.add(invocation);
        campaign.putIfAbsent("environment", Environment.capture());
        Files.writeString(meta, ExperimentRunner.GSON.toJson(campaign));
        Path work = Path.of(o.getOrDefault("work", "experiments/work"));
        ExperimentRunner runner = new ExperimentRunner(work, System.out);
        for (Scenario s : scenarios) {
            runner.runScenario(s, runsDir, runs);
        }
        invocation.put("finishedAtMillis", System.currentTimeMillis());
        Files.writeString(meta, ExperimentRunner.GSON.toJson(campaign));
        ReportGenerator.generate(out, out);
        System.out.println("results and report written to " + out.toAbsolutePath());
    }

    /** Reads an existing campaign.json (converting the single-invocation layout of earlier versions), or starts an empty one. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> readCampaign(Path meta) throws IOException {
        Map<String, Object> campaign = new LinkedHashMap<>();
        if (!Files.exists(meta)) {
            return campaign;
        }
        Map<String, Object> old = ExperimentRunner.GSON.fromJson(Files.readString(meta), Map.class);
        campaign.putAll(old);
        if (old.containsKey("command") && !old.containsKey("invocations")) { // legacy: one invocation described at the top level
            Map<String, Object> inv = new LinkedHashMap<>();
            for (String k : List.of("command", "scenarios", "startedAtMillis", "finishedAtMillis")) {
                if (old.containsKey(k)) {
                    inv.put(k, campaign.remove(k));
                }
            }
            List<Object> l = new ArrayList<>();
            l.add(inv);
            campaign.put("invocations", l);
        }
        return campaign;
    }

    private static Map<String, String> options(String[] args) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 1; i < args.length; i++) {
            if (!args[i].startsWith("--") || i + 1 >= args.length) {
                throw new IllegalArgumentException("bad argument: " + args[i]);
            }
            m.put(args[i].substring(2), args[++i]);
        }
        return m;
    }

    private static String require(Map<String, String> o, String key) {
        String v = o.get(key);
        if (v == null) {
            throw new IllegalArgumentException("missing --" + key);
        }
        return v;
    }

    @SuppressWarnings("unused")
    private static Duration unused() throws IOException {
        return Duration.ZERO;
    }

    static final String USAGE = """
            routeplane-bench - experiment runner for Routeplane (see docs/benchmarking.md)

              list    [--scenarios DIR]
              run     --scenario FILE [--out DIR] [--runs N] [--rates 100,500] [--warmup 3s] [--duration 10s]
              matrix  [--scenarios DIR] [--out DIR] [--only id,id] [--runs N] [--rates ...] [--warmup ...] [--duration ...] [--note TEXT]
              report  --in DIR [--out DIR]

            Processes are launched from the installDist scripts, or from ROUTEPLANE_PROXY_CMD / ROUTEPLANE_UPSTREAM_CMD.
            """;
}
