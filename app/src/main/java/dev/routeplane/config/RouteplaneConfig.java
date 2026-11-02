package dev.routeplane.config;

import dev.routeplane.observability.StructuredLogger;
import dev.routeplane.util.Cidr;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Immutable, validated configuration. Instances are only created by {@link ConfigLoader}, which guarantees every
 * invariant documented in docs/configuration.md. Secrets (admin token, key-store password) are masked in
 * {@code toString()} so that accidental logging cannot leak them.
 */
public record RouteplaneConfig(
        ServerConfig server,
        AdminConfig admin,
        LoggingConfig logging,
        MetricsConfig metrics,
        List<RouteConfig> routes) {

    /** HTTP methods Routeplane accepts at all. Everything else is rejected before routing. */
    public static final Set<String> SUPPORTED_METHODS = Set.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");

    public enum ForwardingMode { OVERWRITE, TRUSTED_APPEND, OFF }

    public enum HostHeaderMode { UPSTREAM, PRESERVE }

    public enum JitterMode { FULL, EQUAL, NONE }

    public enum WindowType { COUNT, TIME }

    public enum SamplingMode { RANDOM, DETERMINISTIC }

    public record ServerConfig(
            String host,
            int port,
            int backlog,
            int maxConnections,
            int maxConcurrentRequests,
            int maxHeaderBytes,
            long maxRequestBodyBytes,
            int maxBufferedBodyBytes,
            Duration requestHeaderTimeout,
            Duration idleTimeout,
            Duration maxResponseTime,
            Duration shutdownGracePeriod,
            String requestIdHeader,
            ForwardingConfig forwarding,
            HostHeaderMode hostHeader,
            boolean allowEncodedSlashes,
            boolean watchConfig,
            Duration watchInterval) {
    }

    public record ForwardingConfig(ForwardingMode mode, List<Cidr> trustedProxies, String proto, boolean emitForwardedHeader) {
    }

    public record AdminConfig(
            boolean enabled,
            String host,
            int port,
            String bearerToken,
            TlsConfig tls,
            List<String> allowedHosts) {
        @Override
        public String toString() {
            return "AdminConfig[enabled=" + enabled + ", host=" + host + ", port=" + port
                    + ", bearerToken=" + (bearerToken == null ? "<none>" : "<redacted>") + ", tls=" + tls
                    + ", allowedHosts=" + allowedHosts + "]";
        }
    }

    public record TlsConfig(Path keyStore, String keyStoreType, String keyStorePassword) {
        @Override
        public String toString() {
            return "TlsConfig[keyStore=" + keyStore + ", keyStoreType=" + keyStoreType + ", keyStorePassword=<redacted>]";
        }
    }

    public record LoggingConfig(
            StructuredLogger.Level level,
            boolean accessLogEnabled,
            double accessLogSampleRate,
            boolean includeQuery,
            boolean includeClientIp,
            int queueCapacity) {
    }

    public record MetricsConfig(Duration latencyWindow, int maxSeriesPerFamily) {
    }

    // ------------------------------------------------------------------------------------------------- routes

    public record RouteConfig(
            String id,
            int priority,
            MatchConfig match,
            boolean stripPrefix,
            List<UpstreamConfig> upstreams,
            PolicyConfig policy,
            FallbackConfig fallback,
            TimeoutConfig timeouts,
            RetryConfig retry,
            BreakerConfig circuitBreaker,
            HealthConfig health,
            ShadowConfig shadow) {
    }

    /** At most one of {@code path} (exact) and {@code pathPrefix} is set; both null means "any path". */
    public record MatchConfig(
            String path,
            String pathPrefix,
            Set<String> methods,
            List<ValueMatch> headers,
            List<ValueMatch> query) {
    }

    /** Exactly one of {@code equals}, {@code prefix}, {@code present} is meaningful. */
    public record ValueMatch(String name, String equals, String prefix, boolean present) {
    }

    public record UpstreamConfig(String id, URI url, int weight, int maxConcurrent) {
    }

    public record PolicyConfig(String type, Map<String, String> options) {
    }

    public record FallbackConfig(List<UpstreamConfig> upstreams, StaticResponse response) {
    }

    public record StaticResponse(int status, String body, String contentType) {
    }

    public record TimeoutConfig(Duration connect, Duration response, Duration request) {
    }

    public record RetryConfig(
            int maxAttempts,
            Set<String> methods,
            Set<Integer> retryStatuses,
            boolean retryOnTimeout,
            Duration backoffInitial,
            Duration backoffMax,
            double backoffMultiplier,
            JitterMode jitter,
            double budgetRatio,
            double budgetMinRetriesPerSecond,
            Duration budgetWindow) {
        public boolean enabled() {
            return maxAttempts > 1;
        }
    }

    public record BreakerConfig(
            double failureRateThreshold,
            int minimumRequests,
            WindowType windowType,
            int windowCount,
            Duration windowDuration,
            Duration openDuration,
            int halfOpenMaxProbes,
            int recoverySuccessThreshold,
            BitSet failureStatuses,
            boolean countTimeoutsAsFailures) {
    }

    public record HealthConfig(ActiveHealth active, PassiveHealth passive) {
    }

    public record ActiveHealth(
            String path,
            Duration interval,
            Duration timeout,
            int unhealthyThreshold,
            int healthyThreshold,
            BitSet expectedStatuses) {
    }

    public record PassiveHealth(int consecutiveFailures, Duration ejectionTime) {
    }

    public record ShadowConfig(
            List<ShadowTarget> targets,
            Set<String> methods,
            boolean allowUnsafeMethods,
            int maxBodyBytes,
            int maxResponseBytes,
            Set<String> stripHeaders,
            Set<String> allowHeaders,
            Map<String, String> setHeaders,
            CompareConfig compare) {
    }

    public record ShadowTarget(
            String id,
            URI url,
            double samplePercent,
            SamplingMode sampling,
            String sampleKey,
            Duration connectTimeout,
            Duration timeout,
            int maxConcurrent,
            int queueCapacity,
            Duration queueMaxWait) {
    }

    public record CompareConfig(boolean enabled, boolean compareBody, Set<String> headers) {
    }
}
