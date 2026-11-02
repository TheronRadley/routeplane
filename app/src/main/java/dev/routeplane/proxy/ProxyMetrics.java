package dev.routeplane.proxy;

import dev.routeplane.observability.MetricsRegistry;
import dev.routeplane.observability.MetricsRegistry.Counter;
import dev.routeplane.observability.MetricsRegistry.Gauge;
import dev.routeplane.observability.MetricsRegistry.Histogram;

/**
 * All metric families of the data plane (see docs/observability... in docs/architecture.md for the catalogue).
 * Label values are limited to configured route/upstream ids and small closed sets; nothing comes from client input.
 */
public final class ProxyMetrics {
    public static final double[] DURATION_BUCKETS = {0.0005, 0.001, 0.0025, 0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10, 30};
    public static final String[] STATUS_CLASSES = {"1xx", "2xx", "3xx", "4xx", "5xx", "other"};

    final MetricsRegistry registry;
    final Counter requests;
    final Histogram requestDuration;
    final Counter errors;
    final Counter timeouts;
    final Counter bytesIn;
    final Counter bytesOut;
    final Counter upstreamRequests;
    final Counter upstreamResponses;
    final Histogram upstreamDuration;
    final Counter retries;
    final Counter retrySkipped;
    final Counter retryBudgetExhausted;
    final Counter breakerTransitions;
    final Counter breakerRejections;
    final Counter policyErrors;
    final Counter healthChecks;
    final Counter ejections;
    final Counter fallbackUsed;
    final Counter shadowSampled;
    final Counter shadowRequests;
    final Counter shadowFailures;
    final Counter shadowDropped;
    final Counter shadowSkipped;
    final Counter shadowComparisons;
    final Histogram shadowDuration;
    final Gauge activeRequests;
    final Gauge draining;

    public ProxyMetrics(MetricsRegistry r) {
        this.registry = r;
        requests = r.counter("routeplane_requests_total", "Downstream requests completed, by route and response status class", "route", "status_class");
        requestDuration = r.histogram("routeplane_request_duration_seconds", "Downstream request duration from first byte of the request to the end of the response", DURATION_BUCKETS, "route");
        errors = r.counter("routeplane_errors_total", "Requests that failed without a normal upstream response, by failure kind (proxy-generated or aborted)", "route", "kind");
        timeouts = r.counter("routeplane_timeouts_total", "Timeouts by kind (connect, response, request)", "route", "kind");
        bytesIn = r.counter("routeplane_request_body_bytes_total", "Request body bytes received from clients", "route");
        bytesOut = r.counter("routeplane_response_body_bytes_total", "Response body bytes sent to clients", "route");
        upstreamRequests = r.counter("routeplane_upstream_requests_total", "Requests sent to upstreams; attempt=first or retry", "route", "upstream", "attempt");
        upstreamResponses = r.counter("routeplane_upstream_responses_total", "Responses received from upstreams, by status class", "route", "upstream", "status_class");
        upstreamDuration = r.histogram("routeplane_upstream_duration_seconds", "Time from sending an upstream request until its response headers arrive", DURATION_BUCKETS, "route", "upstream");
        retries = r.counter("routeplane_retries_total", "Retries performed, by reason", "route", "reason");
        retrySkipped = r.counter("routeplane_retry_skipped_total", "Retries that were eligible by outcome but not performed, by reason", "route", "reason");
        retryBudgetExhausted = r.counter("routeplane_retry_budget_exhausted_total", "Retries denied because the retry budget was used up", "route");
        breakerTransitions = r.counter("routeplane_circuit_breaker_transitions_total", "Circuit breaker state transitions", "route", "upstream", "from", "to");
        breakerRejections = r.counter("routeplane_circuit_breaker_rejections_total", "Calls rejected because the breaker was open or half-open at its probe limit", "route", "upstream");
        policyErrors = r.counter("routeplane_policy_errors_total", "Routing policy exceptions or invalid selections (fell back to the first candidate)", "route");
        healthChecks = r.counter("routeplane_health_checks_total", "Active health probes by result", "route", "upstream", "result");
        ejections = r.counter("routeplane_upstream_ejections_total", "Passive outlier ejections", "route", "upstream");
        fallbackUsed = r.counter("routeplane_fallback_total", "Requests served by a fallback upstream or the static fallback response", "route", "kind");
        shadowSampled = r.counter("routeplane_shadow_sampled_total", "Requests selected for shadowing by the sampler", "route", "target");
        shadowRequests = r.counter("routeplane_shadow_requests_total", "Shadow requests sent to the shadow upstream", "route", "target");
        shadowFailures = r.counter("routeplane_shadow_failures_total", "Shadow requests that failed (timeout, connect, error status, reset)", "route", "target", "kind");
        shadowDropped = r.counter("routeplane_shadow_dropped_total", "Sampled shadow requests dropped (queue_full, expired, shutdown)", "route", "target", "reason");
        shadowSkipped = r.counter("routeplane_shadow_skipped_total", "Requests not eligible for shadowing (method, body_too_large, body_not_replayable)", "route", "target", "reason");
        shadowComparisons = r.counter("routeplane_shadow_comparisons_total", "Primary-versus-shadow comparisons by result", "route", "target", "result");
        shadowDuration = r.histogram("routeplane_shadow_duration_seconds", "Shadow request duration until the shadow response was fully read", DURATION_BUCKETS, "route", "target");
        activeRequests = r.gauge("routeplane_active_requests", "Requests currently being handled");
        draining = r.gauge("routeplane_draining", "1 while the proxy is draining for shutdown");
    }

    public static int statusClassIndex(int status) {
        if (status >= 100 && status < 600) {
            return status / 100 - 1;
        }
        return 5;
    }

    public MetricsRegistry registry() {
        return registry;
    }
}
