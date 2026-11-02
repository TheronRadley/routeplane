package dev.routeplane.config;

import dev.routeplane.util.Durations;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.yaml.snakeyaml.error.Mark;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeId;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.nodes.Tag;

/**
 * Typed, position-aware view of a YAML mapping. Every accessor validates, records problems (with line and column)
 * into the shared {@link Ctx} instead of throwing, and returns the default so that one pass reports many problems.
 * Keys that were never read are reported by {@link #rejectUnknown()}, with a "did you mean" hint.
 */
final class ConfigNode {

    /** Shared state of one load: where the text came from, environment lookup, and collected issues. */
    static final class Ctx {
        final String source;
        final EnvSubstitutor env;
        final List<ConfigIssue> issues = new ArrayList<>();

        Ctx(String source, EnvSubstitutor env) {
            this.source = source;
            this.env = env;
        }

        void error(Node at, String path, String message) {
            if (issues.size() >= 100) {
                return;
            }
            Mark m = at == null ? null : at.getStartMark();
            issues.add(new ConfigIssue(source, m == null ? 0 : m.getLine() + 1, m == null ? 0 : m.getColumn() + 1, path, message));
        }
    }

    private final Ctx ctx;
    private final Node node;
    private final String path;
    private final Map<String, Node> entries;
    private final Map<String, Node> keyNodes;
    private final Set<String> used = new HashSet<>();

    private ConfigNode(Ctx ctx, Node node, String path, Map<String, Node> entries, Map<String, Node> keyNodes) {
        this.ctx = ctx;
        this.node = node;
        this.path = path;
        this.entries = entries;
        this.keyNodes = keyNodes;
    }

    /** A mapping view over {@code node}; if it is not a mapping an issue is recorded and the view is empty. */
    static ConfigNode of(Ctx ctx, Node node, String path) {
        Map<String, Node> entries = new LinkedHashMap<>();
        Map<String, Node> firstSeen = new LinkedHashMap<>();
        if (node instanceof MappingNode mapping) {
            for (NodeTuple t : mapping.getValue()) {
                if (!(t.getKeyNode() instanceof ScalarNode key)) {
                    ctx.error(t.getKeyNode(), path, "mapping keys must be plain strings");
                    continue;
                }
                if (Tag.MERGE.equals(key.getTag())) {
                    ctx.error(key, path, "YAML merge keys ('<<') are not supported");
                    continue;
                }
                String k = key.getValue();
                if (firstSeen.containsKey(k)) {
                    Mark first = firstSeen.get(k).getStartMark();
                    ctx.error(key, path, "duplicate key '" + k + "' (first defined at line " + (first.getLine() + 1) + ")");
                    continue;
                }
                firstSeen.put(k, key);
                entries.put(k, t.getValueNode());
            }
        } else if (node != null && !isNull(node)) {
            ctx.error(node, path, "expected a mapping but found " + describe(node));
        }
        return new ConfigNode(ctx, node, path, entries, firstSeen);
    }

    static ConfigNode empty(Ctx ctx, String path) {
        return new ConfigNode(ctx, null, path, Collections.emptyMap(), Collections.emptyMap());
    }

    Ctx ctxFor() {
        return ctx;
    }

    /** True if {@code key} holds a mapping (does not consume the key). */
    boolean isMapping(String key) {
        asked.add(key);
        return entries.get(key) instanceof MappingNode;
    }

    String path() {
        return path;
    }

    Node node() {
        return node;
    }

    boolean has(String key) {
        return entries.containsKey(key) && !isNull(entries.get(key));
    }

    String pathOf(String key) {
        return path.isEmpty() ? key : path + "." + key;
    }

    void error(String key, String message) {
        ctx.error(entries.containsKey(key) ? entries.get(key) : node, pathOf(key), message);
    }

    void errorHere(String message) {
        ctx.error(node, path, message);
    }

    // ------------------------------------------------------------------------------------------- structure

    /** Optional child mapping; returns null when absent. */
    ConfigNode map(String key) {
        Node v = take(key);
        if (v == null) {
            return null;
        }
        return of(ctx, v, pathOf(key));
    }

    /** Child mapping that must exist; when absent an issue is recorded and an empty view returned. */
    ConfigNode requireMap(String key) {
        ConfigNode m = map(key);
        if (m == null) {
            ctx.error(node, path, "missing required key '" + key + "'");
            return empty(ctx, pathOf(key));
        }
        return m;
    }

    /** Optional sequence of mappings; absent gives an empty list. */
    List<ConfigNode> mapList(String key) {
        Node v = take(key);
        if (v == null) {
            return List.of();
        }
        if (!(v instanceof SequenceNode seq)) {
            ctx.error(v, pathOf(key), "expected a list but found " + describe(v));
            return List.of();
        }
        List<ConfigNode> out = new ArrayList<>();
        int i = 0;
        for (Node item : seq.getValue()) {
            out.add(of(ctx, item, pathOf(key) + "[" + i++ + "]"));
        }
        return out;
    }

    /** Optional sequence of scalars (environment substitution applied to each). */
    List<String> strings(String key) {
        Node v = take(key);
        if (v == null) {
            return List.of();
        }
        if (!(v instanceof SequenceNode seq)) {
            ctx.error(v, pathOf(key), "expected a list but found " + describe(v));
            return List.of();
        }
        List<String> out = new ArrayList<>();
        int i = 0;
        for (Node item : seq.getValue()) {
            String s = scalar(item, pathOf(key) + "[" + i++ + "]");
            if (s != null) {
                out.add(s);
            }
        }
        return out;
    }

    /** Optional mapping of string to string. */
    Map<String, String> stringMap(String key) {
        ConfigNode m = map(key);
        if (m == null) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (String k : new ArrayList<>(m.entries.keySet())) {
            String v = m.string(k, null);
            if (v != null) {
                out.put(k, v);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------- scalars

    String string(String key, String def) {
        Node v = take(key);
        if (v == null) {
            return def;
        }
        String s = scalar(v, pathOf(key));
        return s == null ? def : s;
    }

    String requireString(String key) {
        String s = string(key, null);
        if (s == null) {
            if (!entries.containsKey(key) || isNull(entries.get(key))) {
                ctx.error(node, path, "missing required key '" + key + "'");
            }
            return "";
        }
        return s;
    }

    int integer(String key, int def, int min, int max) {
        return (int) longValue(key, def, min, max);
    }

    long longValue(String key, long def, long min, long max) {
        Node v = take(key);
        if (v == null) {
            return def;
        }
        String s = scalar(v, pathOf(key));
        if (s == null) {
            return def;
        }
        if (!s.matches("-?[0-9]{1,18}")) {
            ctx.error(v, pathOf(key), "expected an integer but found '" + s + "'");
            return def;
        }
        long n = Long.parseLong(s);
        if (n < min || n > max) {
            ctx.error(v, pathOf(key), "must be between " + min + " and " + max + " but is " + n);
            return def;
        }
        return n;
    }

    double number(String key, double def, double min, double max) {
        Node v = take(key);
        if (v == null) {
            return def;
        }
        String s = scalar(v, pathOf(key));
        if (s == null) {
            return def;
        }
        double d;
        try {
            if (!s.matches("-?[0-9]+(\\.[0-9]+)?")) {
                throw new NumberFormatException();
            }
            d = Double.parseDouble(s);
        } catch (NumberFormatException e) {
            ctx.error(v, pathOf(key), "expected a number but found '" + s + "'");
            return def;
        }
        if (d < min || d > max) {
            ctx.error(v, pathOf(key), "must be between " + min + " and " + max + " but is " + s);
            return def;
        }
        return d;
    }

    /** Strict booleans: only {@code true} and {@code false} (YAML 1.1 forms like yes/no/on/off are rejected). */
    boolean bool(String key, boolean def) {
        Node v = take(key);
        if (v == null) {
            return def;
        }
        String s = scalar(v, pathOf(key));
        if (s == null) {
            return def;
        }
        if (s.equals("true")) {
            return true;
        }
        if (s.equals("false")) {
            return false;
        }
        ctx.error(v, pathOf(key), "expected true or false but found '" + s + "'");
        return def;
    }

    Duration duration(String key, Duration def, Duration min, Duration max) {
        Node v = take(key);
        if (v == null) {
            return def;
        }
        String s = scalar(v, pathOf(key));
        if (s == null) {
            return def;
        }
        try {
            Duration d = Durations.parse(s);
            if (d.compareTo(min) < 0 || d.compareTo(max) > 0) {
                ctx.error(v, pathOf(key), "must be between " + Durations.format(min) + " and " + Durations.format(max) + " but is " + s);
                return def;
            }
            return d;
        } catch (IllegalArgumentException e) {
            ctx.error(v, pathOf(key), e.getMessage());
            return def;
        }
    }

    <E extends Enum<E>> E enumValue(String key, Class<E> type, E def) {
        Node v = take(key);
        if (v == null) {
            return def;
        }
        String s = scalar(v, pathOf(key));
        if (s == null) {
            return def;
        }
        String norm = s.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        for (E e : type.getEnumConstants()) {
            if (e.name().equals(norm)) {
                return e;
            }
        }
        List<String> allowed = new ArrayList<>();
        for (E e : type.getEnumConstants()) {
            allowed.add(e.name().toLowerCase(Locale.ROOT).replace('_', '-'));
        }
        ctx.error(v, pathOf(key), "must be one of " + allowed + " but is '" + s + "'");
        return def;
    }

    /** Reports every key of this mapping that no accessor consumed. */
    void rejectUnknown() {
        for (Map.Entry<String, Node> e : entries.entrySet()) {
            if (!used.contains(e.getKey())) {
                String hint = suggest(e.getKey());
                ctx.error(keyNodeOrValue(e.getKey()), path, "unknown key '" + e.getKey() + "'"
                        + (hint == null ? "" : " (did you mean '" + hint + "'?)")
                        + "; allowed keys here: " + allowed());
            }
        }
    }

    private final Set<String> asked = new java.util.LinkedHashSet<>();

    private String allowed() {
        return asked.toString();
    }

    private Node keyNodeOrValue(String key) {
        Node n = keyNodes.get(key);
        return n == null ? node : n;
    }

    private String suggest(String unknown) {
        String best = null;
        int bestDist = Integer.MAX_VALUE;
        for (String k : asked) {
            int d = levenshtein(unknown.toLowerCase(Locale.ROOT), k.toLowerCase(Locale.ROOT));
            if (d < bestDist) {
                bestDist = d;
                best = k;
            }
        }
        return bestDist <= Math.max(2, unknown.length() / 3) ? best : null;
    }

    static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }

    // --------------------------------------------------------------------------------------------- internals

    private Node take(String key) {
        asked.add(key);
        used.add(key);
        Node v = entries.get(key);
        if (v == null || isNull(v)) {
            return null;
        }
        return v;
    }

    private String scalar(Node v, String where) {
        if (!(v instanceof ScalarNode s)) {
            ctx.error(v, where, "expected a single value but found " + describe(v));
            return null;
        }
        if (s.getTag() != null && !s.getTag().getValue().startsWith("tag:yaml.org,2002:")) {
            ctx.error(v, where, "custom YAML tags are not allowed (" + s.getTag().getValue() + ")");
            return null;
        }
        try {
            return ctx.env.substitute(s.getValue());
        } catch (IllegalArgumentException e) {
            ctx.error(v, where, e.getMessage());
            return null;
        }
    }

    private static boolean isNull(Node n) {
        return n instanceof ScalarNode s && Tag.NULL.equals(s.getTag());
    }

    private static String describe(Node n) {
        NodeId id = n.getNodeId();
        return switch (id) {
            case mapping -> "a mapping";
            case sequence -> "a list";
            case scalar -> "a single value";
            default -> "an unexpected node";
        };
    }
}
