package dev.routeplane.proxy;

import dev.routeplane.observability.LatencyWindow;
import dev.routeplane.observability.MetricsRegistry;
import dev.routeplane.observability.TcpStats;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/** Registers the gauges that are read from the current runtime state at scrape time. */
public final class StateMetrics {
    private StateMetrics() {
    }

    public static void register(MetricsRegistry r, Supplier<RuntimeState> state, TcpStats tcp, IntSupplier proxyPort) {
        r.collector(sink -> {
            RuntimeState st = state.get();
            if (st == null) {
                return;
            }
            sink.gauge("routeplane_config_version", "Version counter of the active configuration", st.version());
            sink.gauge("routeplane_config_last_reload_timestamp_seconds", "When the active configuration was loaded", st.loadedAtMillis() / 1000.0);
            sink.gauge("routeplane_config_routes", "Routes in the active configuration", st.routes().size());
            for (UpstreamRuntime u : st.upstreams().values()) {
                String[] l = {"route", u.routeId, "upstream", u.id};
                int inFlight = u.limiter.inFlight();
                sink.gauge("routeplane_upstream_in_flight", "Requests currently in flight to the upstream", inFlight, l);
                sink.gauge("routeplane_upstream_concurrency_limit", "Configured per-upstream concurrency limit", u.limiter.limit(), l);
                sink.gauge("routeplane_upstream_pool_utilization_ratio", "In-flight requests divided by the concurrency limit", (double) inFlight / u.limiter.limit(), l);
                sink.gauge("routeplane_upstream_healthy", "1 if neither health checks nor outlier ejection exclude the upstream", u.health.isAvailable() ? 1 : 0, l);
                sink.gauge("routeplane_upstream_ejected", "1 while the upstream is passively ejected", u.health.isEjected() ? 1 : 0, l);
                if (u.breaker != null) {
                    sink.gauge("routeplane_circuit_breaker_state", "Circuit breaker state: 0 closed, 1 open, 2 half-open", u.breaker.state().ordinal(), l);
                }
                LatencyWindow.Snapshot snap = u.ttfbWindow.snapshot();
                for (double q : LatencyWindow.QUANTILES) {
                    double v = snap.quantileSeconds(q);
                    if (!Double.isNaN(v)) {
                        sink.gauge("routeplane_upstream_duration_quantile_seconds", "Sliding-window upstream time-to-headers percentile; omitted until the window holds enough samples",
                                v, "route", u.routeId, "upstream", u.id, "quantile", Double.toString(q));
                    }
                }
            }
            for (RouteRuntime route : st.routes()) {
                LatencyWindow.Snapshot snap = route.requestWindow.snapshot();
                sink.gauge("routeplane_request_duration_window_samples", "Samples in the sliding latency window", snap.samples(), "route", route.id);
                for (double q : LatencyWindow.QUANTILES) {
                    double v = snap.quantileSeconds(q);
                    if (!Double.isNaN(v)) {
                        sink.gauge("routeplane_request_duration_quantile_seconds",
                                "Sliding-window request duration percentile; omitted until the window holds enough samples for it to be meaningful (10/(1-q))",
                                v, "route", route.id, "quantile", Double.toString(q));
                    }
                }
                if (route.shadow != null) {
                    for (ShadowDispatcher.TargetView t : route.shadow.targetViews()) {
                        String[] l = {"route", route.id, "target", t.id()};
                        sink.gauge("routeplane_shadow_queue_depth", "Shadow tasks waiting for a worker", t.queueDepth(), l);
                        sink.gauge("routeplane_shadow_queue_capacity", "Shadow queue capacity", t.queueCapacity(), l);
                        sink.gauge("routeplane_shadow_in_flight", "Shadow requests currently executing", t.inFlight(), l);
                    }
                }
            }
            if (tcp != null && tcp.available()) {
                TcpStats.Counts c = tcp.snapshot();
                sink.gauge("routeplane_tcp_connections", "ESTABLISHED TCP connections as seen by the kernel (Linux): downstream clients and upstream pools", c.byLocalPort().getOrDefault(proxyPort.getAsInt(), 0), "role", "downstream", "route", "", "upstream", "");
                for (UpstreamRuntime u : st.upstreams().values()) {
                    sink.gauge("routeplane_tcp_connections", "ESTABLISHED TCP connections as seen by the kernel (Linux): downstream clients and upstream pools",
                            c.byRemotePort().getOrDefault(u.spec.url().getPort(), 0), "role", "upstream", "route", u.routeId, "upstream", u.id);
                }
            }
        });
    }
}
