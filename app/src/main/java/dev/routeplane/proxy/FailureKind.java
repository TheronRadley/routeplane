package dev.routeplane.proxy;

/**
 * Why a request did not get a normal upstream response. Every kind maps to one HTTP status and one stable
 * {@code code} that is exposed to clients ({@code X-Routeplane-Error}), in metrics ({@code kind} label) and in logs.
 *
 * <p>{@link #proxyGenerated()} separates failures that Routeplane itself produced (the response body and headers are
 * ours) from failures that merely mirror an upstream condition; benchmark reports use it to attribute errors.
 * {@link #upstreamCaused()} marks the subset that was caused by an upstream misbehaving, as opposed to protective
 * decisions of the proxy (breaker open, overload, concurrency limit).
 */
public enum FailureKind {
    NONE("none", 200, false, false),
    INVALID_REQUEST("invalid_request", 400, true, false),
    BODY_TOO_LARGE("body_too_large", 413, true, false),
    NO_ROUTE("no_route", 404, true, false),
    DRAINING("draining", 503, true, false),
    OVERLOAD("overload", 503, true, false),
    NO_UPSTREAM("no_upstream", 503, true, false),
    BREAKER_OPEN("breaker_open", 503, true, false),
    CONCURRENCY_LIMIT("concurrency_limit", 503, true, false),
    CONNECT_FAILED("connect_failed", 502, true, true),
    UPSTREAM_RESET("upstream_reset", 502, true, true),
    UPSTREAM_TIMEOUT("upstream_timeout", 504, true, true),
    DEADLINE_EXCEEDED("deadline_exceeded", 504, true, true),
    CLIENT_ABORT("client_abort", 499, false, false),
    INTERNAL("internal", 500, true, false);

    private final String code;
    private final int status;
    private final boolean proxyGenerated;
    private final boolean upstreamCaused;

    FailureKind(String code, int status, boolean proxyGenerated, boolean upstreamCaused) {
        this.code = code;
        this.status = status;
        this.proxyGenerated = proxyGenerated;
        this.upstreamCaused = upstreamCaused;
    }

    public String code() {
        return code;
    }

    public int status() {
        return status;
    }

    public boolean proxyGenerated() {
        return proxyGenerated;
    }

    public boolean upstreamCaused() {
        return upstreamCaused;
    }
}
