package dev.routeplane.proxy;

import dev.routeplane.config.RouteplaneConfig.RouteConfig;
import dev.routeplane.observability.LatencyWindow;
import dev.routeplane.observability.MetricsRegistry.Counter;
import dev.routeplane.observability.MetricsRegistry.Histogram;
import dev.routeplane.observability.StructuredLogger;
import dev.routeplane.resilience.Backoff;
import dev.routeplane.resilience.RetryBudget;
import dev.routeplane.routing.Candidate;
import dev.routeplane.routing.RouteMatcher;
import dev.routeplane.routing.RoutingPolicy;
import dev.routeplane.util.TimeSource;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** One route of one configuration version: matcher, policy, upstream bindings, retry machinery, shadowing, metrics. */
final class RouteRuntime {
    final RouteConfig config;
    final String id;
    final RouteMatcher matcher;
    final RoutingPolicy policy;
    final List<UpstreamBinding> primaries;
    final List<UpstreamBinding> fallbacks;
    final Backoff backoff;
    final RetryBudget retryBudget;
    final ShadowDispatcher shadow;
    final LatencyWindow requestWindow;
    final Duration connectTimeout;
    final Duration responseTimeout;
    final Duration requestTimeout;

    final Counter.Child[] requestsByClass = new Counter.Child[ProxyMetrics.STATUS_CLASSES.length];
    final Histogram.Child duration;
    final Counter.Child bytesIn;
    final Counter.Child bytesOut;
    final Counter.Child policyErrors;
    final Counter.Child budgetExhausted;
    private final EnumMap<FailureKind, Counter.Child> errors = new EnumMap<>(FailureKind.class);
    private final Map<String, Counter.Child> retries = new ConcurrentHashMap<>();
    private final Map<String, Counter.Child> retrySkipped = new ConcurrentHashMap<>();
    private final Map<String, Counter.Child> timeouts = new ConcurrentHashMap<>();
    private final Map<String, Counter.Child> fallbackUsed = new ConcurrentHashMap<>();
    private final ProxyMetrics metrics;
    private final StructuredLogger log;

    RouteRuntime(RouteConfig config, RoutingPolicy policy, List<UpstreamBinding> primaries, List<UpstreamBinding> fallbacks,
                 RetryBudget retryBudget, Backoff backoff, ShadowDispatcher shadow, ProxyMetrics metrics, TimeSource time,
                 long latencyWindowMillis, StructuredLogger log) {
        this.config = config;
        this.id = config.id();
        this.matcher = RouteMatcher.of(config.match());
        this.policy = policy;
        this.primaries = List.copyOf(primaries);
        this.fallbacks = List.copyOf(fallbacks);
        this.retryBudget = retryBudget;
        this.backoff = backoff;
        this.shadow = shadow;
        this.metrics = metrics;
        this.log = log;
        this.requestWindow = new LatencyWindow(time, latencyWindowMillis, 6);
        this.connectTimeout = config.timeouts().connect();
        this.responseTimeout = config.timeouts().response();
        this.requestTimeout = config.timeouts().request();
        for (int i = 0; i < requestsByClass.length; i++) {
            requestsByClass[i] = metrics.requests.labels(id, ProxyMetrics.STATUS_CLASSES[i]);
        }
        this.duration = metrics.requestDuration.labels(id);
        this.bytesIn = metrics.bytesIn.labels(id);
        this.bytesOut = metrics.bytesOut.labels(id);
        this.policyErrors = metrics.policyErrors.labels(id);
        this.budgetExhausted = metrics.retryBudgetExhausted.labels(id);
        for (FailureKind k : FailureKind.values()) {
            if (k != FailureKind.NONE) {
                errors.put(k, metrics.errors.labels(id, k.code()));
            }
        }
    }

    Counter.Child error(FailureKind kind) {
        return errors.get(kind);
    }

    Counter.Child retried(String reason) {
        return retries.computeIfAbsent(reason, r -> metrics.retries.labels(id, r));
    }

    Counter.Child retrySkipped(String reason) {
        return retrySkipped.computeIfAbsent(reason, r -> metrics.retrySkipped.labels(id, r));
    }

    Counter.Child timeout(String kind) {
        return timeouts.computeIfAbsent(kind, k -> metrics.timeouts.labels(id, k));
    }

    Counter.Child fallback(String kind) {
        return fallbackUsed.computeIfAbsent(kind, k -> metrics.fallbackUsed.labels(id, k));
    }

    /**
     * Runs the routing policy over {@code candidates}. A policy that throws or returns something that is not one of the
     * candidates must not fail the request: the first candidate is used and the error is counted and logged.
     */
    UpstreamBinding choose(ProxyRequest req, List<UpstreamBinding> candidates) {
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        try {
            Candidate c = policy.select(req, candidates);
            for (UpstreamBinding b : candidates) {
                if (b == c) {
                    return b;
                }
            }
            throw new IllegalStateException("policy returned a candidate that was not offered: " + (c == null ? "null" : c.id()));
        } catch (RuntimeException e) {
            policyErrors.inc();
            log.error("routing_policy_error", e, "route", id, "policy", config.policy().type());
            return candidates.get(0);
        }
    }

    boolean retryEnabledFor(String method) {
        return config.retry().enabled() && config.retry().methods().contains(method);
    }

    /** True when the body must be kept in memory so that the request can be retried or mirrored. */
    boolean needsReplayableBody(String method, long contentLength) {
        if (retryEnabledFor(method)) {
            return true;
        }
        return shadow != null && shadow.wantsBody(method, Math.max(0, contentLength));
    }
}
