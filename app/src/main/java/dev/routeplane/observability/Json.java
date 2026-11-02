package dev.routeplane.observability;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal JSON support for admin responses and log lines, built on Gson's streaming writer (no reflection). */
public final class Json {
    private Json() {
    }

    public static String write(Object value) {
        return write(value, false);
    }

    /**
     * Serialises maps, iterables, arrays, strings, numbers, booleans and null. Other objects are written via
     * {@code toString()}. NaN and infinite numbers become {@code null} (JSON cannot represent them).
     */
    public static String write(Object value, boolean pretty) {
        StringWriter sw = new StringWriter(256);
        try (JsonWriter w = new JsonWriter(sw)) {
            if (pretty) {
                w.setIndent("  ");
            }
            writeValue(w, value);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return sw.toString();
    }

    private static void writeValue(JsonWriter w, Object v) throws IOException {
        if (v == null) {
            w.nullValue();
        } else if (v instanceof String s) {
            w.value(s);
        } else if (v instanceof Boolean b) {
            w.value(b);
        } else if (v instanceof Double d) {
            if (d.isNaN() || d.isInfinite()) {
                w.nullValue();
            } else {
                w.value(d.doubleValue());
            }
        } else if (v instanceof Float f) {
            if (f.isNaN() || f.isInfinite()) {
                w.nullValue();
            } else {
                w.value(f.doubleValue());
            }
        } else if (v instanceof Number n) {
            w.value(n);
        } else if (v instanceof Map<?, ?> m) {
            w.beginObject();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                w.name(String.valueOf(e.getKey()));
                writeValue(w, e.getValue());
            }
            w.endObject();
        } else if (v instanceof Iterable<?> it) {
            w.beginArray();
            for (Object o : it) {
                writeValue(w, o);
            }
            w.endArray();
        } else if (v instanceof Object[] arr) {
            w.beginArray();
            for (Object o : arr) {
                writeValue(w, o);
            }
            w.endArray();
        } else if (v instanceof long[] arr) {
            w.beginArray();
            for (long o : arr) {
                w.value(o);
            }
            w.endArray();
        } else {
            w.value(String.valueOf(v));
        }
    }

    /** Parses JSON into {@code Map<String,Object>}, {@code List<Object>}, String, Long, Double, Boolean or null. */
    public static Object parse(String json) {
        return convert(JsonParser.parseString(json));
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String json) {
        Object o = parse(json);
        if (!(o instanceof Map)) {
            throw new IllegalArgumentException("expected a JSON object");
        }
        return (Map<String, Object>) o;
    }

    private static Object convert(JsonElement e) {
        if (e == null || e.isJsonNull()) {
            return null;
        }
        if (e.isJsonObject()) {
            Map<String, Object> m = new LinkedHashMap<>();
            e.getAsJsonObject().entrySet().forEach(en -> m.put(en.getKey(), convert(en.getValue())));
            return m;
        }
        if (e.isJsonArray()) {
            JsonArray a = e.getAsJsonArray();
            List<Object> l = new ArrayList<>(a.size());
            a.forEach(x -> l.add(convert(x)));
            return l;
        }
        var p = e.getAsJsonPrimitive();
        if (p.isBoolean()) {
            return p.getAsBoolean();
        }
        if (p.isNumber()) {
            BigDecimal bd = p.getAsBigDecimal();
            try {
                return bd.longValueExact();
            } catch (ArithmeticException ex) {
                return bd.doubleValue();
            }
        }
        return p.getAsString();
    }
}
