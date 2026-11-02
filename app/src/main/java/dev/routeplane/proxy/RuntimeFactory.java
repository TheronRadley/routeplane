package dev.routeplane.proxy;

import dev.routeplane.config.RouteplaneConfig;
import dev.routeplane.config.RouteplaneConfig.RouteConfig;
import dev.routeplane.config.RouteplaneConfig.UpstreamConfig;
import dev.routeplane.observability.StructuredLogger;
import dev.routeplane.resilience.Backoff;
import dev.routeplane.resilience.RetryBudget;
import dev.routeplane.routing.PolicyRegistry;
import dev.routeplane.routing.RouteMatcher;
import dev.routeplane.routing.RouteTable;
import dev.routeplane.routing.RoutingPolicy;
import dev.routeplane.util.TimeSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Turns a validated {@link RouteplaneConfig} into a {@link RuntimeState}. Upstream runtimes (clients, pools, breakers,
 * health) and shadow dispatchers whose settings did not change are carried over from the previous state so that a
 * reload neither resets circuit breakers nor drops pooled connections or queued shadow work.
 */
final class RuntimeFactory {
    private final PolicyRegistry policies;
    private final ProxyMetrics metrics;
    private final TimeSource time;
    private final StructuredLogger log;

    RuntimeFactory(PolicyRegistry policies, ProxyMetrics metrics, TimeSource time, StructuredLogger log) {
        this.policies = policies;
        this.metrics = metrics;
        this.time = time;
        this.log = log;
    }

    RuntimeState build(RouteplaneConfig cfg, long version, String sha256, long loadedAtMillis, RuntimeState previous) {
        List<AutoCloseable> created = new ArrayList<>();
        try {
            return build0(cfg, version, sha256, loadedAtMillis, previous, created);
        } catch (RuntimeException | Error e) {
            // a half-built state must not leak HTTP clients, selector threads or shadow workers
            for (AutoCloseable c : created) {
                try {
                    c.close();
                } catch (Exception ignored) {
                    // best effort
                }
            }
            throw e;
        }
    }

    private RuntimeState build0(RouteplaneConfig cfg, long version, String sha256, long loadedAtMillis, RuntimeState previous,
                                List<AutoCloseable> created) {
        Map<String, UpstreamRuntime> upstreams = new LinkedHashMap<>();
        Map<String, RouteRuntime> previousRoutes = new LinkedHashMap<>();
        if (previous != null) {
            previous.routes().forEach(r -> previousRoutes.put(r.id, r));
        }
        long windowMillis = cfg.metrics().latencyWindow().toMillis();
        List<RouteRuntime> routes = new ArrayList<>();
        RouteTable.Builder<RouteRuntime> table = RouteTable.builder();
        for (RouteConfig rc : cfg.routes()) {
            List<UpstreamBinding> primaries = bindings(rc, rc.upstreams(), cfg, previous, upstreams, windowMillis, created);
            List<UpstreamBinding> fallbacks = rc.fallback() == null ? List.of() : bindings(rc, rc.fallback().upstreams(), cfg, previous, upstreams, windowMillis, created);
            RoutingPolicy policy = policies.find(rc.policy().type()).orElseThrow().create(rc.policy().options());
            var retry = rc.retry();
            RetryBudget budget = new RetryBudget(retry.budgetRatio(), retry.budgetMinRetriesPerSecond(), retry.budgetWindow(), time);
            Backoff backoff = new Backoff(retry.backoffInitial(), retry.backoffMax(), retry.backoffMultiplier(), retry.jitter(), ThreadLocalRandom.current());
            ShadowDispatcher shadow = null;
            if (rc.shadow() != null) {
                RouteRuntime old = previousRoutes.get(rc.id());
                if (old != null && old.shadow != null && old.shadow.config().equals(rc.shadow())) {
                    shadow = old.shadow;
                } else {
                    shadow = new ShadowDispatcher(rc.id(), rc.shadow(), metrics, time, log, ThreadLocalRandom.current());
                    created.add(shadow);
                }
            }
            RouteRuntime rr = new RouteRuntime(rc, policy, primaries, fallbacks, budget, backoff, shadow, metrics, time, windowMillis, log);
            routes.add(rr);
            table.add(RouteMatcher.of(rc.match()), rc.priority(), rr);
        }
        RuntimeState state = new RuntimeState(version, sha256, loadedAtMillis, cfg, table.build(), routes, upstreams);
        upstreams.values().forEach(UpstreamRuntime::startActiveChecks);
        return state;
    }

    private List<UpstreamBinding> bindings(RouteConfig rc, List<UpstreamConfig> list, RouteplaneConfig cfg, RuntimeState previous,
                                           Map<String, UpstreamRuntime> upstreams, long windowMillis, List<AutoCloseable> created) {
        List<UpstreamBinding> out = new ArrayList<>();
        for (UpstreamConfig uc : list) {
            UpstreamRuntime.Spec spec = new UpstreamRuntime.Spec(uc.url(), uc.maxConcurrent(), rc.timeouts().connect(), rc.circuitBreaker(), rc.health());
            String key = rc.id() + "/" + uc.id();
            UpstreamRuntime existing = previous == null ? null : previous.upstreams().get(key);
            UpstreamRuntime rt = existing != null && existing.spec.equals(spec)
                    ? existing
                    : new UpstreamRuntime(rc.id(), uc.id(), spec, metrics, time, windowMillis, log);
            if (rt != existing) {
                created.add(rt);
            }
            upstreams.put(key, rt);
            out.add(new UpstreamBinding(rt, uc.weight()));
        }
        return out;
    }
}
