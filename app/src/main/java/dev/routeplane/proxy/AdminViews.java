package dev.routeplane.proxy;

import dev.routeplane.config.RouteplaneConfig.UpstreamConfig;
import dev.routeplane.observability.LatencyWindow;
import dev.routeplane.resilience.CircuitBreaker;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds the JSON documents served by the admin API from the runtime state. */
public final class AdminViews {
    private AdminViews() {
    }

    public static List<Object> routes(RuntimeState state) {
        List<Object> out = new ArrayList<>();
        for (RouteRuntime r : state.routesInOrder()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.id);
            m.put("priority", r.config.priority());
            m.put("match", ConfigView.render(r.config.match()));
            m.put("stripPrefix", r.config.stripPrefix());
            m.put("policy", ConfigView.render(r.config.policy()));
            m.put("timeouts", ConfigView.render(r.config.timeouts()));
            m.put("retry", ConfigView.render(r.config.retry()));
            m.put("circuitBreaker", ConfigView.render(r.config.circuitBreaker()));
            m.put("health", ConfigView.render(r.config.health()));
            m.put("upstreams", upstreamList(r.primaries));
            m.put("fallback", r.config.fallback() == null ? null : Map.of("upstreams", upstreamList(r.fallbacks),
                    "staticResponse", r.config.fallback().response() != null));
            List<Object> targets = new ArrayList<>();
            if (r.shadow != null) {
                for (ShadowDispatcher.TargetView t : r.shadow.targetViews()) {
                    Map<String, Object> tm = new LinkedHashMap<>();
                    tm.put("id", t.id());
                    tm.put("samplePercent", t.samplePercent());
                    tm.put("queueDepth", t.queueDepth());
                    tm.put("queueCapacity", t.queueCapacity());
                    tm.put("inFlight", t.inFlight());
                    tm.put("maxConcurrent", t.maxConcurrent());
                    targets.add(tm);
                }
            }
            m.put("shadow", r.shadow == null ? null : Map.of("methods", ConfigView.render(r.shadow.config().methods()), "targets", targets));
            LatencyWindow.Snapshot snap = r.requestWindow.snapshot();
            m.put("latencyWindow", quantiles(snap));
            out.add(m);
        }
        return out;
    }

    private static List<Object> upstreamList(List<UpstreamBinding> list) {
        List<Object> out = new ArrayList<>();
        for (UpstreamBinding b : list) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", b.runtime.id);
            m.put("url", b.runtime.spec.url().toString());
            m.put("weight", b.weight);
            out.add(m);
        }
        return out;
    }

    public static List<Object> upstreams(RuntimeState state) {
        List<Object> out = new ArrayList<>();
        for (UpstreamRuntime u : state.upstreams().values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("route", u.routeId);
            m.put("id", u.id);
            m.put("url", u.spec.url().toString());
            m.put("available", u.eligible());
            m.put("healthy", u.health.isAvailable());
            m.put("activeCheckDown", u.health.isActiveDown());
            m.put("ejected", u.health.isEjected());
            m.put("ejections", u.health.ejections());
            m.put("inFlight", u.limiter.inFlight());
            m.put("maxConcurrent", u.limiter.limit());
            m.put("circuit", u.breaker == null ? "none" : u.breaker.state().name().toLowerCase());
            m.put("ttfbWindow", quantiles(u.ttfbWindow.snapshot()));
            out.add(m);
        }
        return out;
    }

    public static List<Object> circuits(RuntimeState state) {
        List<Object> out = new ArrayList<>();
        for (UpstreamRuntime u : state.upstreams().values()) {
            if (u.breaker == null) {
                continue;
            }
            CircuitBreaker.Snapshot s = u.breaker.snapshot();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("route", u.routeId);
            m.put("upstream", u.id);
            m.put("state", s.state().name().toLowerCase());
            m.put("windowCalls", s.windowCalls());
            m.put("windowFailures", s.windowFailures());
            m.put("failureRatePercent", s.failureRatePercent());
            m.put("openRemainingMillis", s.openRemainingMillis());
            m.put("probesInFlight", s.probesInFlight());
            m.put("probeSuccesses", s.probeSuccesses());
            m.put("totalSuccesses", s.totalSuccesses());
            m.put("totalFailures", s.totalFailures());
            m.put("totalRejected", s.totalRejected());
            m.put("transitionCount", s.transitionCount());
            List<Object> history = new ArrayList<>();
            for (CircuitBreaker.Transition t : s.recentTransitions()) {
                history.add(Map.of("atMillis", t.atMillis(), "from", t.from().name().toLowerCase(), "to", t.to().name().toLowerCase(), "reason", t.reason()));
            }
            m.put("recentTransitions", history);
            out.add(m);
        }
        return out;
    }

    private static Map<String, Object> quantiles(LatencyWindow.Snapshot snap) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("samples", snap.samples());
        for (double q : LatencyWindow.QUANTILES) {
            double v = snap.quantileSeconds(q);
            m.put("p" + (q * 100 % 1 == 0 ? String.valueOf((int) (q * 100)) : String.valueOf(q * 100)), Double.isNaN(v) ? null : v);
        }
        return m;
    }

    /** For the Prometheus collector: the declared upstreams of the current state. */
    static List<UpstreamConfig> declared(RuntimeState state) {
        List<UpstreamConfig> out = new ArrayList<>();
        state.config().routes().forEach(r -> out.addAll(r.upstreams()));
        return out;
    }
}
