package dev.routeplane.observability;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

/**
 * A small, dependency-free metrics registry that renders the Prometheus text exposition format (0.0.4).
 *
 * <p>Design points:
 * <ul>
 *   <li>Label <em>names</em> are fixed per family; label <em>values</em> must come from configuration or from small
 *       closed sets (status class, outcome), never from client input. As a backstop each family caps its number of
 *       series; beyond the cap new label combinations share an unexported overflow child and
 *       {@code routeplane_metric_series_dropped_total} is incremented.</li>
 *   <li>Hot-path updates are lock-free ({@link LongAdder}); callers pre-bind children once and keep the handle.</li>
 *   <li>Values that are cheaper to read than to push (queue depth, breaker state, JVM) are produced by
 *       {@link Collector}s at scrape time.</li>
 * </ul>
 */
public final class MetricsRegistry {

    /** Produces samples when the registry is scraped. */
    @FunctionalInterface
    public interface Collector {
        void collect(SampleSink sink);
    }

    /** Receives samples from a {@link Collector}. Labels are passed as alternating names and values. */
    public interface SampleSink {
        void gauge(String name, String help, double value, String... labels);

        void counter(String name, String help, double value, String... labels);
    }

    private final int maxSeriesPerFamily;
    private final ConcurrentMap<String, Family<?>> byName = new ConcurrentHashMap<>();
    private final List<Family<?>> ordered = new CopyOnWriteArrayList<>();
    private final List<Collector> collectors = new CopyOnWriteArrayList<>();
    private final LongAdder droppedSeries = new LongAdder();

    public MetricsRegistry() {
        this(2000);
    }

    public MetricsRegistry(int maxSeriesPerFamily) {
        this.maxSeriesPerFamily = maxSeriesPerFamily;
    }

    public Counter counter(String name, String help, String... labelNames) {
        return register(new Counter(name, help, labelNames, maxSeriesPerFamily, droppedSeries));
    }

    public Gauge gauge(String name, String help, String... labelNames) {
        return register(new Gauge(name, help, labelNames, maxSeriesPerFamily, droppedSeries));
    }

    public Histogram histogram(String name, String help, double[] bucketsSeconds, String... labelNames) {
        return register(new Histogram(name, help, bucketsSeconds, labelNames, maxSeriesPerFamily, droppedSeries));
    }

    public void collector(Collector collector) {
        collectors.add(collector);
    }

    /** Convenience for an unlabelled callback gauge. */
    public void gaugeFn(String name, String help, Supplier<Number> supplier) {
        collector(sink -> sink.gauge(name, help, supplier.get().doubleValue()));
    }

    public long droppedSeries() {
        return droppedSeries.sum();
    }

    @SuppressWarnings("unchecked")
    private <F extends Family<?>> F register(F family) {
        Family<?> existing = byName.putIfAbsent(family.name, family);
        if (existing == null) {
            ordered.add(family);
            return family;
        }
        if (existing.getClass() != family.getClass() || !Arrays.equals(existing.labelNames, family.labelNames)) {
            throw new IllegalStateException("metric '" + family.name + "' already registered with a different shape");
        }
        return (F) existing;
    }

    // ------------------------------------------------------------------------------------------------ exposition

    /** Renders all metrics in the Prometheus text format. */
    public String scrape() {
        StringBuilder out = new StringBuilder(16 * 1024);
        for (Family<?> f : ordered) {
            f.render(out);
        }
        Map<String, List<Sample>> grouped = new java.util.LinkedHashMap<>();
        Map<String, String[]> meta = new java.util.HashMap<>();
        SampleSink sink = new SampleSink() {
            @Override
            public void gauge(String name, String help, double value, String... labels) {
                add(name, help, "gauge", value, labels);
            }

            @Override
            public void counter(String name, String help, double value, String... labels) {
                add(name, help, "counter", value, labels);
            }

            private void add(String name, String help, String type, double value, String[] labels) {
                meta.putIfAbsent(name, new String[] {help, type});
                grouped.computeIfAbsent(name, k -> new ArrayList<>()).add(new Sample(labels.clone(), value));
            }
        };
        for (Collector c : collectors) {
            try {
                c.collect(sink);
            } catch (RuntimeException e) {
                // A broken collector must never take the whole scrape down.
                sink.counter("routeplane_metrics_collector_errors_total", "Collectors that threw during a scrape", 1);
            }
        }
        for (Map.Entry<String, List<Sample>> e : grouped.entrySet()) {
            String[] m = meta.get(e.getKey());
            header(out, e.getKey(), m[0], m[1]);
            for (Sample s : e.getValue()) {
                sample(out, e.getKey(), "", s.labels, null, null, s.value);
            }
        }
        header(out, "routeplane_metric_series_dropped_total", "Label combinations rejected by the per-family series cap", "counter");
        sample(out, "routeplane_metric_series_dropped_total", "", new String[0], null, null, droppedSeries.sum());
        return out.toString();
    }

    private record Sample(String[] labels, double value) {
    }

    static void header(StringBuilder out, String name, String help, String type) {
        out.append("# HELP ").append(name).append(' ').append(escapeHelp(help)).append('\n');
        out.append("# TYPE ").append(name).append(' ').append(type).append('\n');
    }

    /** Appends one sample line. {@code labels} holds alternating names and values for collector samples. */
    static void sample(StringBuilder out, String name, String suffix, String[] labels, String[] labelNames,
                       String[] extra, double value) {
        out.append(name).append(suffix);
        boolean open = false;
        if (labelNames != null) {
            for (int i = 0; i < labelNames.length; i++) {
                out.append(open ? ',' : '{').append(labelNames[i]).append("=\"");
                escapeLabel(out, labels[i]);
                out.append('"');
                open = true;
            }
        } else {
            for (int i = 0; i + 1 < labels.length; i += 2) {
                out.append(open ? ',' : '{').append(labels[i]).append("=\"");
                escapeLabel(out, labels[i + 1]);
                out.append('"');
                open = true;
            }
        }
        if (extra != null) {
            for (int i = 0; i + 1 < extra.length; i += 2) {
                out.append(open ? ',' : '{').append(extra[i]).append("=\"");
                escapeLabel(out, extra[i + 1]);
                out.append('"');
                open = true;
            }
        }
        if (open) {
            out.append('}');
        }
        out.append(' ').append(formatValue(value)).append('\n');
    }

    static String formatValue(double v) {
        if (Double.isNaN(v)) {
            return "NaN";
        }
        if (Double.isInfinite(v)) {
            return v > 0 ? "+Inf" : "-Inf";
        }
        if (v == Math.rint(v) && Math.abs(v) < 1e15) {
            return Long.toString((long) v);
        }
        return Double.toString(v);
    }

    static void escapeLabel(StringBuilder out, String v) {
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                default -> out.append(c);
            }
        }
    }

    private static String escapeHelp(String help) {
        return help.replace("\\", "\\\\").replace("\n", "\\n");
    }

    // ----------------------------------------------------------------------------------------------- families

    abstract static class Family<C> {
        final String name;
        final String help;
        final String[] labelNames;
        private final int maxSeries;
        private final LongAdder dropped;
        private final ConcurrentMap<List<String>, C> children = new ConcurrentHashMap<>();
        private volatile C overflow;

        Family(String name, String help, String[] labelNames, int maxSeries, LongAdder dropped) {
            this.name = name;
            this.help = help;
            this.labelNames = labelNames.clone();
            this.maxSeries = maxSeries;
            this.dropped = dropped;
        }

        abstract C create();

        abstract String type();

        abstract void renderChild(StringBuilder out, String[] labelValues, C child);

        /** Returns (creating on first use) the child for the given label values. */
        public final C labels(String... values) {
            if (values.length != labelNames.length) {
                throw new IllegalArgumentException(name + " expects " + labelNames.length + " label values");
            }
            List<String> key = List.of(values);
            C c = children.get(key);
            if (c != null) {
                return c;
            }
            if (children.size() >= maxSeries) {
                dropped.increment();
                C o = overflow;
                if (o == null) {
                    synchronized (this) {
                        if (overflow == null) {
                            overflow = create();
                        }
                        o = overflow;
                    }
                }
                return o;
            }
            return children.computeIfAbsent(key, k -> create());
        }

        final void render(StringBuilder out) {
            header(out, name, help, type());
            List<Map.Entry<List<String>, C>> entries = new ArrayList<>(children.entrySet());
            entries.sort(Comparator.comparing((Map.Entry<List<String>, C> e) -> String.join("\u0000", e.getKey())));
            for (Map.Entry<List<String>, C> e : entries) {
                renderChild(out, e.getKey().toArray(new String[0]), e.getValue());
            }
        }
    }

    /** A monotonically increasing counter family. */
    public static final class Counter extends Family<Counter.Child> {
        Counter(String name, String help, String[] labelNames, int max, LongAdder dropped) {
            super(name, help, labelNames, max, dropped);
        }

        @Override
        Child create() {
            return new Child();
        }

        @Override
        String type() {
            return "counter";
        }

        @Override
        void renderChild(StringBuilder out, String[] values, Child c) {
            sample(out, name, "", values, labelNames, null, c.value());
        }

        public void inc(String... labelValues) {
            labels(labelValues).inc();
        }

        public static final class Child {
            private final LongAdder value = new LongAdder();

            public void inc() {
                value.increment();
            }

            public void add(long n) {
                if (n < 0) {
                    throw new IllegalArgumentException("counters cannot decrease");
                }
                value.add(n);
            }

            public long value() {
                return value.sum();
            }
        }
    }

    /** A gauge family (a value that can go up and down). */
    public static final class Gauge extends Family<Gauge.Child> {
        Gauge(String name, String help, String[] labelNames, int max, LongAdder dropped) {
            super(name, help, labelNames, max, dropped);
        }

        @Override
        Child create() {
            return new Child();
        }

        @Override
        String type() {
            return "gauge";
        }

        @Override
        void renderChild(StringBuilder out, String[] values, Child c) {
            sample(out, name, "", values, labelNames, null, c.value());
        }

        public static final class Child {
            private final AtomicLong bits = new AtomicLong(Double.doubleToRawLongBits(0.0));

            public void set(double v) {
                bits.set(Double.doubleToRawLongBits(v));
            }

            public void add(double delta) {
                bits.updateAndGet(b -> Double.doubleToRawLongBits(Double.longBitsToDouble(b) + delta));
            }

            public void inc() {
                add(1);
            }

            public void dec() {
                add(-1);
            }

            public double value() {
                return Double.longBitsToDouble(bits.get());
            }
        }
    }

    /** A fixed-bucket histogram family; observations are recorded in nanoseconds and exported in seconds. */
    public static final class Histogram extends Family<Histogram.Child> {
        private final double[] bounds;
        private final long[] boundsNanos;

        Histogram(String name, String help, double[] bounds, String[] labelNames, int max, LongAdder dropped) {
            super(name, help, labelNames, max, dropped);
            this.bounds = bounds.clone();
            this.boundsNanos = new long[bounds.length];
            for (int i = 0; i < bounds.length; i++) {
                boundsNanos[i] = (long) (bounds[i] * 1e9);
                if (i > 0 && bounds[i] <= bounds[i - 1]) {
                    throw new IllegalArgumentException("histogram buckets must be strictly increasing");
                }
            }
        }

        @Override
        Child create() {
            return new Child(boundsNanos);
        }

        @Override
        String type() {
            return "histogram";
        }

        @Override
        void renderChild(StringBuilder out, String[] values, Child c) {
            long cumulative = 0;
            for (int i = 0; i < bounds.length; i++) {
                cumulative += c.buckets[i].sum();
                sample(out, name, "_bucket", values, labelNames, new String[] {"le", formatValue(bounds[i])}, cumulative);
            }
            cumulative += c.buckets[bounds.length].sum();
            sample(out, name, "_bucket", values, labelNames, new String[] {"le", "+Inf"}, cumulative);
            sample(out, name, "_sum", values, labelNames, null, c.sumNanos.sum() / 1e9);
            sample(out, name, "_count", values, labelNames, null, c.count.sum());
        }

        public static final class Child {
            private final long[] boundsNanos;
            private final LongAdder[] buckets;
            private final LongAdder sumNanos = new LongAdder();
            private final LongAdder count = new LongAdder();

            Child(long[] boundsNanos) {
                this.boundsNanos = boundsNanos;
                this.buckets = new LongAdder[boundsNanos.length + 1];
                for (int i = 0; i < buckets.length; i++) {
                    buckets[i] = new LongAdder();
                }
            }

            public void observeNanos(long nanos) {
                long n = Math.max(0, nanos);
                int i = 0;
                while (i < boundsNanos.length && n > boundsNanos[i]) {
                    i++;
                }
                buckets[i].increment();
                sumNanos.add(n);
                count.increment();
            }

            public long count() {
                return count.sum();
            }
        }
    }
}
