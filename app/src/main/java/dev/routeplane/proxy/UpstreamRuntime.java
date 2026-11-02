package dev.routeplane.proxy;

import dev.routeplane.config.RouteplaneConfig.ActiveHealth;
import dev.routeplane.config.RouteplaneConfig.BreakerConfig;
import dev.routeplane.config.RouteplaneConfig.HealthConfig;
import dev.routeplane.observability.LatencyWindow;
import dev.routeplane.observability.MetricsRegistry.Counter;
import dev.routeplane.observability.MetricsRegistry.Histogram;
import dev.routeplane.observability.StructuredLogger;
import dev.routeplane.resilience.CircuitBreaker;
import dev.routeplane.resilience.ConcurrencyLimiter;
import dev.routeplane.resilience.UpstreamHealth;
import dev.routeplane.util.TimeSource;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Everything stateful about one upstream of one route: its HTTP client and connection pool, concurrency limiter,
 * circuit breaker, health state and pre-bound metrics. Instances are shared between configuration versions when their
 * {@link Spec} is unchanged, so a reload does not reset breakers or drop pooled connections.
 */
final class UpstreamRuntime implements AutoCloseable {

    /** The settings that define the identity of the runtime; equal specs share state across reloads. */
    record Spec(URI url, int maxConcurrent, Duration connectTimeout, BreakerConfig breaker, HealthConfig health) {
    }

    final String routeId;
    final String id;
    final Spec spec;
    final UpstreamClient client;
    final ConcurrencyLimiter limiter;
    final CircuitBreaker breaker;
    final UpstreamHealth health;
    final LatencyWindow ttfbWindow;
    final Counter.Child firstAttempts;
    final Counter.Child retryAttempts;
    final Counter.Child[] responses = new Counter.Child[ProxyMetrics.STATUS_CLASSES.length];
    final Histogram.Child duration;
    final Counter.Child breakerRejections;
    final Counter.Child ejections;
    final Counter.Child probeOk;
    final Counter.Child probeFailed;

    private final StructuredLogger log;
    private volatile boolean closed;
    private Thread prober;

    UpstreamRuntime(String routeId, String id, Spec spec, ProxyMetrics m, TimeSource time, long latencyWindowMillis, StructuredLogger log) {
        this.routeId = routeId;
        this.id = id;
        this.spec = spec;
        this.log = log;
        this.client = new UpstreamClient(spec.url(), spec.connectTimeout());
        this.limiter = new ConcurrencyLimiter(spec.maxConcurrent());
        this.health = new UpstreamHealth(spec.health(), time);
        this.ttfbWindow = new LatencyWindow(time, latencyWindowMillis, 6);
        this.firstAttempts = m.upstreamRequests.labels(routeId, id, "first");
        this.retryAttempts = m.upstreamRequests.labels(routeId, id, "retry");
        for (int i = 0; i < responses.length; i++) {
            responses[i] = m.upstreamResponses.labels(routeId, id, ProxyMetrics.STATUS_CLASSES[i]);
        }
        this.duration = m.upstreamDuration.labels(routeId, id);
        this.breakerRejections = m.breakerRejections.labels(routeId, id);
        this.ejections = m.ejections.labels(routeId, id);
        this.probeOk = m.healthChecks.labels(routeId, id, "ok");
        this.probeFailed = m.healthChecks.labels(routeId, id, "failed");
        if (spec.breaker() == null) {
            this.breaker = null;
        } else {
            this.breaker = new CircuitBreaker(routeId + "/" + id, spec.breaker(), time, (name, t) -> {
                m.breakerTransitions.labels(routeId, id, t.from().name().toLowerCase(), t.to().name().toLowerCase()).inc();
                log.warn("circuit_breaker_transition", "route", routeId, "upstream", id, "from", t.from().name(), "to", t.to().name(), "reason", t.reason());
            });
        }
    }

    String key() {
        return routeId + "/" + id;
    }

    /** Healthy, breaker willing to admit a call, and below its concurrency limit (a non-consuming check). */
    boolean eligible() {
        return health.isAvailable() && (breaker == null || breaker.wouldPermit()) && limiter.inFlight() < limiter.limit();
    }

    /** Starts the periodic active health probe if one is configured. */
    void startActiveChecks() {
        ActiveHealth a = spec.health() == null ? null : spec.health().active();
        if (a == null || prober != null) {
            return;
        }
        prober = Thread.ofVirtual().name("health-" + key()).start(() -> {
            try {
                Thread.sleep(ThreadLocalRandom.current().nextLong(Math.max(1, a.interval().toMillis() / 2)));
                while (!closed) {
                    boolean ok;
                    try {
                        ok = a.expectedStatuses().get(client.probe(a.path(), a.timeout()));
                    } catch (UpstreamClient.UpstreamException e) {
                        ok = false;
                    }
                    (ok ? probeOk : probeFailed).inc();
                    if (health.recordProbe(ok)) {
                        log.warn("upstream_health_changed", "route", routeId, "upstream", id, "state", ok ? "up" : "down", "source", "active");
                    }
                    Thread.sleep(a.interval().toMillis());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    @Override
    public void close() {
        closed = true;
        if (prober != null) {
            prober.interrupt();
        }
        client.close();
    }
}
