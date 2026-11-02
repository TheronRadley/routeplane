package dev.routeplane.proxy;

import dev.routeplane.util.Durations;
import java.lang.reflect.RecordComponent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Renders the (record-based) configuration as plain maps for the admin API. Generic by construction, so a field added
 * to the model shows up automatically - and any component whose name contains token, password or secret is masked, so a
 * new secret field cannot leak by omission.
 */
public final class ConfigView {
    private ConfigView() {
    }

    public static Object render(Object v) {
        if (v == null || v instanceof String || v instanceof Number || v instanceof Boolean) {
            return v;
        }
        if (v instanceof Enum<?> e) {
            return e.name().toLowerCase(Locale.ROOT).replace('_', '-');
        }
        if (v instanceof Duration d) {
            return Durations.format(d);
        }
        if (v instanceof BitSet b) {
            return ranges(b);
        }
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.entrySet().stream().sorted((x, y) -> String.valueOf(x.getKey()).compareTo(String.valueOf(y.getKey())))
                    .forEach(e -> out.put(String.valueOf(e.getKey()), render(e.getValue())));
            return out;
        }
        if (v instanceof Collection<?> c) {
            List<Object> out = new ArrayList<>(c.size());
            Collection<?> source = c;
            if (c instanceof java.util.Set<?> set && !set.isEmpty() && set.iterator().next() instanceof Comparable) {
                List<Object> sorted = new ArrayList<>(set);
                sorted.sort((a, b) -> compare(a, b));
                source = sorted;
            }
            for (Object o : source) {
                out.add(render(o));
            }
            return out;
        }
        if (v.getClass().isRecord()) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (RecordComponent rc : v.getClass().getRecordComponents()) {
                try {
                    Object value = rc.getAccessor().invoke(v);
                    String lower = rc.getName().toLowerCase(Locale.ROOT);
                    if (value != null && (lower.contains("token") || lower.contains("password") || lower.contains("secret"))) {
                        out.put(rc.getName(), "[REDACTED]");
                    } else {
                        out.put(rc.getName(), render(value));
                    }
                } catch (ReflectiveOperationException e) {
                    out.put(rc.getName(), "<unavailable>");
                }
            }
            return out;
        }
        return v.toString();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compare(Object a, Object b) {
        return ((Comparable) a).compareTo(b);
    }

    private static String ranges(BitSet b) {
        StringBuilder sb = new StringBuilder();
        int i = b.nextSetBit(0);
        while (i >= 0) {
            int end = b.nextClearBit(i);
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(end - 1 == i ? Integer.toString(i) : i + "-" + (end - 1));
            i = b.nextSetBit(end);
        }
        return sb.toString();
    }
}
