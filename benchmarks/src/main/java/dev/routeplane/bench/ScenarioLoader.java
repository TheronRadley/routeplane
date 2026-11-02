package dev.routeplane.bench;

import dev.routeplane.bench.Scenario.TimelineEvent;
import dev.routeplane.bench.Scenario.UpstreamSpec;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Reads scenario files. Unknown keys are errors, so a typo cannot silently change an experiment. */
public final class ScenarioLoader {
    private static final Pattern DURATION = Pattern.compile("^([0-9]+)(ms|s|m)$");

    private ScenarioLoader() {
    }

    public static Scenario load(Path file) throws IOException {
        return parse(Files.readString(file), file.getFileName().toString().replaceAll("\\.ya?ml$", ""));
    }

    @SuppressWarnings("unchecked")
    public static Scenario parse(String yaml, String defaultId) {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        Object root = new Yaml(new SafeConstructor(options)).load(new StringReader(yaml));
        if (!(root instanceof Map)) {
            throw new IllegalArgumentException("scenario must be a YAML mapping");
        }
        Map<String, Object> m = new LinkedHashMap<>((Map<String, Object>) root);
        Scenario s = new Scenario();
        s.id = str(m.remove("id"), defaultId);
        s.description = str(m.remove("description"), "");
        s.kind = str(m.remove("kind"), "steady");
        if (!s.kind.equals("steady") && !s.kind.equals("failure")) {
            throw new IllegalArgumentException("kind must be steady or failure");
        }
        s.direct = Boolean.TRUE.equals(m.remove("direct"));
        Object proxy = m.remove("proxy");
        if (proxy instanceof Map<?, ?> pm) {
            Map<String, Object> p = new LinkedHashMap<>((Map<String, Object>) pm);
            String single = str(p.remove("config"), "");
            String base = str(p.remove("base"), "");
            Object variants = p.remove("variants");
            if (variants instanceof Map<?, ?> vm) {
                if (!single.isEmpty()) {
                    throw new IllegalArgumentException("use either proxy.config or proxy.base + proxy.variants, not both");
                }
                // a variant's configuration is the shared base followed by its own route-level additions
                vm.forEach((k, v) -> s.variants.put(String.valueOf(k), base + (v == null ? "" : indent(String.valueOf(v), 4))));
            } else if (!single.isEmpty()) {
                s.variants.put("default", single);
            }
            Object jvm = p.remove("jvmOptions");
            if (jvm instanceof List<?> l) {
                s.proxyJvmOptions = l.stream().map(String::valueOf).toList();
            }
            reject(p, "proxy");
        }
        Object ups = m.remove("upstreams");
        if (ups instanceof List<?> l) {
            for (Object o : l) {
                Map<String, Object> u = new LinkedHashMap<>((Map<String, Object>) o);
                String id = str(u.remove("id"), null);
                Map<String, Object> profile = u.get("profile") instanceof Map<?, ?> pm ? new LinkedHashMap<>((Map<String, Object>) u.remove("profile")) : Map.of();
                u.remove("profile");
                reject(u, "upstreams[]");
                s.upstreams.add(new UpstreamSpec(id, profile));
            }
        }
        Object load = m.remove("load");
        if (load instanceof Map<?, ?> lm) {
            Map<String, Object> l = new LinkedHashMap<>((Map<String, Object>) lm);
            s.path = str(l.remove("path"), s.path);
            s.method = str(l.remove("method"), s.method);
            s.bodyBytes = l.containsKey("bodyBytes") ? ((Number) l.remove("bodyBytes")).intValue() : 0;
            if (l.get("rates") instanceof List<?> rl) {
                s.rates = rl.stream().map(o -> ((Number) o).intValue()).toList();
                l.remove("rates");
            }
            if (l.containsKey("rate")) {
                s.rate = ((Number) l.remove("rate")).intValue();
            }
            s.warmup = dur(l.remove("warmup"), s.warmup);
            s.duration = dur(l.remove("duration"), s.duration);
            s.timeout = dur(l.remove("timeout"), s.timeout);
            if (l.containsKey("maxInFlight")) {
                s.maxInFlight = ((Number) l.remove("maxInFlight")).intValue();
            }
            reject(l, "load");
        }
        if (m.containsKey("runs")) {
            s.runs = ((Number) m.remove("runs")).intValue();
        }
        if (m.containsKey("highRateRuns")) {
            s.highRateRuns = ((Number) m.remove("highRateRuns")).intValue();
        }
        Object tl = m.remove("timeline");
        if (tl instanceof List<?> l) {
            for (Object o : l) {
                Map<String, Object> e = new LinkedHashMap<>((Map<String, Object>) o);
                Duration at = dur(e.remove("at"), null);
                String up = str(e.remove("upstream"), null);
                Map<String, Object> profile = e.get("set") instanceof Map<?, ?> ? new LinkedHashMap<>((Map<String, Object>) e.remove("set")) : null;
                Long recover = e.containsKey("recover") ? dur(e.remove("recover"), null).toMillis() : null;
                String label = str(e.remove("label"), "event");
                boolean recovery = Boolean.TRUE.equals(e.remove("recovery"));
                reject(e, "timeline[]");
                if (at == null || up == null || (profile == null && recover == null)) {
                    throw new IllegalArgumentException("timeline entries need at, upstream and set or recover");
                }
                s.timeline.add(new TimelineEvent(at, up, profile, recover, label, recovery));
            }
        }
        if (m.get("notes") instanceof Map<?, ?> nm) {
            nm.forEach((k, v) -> s.notes.put(String.valueOf(k), String.valueOf(v)));
            m.remove("notes");
        }
        reject(m, "scenario");
        validate(s);
        return s;
    }

    private static void validate(Scenario s) {
        if (s.upstreams.isEmpty()) {
            throw new IllegalArgumentException(s.id + ": at least one upstream is required");
        }
        if (s.direct) {
            if (s.variants.isEmpty()) {
                s.variants.put("default", "");
            }
        } else if (s.variants.isEmpty()) {
            throw new IllegalArgumentException(s.id + ": proxy.config (or proxy.base with proxy.variants) is required unless direct: true");
        }
        for (TimelineEvent e : s.timeline) {
            if (s.upstreams.stream().noneMatch(u -> u.id().equals(e.upstream()))) {
                throw new IllegalArgumentException(s.id + ": timeline refers to unknown upstream '" + e.upstream() + "'");
            }
            if (e.at().compareTo(s.duration) >= 0) {
                throw new IllegalArgumentException(s.id + ": timeline event '" + e.label() + "' is after the end of the window");
            }
        }
        if (s.runs < 1 || s.rates.isEmpty() || s.rate < 1) {
            throw new IllegalArgumentException(s.id + ": runs, rates and rate must be positive");
        }
    }

    /** Variant additions are route-level keys; they are indented to attach to the single route declared in the base. */
    static String indent(String text, int spaces) {
        String pad = " ".repeat(spaces);
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            sb.append(line.isBlank() ? "" : pad + line).append('\n');
        }
        return sb.toString().replaceAll("\n+$", "\n");
    }

    private static void reject(Map<String, Object> rest, String where) {
        if (!rest.isEmpty()) {
            throw new IllegalArgumentException("unknown key(s) in " + where + ": " + rest.keySet());
        }
    }

    private static String str(Object o, String def) {
        return o == null ? def : String.valueOf(o);
    }

    static Duration dur(Object o, Duration def) {
        if (o == null) {
            return def;
        }
        Matcher m = DURATION.matcher(String.valueOf(o).trim());
        if (!m.matches()) {
            throw new IllegalArgumentException("bad duration '" + o + "' (use 500ms, 10s or 2m)");
        }
        long n = Long.parseLong(m.group(1));
        return switch (m.group(2)) {
            case "ms" -> Duration.ofMillis(n);
            case "s" -> Duration.ofSeconds(n);
            default -> Duration.ofMinutes(n);
        };
    }

    /** Loads every *.yaml file of a directory in name order. */
    public static List<Scenario> loadAll(Path dir) throws IOException {
        List<Scenario> out = new ArrayList<>();
        try (var files = Files.list(dir)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".yaml")).sorted().toList()) {
                out.add(load(p));
            }
        }
        return out;
    }
}
