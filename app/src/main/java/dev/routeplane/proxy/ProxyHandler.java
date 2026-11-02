package dev.routeplane.proxy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import dev.routeplane.config.RouteplaneConfig.LoggingConfig;
import dev.routeplane.config.RouteplaneConfig.RetryConfig;
import dev.routeplane.config.RouteplaneConfig.ServerConfig;
import dev.routeplane.observability.StructuredLogger;
import dev.routeplane.resilience.CircuitBreaker;
import dev.routeplane.util.TimeSource;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.SequenceInputStream;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * The data plane. For each exchange (one virtual thread each): validate, match a route, prepare the body, then loop
 * over upstream attempts - select an upstream (health, circuit breaker, concurrency limit, routing policy), send,
 * classify the outcome, optionally retry within budget and deadline - and finally stream the response to the client.
 *
 * <p>Invariants worth knowing when changing this class:
 * <ul>
 *   <li>Once response headers have been sent to the client nothing is retried and a failure can only abort the
 *       connection (a truncated response must never look complete).</li>
 *   <li>Every acquired concurrency permit and breaker permit is released exactly once ({@link Attempt}).</li>
 *   <li>Client-side failures (disconnect, slow upload, oversized body) are never blamed on the upstream: they do not
 *       count against breakers or health.</li>
 *   <li>Shadow work is only ever <em>offered</em> to {@link ShadowDispatcher}, which cannot block or throw.</li>
 * </ul>
 */
final class ProxyHandler implements HttpHandler {
    private static final String NO_ROUTE_LABEL = "-";

    private final Supplier<RuntimeState> state;
    private final ServerConfig server;
    private final LoggingConfig logging;
    private final ProxyMetrics metrics;
    private final TimeSource time;
    private final StructuredLogger log;
    private final RequestValidator validator;
    private final HeaderPolicy headers;
    private final AtomicInteger inFlight = new AtomicInteger();
    private volatile boolean draining;

    ProxyHandler(Supplier<RuntimeState> state, ServerConfig server, LoggingConfig logging, ProxyMetrics metrics, TimeSource time,
                 StructuredLogger log, String viaToken) {
        this.state = state;
        this.server = server;
        this.logging = logging;
        this.metrics = metrics;
        this.time = time;
        this.log = log;
        this.validator = new RequestValidator(server, viaToken);
        this.headers = new HeaderPolicy(server, viaToken);
    }

    void startDraining() {
        draining = true;
        metrics.draining.labels().set(1);
    }

    int inFlight() {
        return inFlight.get();
    }

    boolean isDraining() {
        return draining;
    }

    // ---------------------------------------------------------------------------------------------- scope

    /** Mutable per-request record used for metrics and the access log. */
    private static final class Scope {
        final long start;
        String requestId;
        ProxyRequest req;
        boolean bodyHint;
        String method = "-";
        String path = "-";
        String query;
        String client;
        RouteRuntime route;
        UpstreamRuntime upstream;
        int attempts;
        FailureKind failure = FailureKind.NONE;
        String detail;
        boolean connectTimeout;
        int status;
        long bytesOut;
        long bytesIn;
        boolean responseStarted;
        long ttfbNanos = -1;
        boolean viaFallback;

        Scope(long start) {
            this.start = start;
        }

        void fail(FailureKind kind, String why) {
            this.failure = kind;
            this.detail = why;
        }
    }

    // ------------------------------------------------------------------------------------------- entry point

    @Override
    public void handle(HttpExchange ex) {
        Scope sc = new Scope(time.nanoTime());
        inFlight.incrementAndGet();
        metrics.activeRequests.labels().inc();
        try {
            process(ex, sc);
        } catch (Throwable t) {
            if (t instanceof Error && !(t instanceof StackOverflowError)) {
                // OutOfMemoryError and friends: record and rethrow after cleanup
                log.error("fatal_error_in_handler", t, "request_id", sc.requestId);
            }
            sc.fail(FailureKind.INTERNAL, t.getClass().getSimpleName());
            log.error("unhandled_exception", t, "request_id", sc.requestId);
            if (sc.responseStarted) {
                abort(ex, sc);
            } else {
                sendError(ex, sc, FailureKind.INTERNAL, 500, "internal error", true, null);
            }
        } finally {
            inFlight.decrementAndGet();
            metrics.activeRequests.labels().dec();
            completeExchange(ex, sc);
            finish(sc);
        }
    }

    /** How long the JDK may spend discarding an unread request body when an exchange completes (lingering close). */
    private static final long LINGER_NANOS = java.util.concurrent.TimeUnit.SECONDS.toNanos(1);

    /**
     * Completes the exchange. Normally that is a plain close. If the client's body was not read completely, the JDK
     * server would drain it with a blocking read that a stalled client could hold open indefinitely; so the drain is
     * allowed (cooperative clients get a clean FIN and reliably see the response) but only for {@link #LINGER_NANOS},
     * after which the connection is cut. Idempotent.
     */
    private void completeExchange(HttpExchange ex, Scope sc) {
        boolean unread = sc.req != null ? sc.req.bodyUnread() : sc.bodyHint;
        if (!unread) {
            try {
                ex.close();
            } catch (RuntimeException ignored) {
                // already closed
            }
            return;
        }
        Thread.interrupted(); // a stale interrupt must not pre-empt the bounded drain
        GuardedInputStream guard = sc.req == null ? null : sc.req.bodyGuard;
        try (Deadlines.Watch linger = Deadlines.watch(Thread.currentThread(), LINGER_NANOS, guard == null ? null : guard::interruptReader)) {
            ex.close();
        } catch (RuntimeException ignored) {
            // connection torn down by the linger interrupt
        }
    }

    private void process(HttpExchange ex, Scope sc) throws IOException {
        sc.method = ex.getRequestMethod();
        var hdrs = ex.getRequestHeaders();
        sc.bodyHint = hdrs.containsKey("Content-Length") || hdrs.containsKey("Transfer-Encoding");
        if (draining) {
            sendError(ex, sc, FailureKind.DRAINING, 503, "the proxy is shutting down", true, "1");
            return;
        }
        if (inFlight.get() > server.maxConcurrentRequests()) {
            sendError(ex, sc, FailureKind.OVERLOAD, 503, "too many concurrent requests", false, "1");
            return;
        }
        RequestValidator.Result v = validator.validate(ex, sc.start);
        if (!v.ok()) {
            RequestValidator.Rejection r = v.rejection();
            sendError(ex, sc, r.kind(), r.status(), r.message(), r.closeConnection(), null);
            return;
        }
        ProxyRequest req = v.request();
        sc.req = req;
        sc.requestId = req.requestId;
        sc.method = req.method;
        sc.path = req.path;
        sc.query = req.rawQuery;
        sc.client = req.clientAddress;
        Optional<RouteRuntime> match = state.get().match(req);
        if (match.isEmpty()) {
            sendError(req, sc, FailureKind.NO_ROUTE, 404, "no route matches this request", false, null);
            return;
        }
        RouteRuntime route = match.get();
        sc.route = route;
        long budget = route.requestTimeout.toNanos() - (time.nanoTime() - req.startNanos);
        try (Deadlines.Watch watch = Deadlines.watch(Thread.currentThread(), budget)) {
            forward(req, route, sc, watch);
            // Complete inside the watch: whatever the JDK does on close (e.g. draining) is bounded by the deadline as well.
            if (watch.fired()) {
                abort(ex, sc);
            } else {
                completeExchange(ex, sc);
            }
        }
    }

    // --------------------------------------------------------------------------------------------- forwarding

    private void forward(ProxyRequest req, RouteRuntime route, Scope sc, Deadlines.Watch watch) throws IOException {
        long deadlineNanos = req.startNanos + route.requestTimeout.toNanos();
        GuardedInputStream[] guardHolder = new GuardedInputStream[1];
        BodySource body;
        try {
            body = prepareBody(req, route, sc, guardHolder);
        } catch (GuardedInputStream.BodyTooLargeException e) {
            sendError(req, sc, FailureKind.BODY_TOO_LARGE, 413, "request body exceeds " + server.maxRequestBodyBytes() + " bytes", true, null);
            return;
        } catch (IOException e) {
            boolean deadline = watch.fired();
            if (deadline) {
                sendError(req, sc, FailureKind.DEADLINE_EXCEEDED, 504, "deadline exceeded while receiving the request body", true, null);
            } else {
                sc.fail(FailureKind.CLIENT_ABORT, "client failed while sending the body");
                abort(req.exchange, sc);
            }
            return;
        }
        GuardedInputStream guard = guardHolder[0];
        List<String[]> upstreamHeaders = headers.upstreamRequestHeaders(req);
        ShadowDispatcher.PrimaryCapture capture = route.shadow == null ? null : route.shadow.offer(req, body, upstreamHeaders);
        RetryConfig retry = route.config.retry();
        boolean retryable = route.retryEnabledFor(req.method);
        if (retryable) {
            route.retryBudget.recordRequest();
        }
        Set<UpstreamRuntime> tried = new HashSet<>(4);
        FailureKind lastFailure = null;
        String lastDetail = null;
        int attemptNo = 0;
        try {
            while (true) {
                attemptNo++;
                sc.attempts = attemptNo;
                Selection sel = select(req, route, tried);
                if (sel == null) {
                    FailureKind kind = lastFailure != null ? lastFailure : selectionFailure(route);
                    if (lastFailure == null && fallbackResponse(req, route, sc)) {
                        return;
                    }
                    respondFailure(req, sc, kind, lastDetail);
                    return;
                }
                Attempt at = new Attempt(sel.upstream, sel.permit);
                tried.add(sel.upstream);
                sc.upstream = sel.upstream;
                sc.viaFallback = sel.fallback;
                if (sel.fallback) {
                    route.fallback("upstream").inc();
                }
                try {
                    long remaining = deadlineNanos - time.nanoTime();
                    if (remaining <= 0) {
                        at.ignore();
                        respondFailure(req, sc, FailureKind.DEADLINE_EXCEEDED, "deadline exceeded before the attempt started");
                        return;
                    }
                    (attemptNo == 1 ? sel.upstream.firstAttempts : sel.upstream.retryAttempts).inc();
                    UpstreamClient.Response response;
                    FailureKind failure = null;
                    String detail = null;
                    try {
                        response = sel.upstream.client.send(req.method, upstreamPath(route, req), upstreamHeaders, body,
                                Duration.ofNanos(Math.min(route.responseTimeout.toNanos(), remaining)));
                    } catch (UpstreamClient.UpstreamException e) {
                        response = null;
                        failure = e.kind;
                        detail = e.getMessage();
                        if (e.connectTimeout) {
                            sc.connectTimeout = true;
                        }
                        if (guard != null && guard.limitExceeded()) {
                            at.ignore();
                            sendError(req, sc, FailureKind.BODY_TOO_LARGE, 413, "request body exceeds " + server.maxRequestBodyBytes() + " bytes", true, null);
                            return;
                        }
                        if (guard != null && guard.clientFailure() != null) {
                            at.ignore();
                            sc.fail(FailureKind.CLIENT_ABORT, "client failed while sending the body");
                            abort(req.exchange, sc);
                            return;
                        }
                    } catch (InterruptedException e) {
                        response = null;
                        if (watch.fired()) {
                            failure = FailureKind.DEADLINE_EXCEEDED;
                            detail = "deadline exceeded";
                        } else {
                            at.ignore();
                            Thread.currentThread().interrupt();
                            sc.fail(FailureKind.INTERNAL, "interrupted");
                            sendError(req, sc, FailureKind.INTERNAL, 503, "interrupted during shutdown", true, "1");
                            return;
                        }
                    }
                    if (response != null) {
                        sc.ttfbNanos = response.headersNanos();
                        sel.upstream.duration.observeNanos(response.headersNanos());
                        sel.upstream.ttfbWindow.recordNanos(response.headersNanos());
                        sel.upstream.responses[ProxyMetrics.statusClassIndex(response.status())].inc();
                        boolean failureStatus = sel.upstream.breaker != null
                                ? sel.upstream.breaker.isFailureStatus(response.status())
                                : response.status() >= 500 && response.status() <= 599;
                        if (failureStatus) {
                            at.failure();
                        }
                        if (retry.retryStatuses().contains(response.status())
                                && mayRetry(route, req, body, attemptNo, "status", deadlineNanos)) {
                            lastFailure = FailureKind.UPSTREAM_RESET;
                            lastDetail = "upstream status " + response.status();
                            drainQuietly(response);
                            sleepBackoff(route, attemptNo);
                            continue;
                        }
                        relay(req, route, at, response, sc, watch, capture);
                        capture = null; // completed by relay
                        return;
                    }
                    // no response: classify and maybe retry
                    switch (failure) {
                        case DEADLINE_EXCEEDED -> at.failure();
                        case UPSTREAM_TIMEOUT -> {
                            if (sel.upstream.breaker == null || sel.upstream.breaker.countTimeoutsAsFailures()) {
                                at.failure();
                            } else {
                                at.ignore();
                            }
                        }
                        case INVALID_REQUEST -> at.ignore();
                        default -> at.failure();
                    }
                    lastFailure = failure;
                    lastDetail = detail;
                    String reason = switch (failure) {
                        case CONNECT_FAILED -> "connect_failed";
                        case UPSTREAM_TIMEOUT -> retry.retryOnTimeout() ? "timeout" : null;
                        case UPSTREAM_RESET -> "reset";
                        default -> null;
                    };
                    if (reason != null && mayRetry(route, req, body, attemptNo, reason, deadlineNanos)) {
                        sleepBackoff(route, attemptNo);
                        continue;
                    }
                    respondFailure(req, sc, failure, detail);
                    return;
                } finally {
                    at.release();
                }
            }
        } finally {
            if (capture != null) {
                capture.complete(false);
            }
        }
    }

    private String upstreamPath(RouteRuntime route, ProxyRequest req) {
        String path = route.config.stripPrefix() ? route.matcher.stripPrefix(req.path) : req.path;
        return req.rawQuery == null ? path : path + "?" + req.rawQuery;
    }

    /** Discards a response that is being retried away. Small bodies are read to the end so the connection can be reused. */
    private void drainQuietly(UpstreamClient.Response r) {
        try {
            InputStream in = r.body();
            byte[] buf = new byte[4096];
            int total = 0;
            int n;
            while (total < 16 * 1024) {
                n = in.read(buf);
                if (n < 0) {
                    return; // fully consumed: nothing to cancel
                }
                total += n;
            }
            r.close(); // too large to bother: cancel and let the connection go
        } catch (IOException e) {
            r.close();
        }
    }

    // ----------------------------------------------------------------------------------------- body handling

    private BodySource prepareBody(ProxyRequest req, RouteRuntime route, Scope sc, GuardedInputStream[] guardOut) throws IOException {
        if (!req.hasBody()) {
            return new BodySource.Empty();
        }
        GuardedInputStream guard = new GuardedInputStream(req.exchange.getRequestBody(), server.maxRequestBodyBytes(), req.contentLength);
        guardOut[0] = guard;
        req.bodyGuard = guard;
        int limit = server.maxBufferedBodyBytes();
        boolean replay = route.needsReplayableBody(req.method, req.contentLength) && (req.contentLength < 0 || req.contentLength <= limit);
        if (!replay) {
            return new BodySource.Streaming(new CountingStream(guard, sc), req.contentLength);
        }
        ByteArrayOutputStream buf = new ByteArrayOutputStream(req.contentLength > 0 ? (int) req.contentLength : 1024);
        long want = req.contentLength > 0 ? req.contentLength : (long) limit + 1;
        byte[] chunk = new byte[8192];
        long total = 0;
        while (total < want) {
            int n = guard.read(chunk, 0, (int) Math.min(chunk.length, want - total));
            if (n < 0) {
                break;
            }
            buf.write(chunk, 0, n);
            total += n;
        }
        sc.bytesIn = total;
        if (req.contentLength > 0 || total <= limit) {
            if (req.contentLength > 0 && total != req.contentLength) {
                throw new IOException("client closed before sending the whole body");
            }
            return new BodySource.Buffered(buf.toByteArray());
        }
        // chunked body larger than the buffering limit: continue as a stream, no longer replayable
        InputStream rest = new SequenceInputStream(new ByteArrayInputStream(buf.toByteArray()), new CountingStream(guard, sc));
        return new BodySource.Streaming(rest, -1);
    }

    /** Counts bytes that flow to the upstream, for logs and metrics. */
    private static final class CountingStream extends InputStream {
        private final InputStream in;
        private final Scope sc;

        CountingStream(InputStream in, Scope sc) {
            this.in = in;
            this.sc = sc;
        }

        @Override
        public int read() throws IOException {
            int b = in.read();
            if (b >= 0) {
                sc.bytesIn++;
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = in.read(b, off, len);
            if (n > 0) {
                sc.bytesIn += n;
            }
            return n;
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }

    // ---------------------------------------------------------------------------------------------- selection

    private record Selection(UpstreamRuntime upstream, CircuitBreaker.Permit permit, boolean fallback) {
    }

    /** Owns the permits of one attempt; every release/outcome call is idempotent. */
    private final class Attempt {
        private final UpstreamRuntime up;
        private final CircuitBreaker.Permit permit;
        private boolean resolved;
        private boolean released;

        Attempt(UpstreamRuntime up, CircuitBreaker.Permit permit) {
            this.up = up;
            this.permit = permit;
        }

        void success() {
            if (resolved) {
                return;
            }
            resolved = true;
            if (permit != null) {
                up.breaker.onSuccess(permit);
            }
            up.health.recordRequestSuccess();
        }

        void failure() {
            if (resolved) {
                return;
            }
            resolved = true;
            if (permit != null) {
                up.breaker.onFailure(permit);
            }
            if (up.health.recordRequestFailure()) {
                up.ejections.inc();
                log.warn("upstream_ejected", "route", up.routeId, "upstream", up.id);
            }
        }

        void ignore() {
            if (resolved) {
                return;
            }
            resolved = true;
            if (permit != null) {
                up.breaker.onIgnored(permit);
            }
        }

        void release() {
            ignore(); // a permit that never got an outcome must not stay allocated
            if (!released) {
                released = true;
                up.limiter.release();
            }
        }
    }

    /**
     * Picks an upstream: eligible primaries first (healthy, breaker willing, below the concurrency limit), then
     * fallbacks. Upstreams already tried by this request are avoided when an alternative exists. The routing policy
     * chooses among the eligible; the limiter and breaker permits are then taken and, if either refuses, the upstream
     * is dropped from the candidate list and the policy is asked again.
     */
    private Selection select(ProxyRequest req, RouteRuntime route, Set<UpstreamRuntime> tried) {
        for (int round = 0; round < 2; round++) {
            List<UpstreamBinding> pool = round == 0 ? route.primaries : route.fallbacks;
            if (pool.isEmpty()) {
                continue;
            }
            List<UpstreamBinding> eligible = new ArrayList<>(pool.size());
            for (UpstreamBinding b : pool) {
                UpstreamRuntime u = b.runtime;
                if (u.breaker != null && !u.breaker.wouldPermit() && u.health.isAvailable()) {
                    u.breakerRejections.inc();
                }
                if (u.eligible()) {
                    eligible.add(b);
                }
            }
            if (!tried.isEmpty()) {
                List<UpstreamBinding> fresh = new ArrayList<>(eligible.size());
                for (UpstreamBinding b : eligible) {
                    if (!tried.contains(b.runtime)) {
                        fresh.add(b);
                    }
                }
                if (!fresh.isEmpty()) {
                    eligible = fresh;
                }
            }
            while (!eligible.isEmpty()) {
                UpstreamBinding b = route.choose(req, eligible);
                UpstreamRuntime u = b.runtime;
                if (!u.limiter.tryAcquire()) {
                    eligible = without(eligible, b);
                    continue;
                }
                CircuitBreaker.Permit permit = null;
                if (u.breaker != null) {
                    permit = u.breaker.tryAcquire();
                    if (!permit.allowed()) {
                        u.limiter.release();
                        u.breakerRejections.inc();
                        eligible = without(eligible, b);
                        continue;
                    }
                }
                return new Selection(u, permit, round == 1);
            }
        }
        return null;
    }

    private static List<UpstreamBinding> without(List<UpstreamBinding> list, UpstreamBinding b) {
        List<UpstreamBinding> out = new ArrayList<>(list);
        out.remove(b);
        return out;
    }

    /** Why nothing could be selected: breaker refusal, concurrency limit, or no healthy upstream at all. */
    private FailureKind selectionFailure(RouteRuntime route) {
        boolean breaker = false;
        boolean limited = false;
        List<UpstreamBinding> all = new ArrayList<>(route.primaries);
        all.addAll(route.fallbacks);
        for (UpstreamBinding b : all) {
            UpstreamRuntime u = b.runtime;
            if (!u.health.isAvailable()) {
                continue;
            }
            if (u.breaker != null && !u.breaker.wouldPermit()) {
                breaker = true;
            } else if (u.limiter.inFlight() >= u.limiter.limit()) {
                limited = true;
            }
        }
        return breaker ? FailureKind.BREAKER_OPEN : limited ? FailureKind.CONCURRENCY_LIMIT : FailureKind.NO_UPSTREAM;
    }

    /** Serves the route's static fallback response when no upstream could be selected for the first attempt. */
    private boolean fallbackResponse(ProxyRequest req, RouteRuntime route, Scope sc) throws IOException {
        var fb = route.config.fallback();
        if (fb == null || fb.response() == null) {
            return false;
        }
        var r = fb.response();
        route.fallback("static").inc();
        byte[] bytes = r.body().getBytes(StandardCharsets.UTF_8);
        var h = req.exchange.getResponseHeaders();
        h.set("Content-Type", r.contentType());
        h.set("X-Routeplane-Fallback", "static");
        h.set(server.requestIdHeader(), req.requestId);
        sc.status = r.status();
        sc.responseStarted = true;
        sc.fail(FailureKind.NONE, null);
        if (req.head) {
            h.set("Content-Length", Integer.toString(bytes.length));
            req.exchange.sendResponseHeaders(r.status(), -1);
            return true;
        }
        req.exchange.sendResponseHeaders(r.status(), bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            OutputStream os = req.exchange.getResponseBody();
            os.write(bytes);
            os.flush();
        }
        sc.bytesOut = bytes.length;
        return true;
    }

    // -------------------------------------------------------------------------------------------------- retry

    /**
     * Decides whether another attempt is allowed. The checks run cheapest first and the retry budget is consumed last,
     * so a retry that is refused for any other reason does not eat budget.
     */
    private boolean mayRetry(RouteRuntime route, ProxyRequest req, BodySource body, int attemptNo, String reason, long deadlineNanos) {
        RetryConfig rc = route.config.retry();
        if (!rc.enabled()) {
            return false;
        }
        if (attemptNo >= rc.maxAttempts()) {
            route.retrySkipped("attempts_exhausted").inc();
            return false;
        }
        if (!rc.methods().contains(req.method)) {
            route.retrySkipped("method_not_retryable").inc();
            return false;
        }
        if (!body.replayable()) {
            route.retrySkipped("body_not_replayable").inc();
            return false;
        }
        Duration delay = route.backoff.delayBeforeRetry(attemptNo);
        if (deadlineNanos - time.nanoTime() <= delay.toNanos() + 1_000_000L) {
            route.retrySkipped("deadline").inc();
            return false;
        }
        if (!route.retryBudget.tryAcquireRetry()) {
            route.budgetExhausted.inc();
            route.retrySkipped("budget_exhausted").inc();
            return false;
        }
        route.retried(reason).inc();
        return true;
    }

    private void sleepBackoff(RouteRuntime route, int attemptNo) throws IOException {
        Duration d = route.backoff.delayBeforeRetry(attemptNo);
        if (d.isZero()) {
            return;
        }
        try {
            Thread.sleep(d.toMillis(), d.toNanosPart() % 1_000_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while backing off", e);
        }
    }

    // ------------------------------------------------------------------------------------------------- relay

    private void relay(ProxyRequest req, RouteRuntime route, Attempt at, UpstreamClient.Response up, Scope sc, Deadlines.Watch watch,
                       ShadowDispatcher.PrimaryCapture capture) {
        HttpExchange ex = req.exchange;
        int status = up.status();
        sc.status = status;
        long len = up.headers().firstValue("content-length").map(ProxyHandler::parseLong).orElse(-1L);
        boolean noBody = req.head || status == 204 || status == 304 || (status >= 100 && status < 200);
        headers.copyResponseHeaders(up.headers(), ex.getResponseHeaders(), req);
        if (capture != null) {
            capture.onHeaders(status, up.headers());
        }
        boolean ok = false;
        try {
            sc.responseStarted = true;
            try {
                if (noBody) {
                    if (len >= 0 && (req.head || status == 304)) {
                        ex.getResponseHeaders().set("Content-Length", Long.toString(len));
                    }
                    ex.sendResponseHeaders(status, -1);
                    up.finish();
                    at.success();
                    ok = true;
                    return;
                }
                ex.sendResponseHeaders(status, len < 0 ? 0 : (len == 0 ? -1 : len));
            } catch (IOException e) {
                clientGone(sc, at, up, ex);
                return;
            }
            byte[] buf = new byte[16 * 1024];
            long total = 0;
            boolean flushEach = len < 0;
            OutputStream out = ex.getResponseBody();
            InputStream in = up.body();
            while (true) {
                int n;
                try {
                    n = in.read(buf);
                } catch (IOException e) {
                    up.close();
                    if (watch.fired()) {
                        sc.fail(FailureKind.DEADLINE_EXCEEDED, "deadline exceeded while streaming the response");
                    } else {
                        sc.fail(FailureKind.UPSTREAM_RESET, "upstream failed mid-response");
                    }
                    at.failure();
                    abort(ex, sc); // closes without the terminating chunk so the client can see the response is incomplete
                    return;
                }
                if (n < 0) {
                    break;
                }
                try {
                    out.write(buf, 0, n);
                    if (flushEach) {
                        out.flush();
                    }
                } catch (IOException e) {
                    if (watch.fired()) {
                        sc.fail(FailureKind.DEADLINE_EXCEEDED, "deadline exceeded while writing the response");
                        up.close();
                        at.failure();
                        abort(ex, sc);
                    } else {
                        clientGone(sc, at, up, ex);
                    }
                    return;
                }
                total += n;
                sc.bytesOut = total;
                if (capture != null) {
                    capture.onBody(buf, 0, n);
                }
            }
            // end of stream: the exchange is complete and the connection is back in the pool; do not cancel it
            try {
                if (len >= 0 && req.bodyUnread()) {
                    out.flush(); // complete response is on the wire; completeExchange() then performs the bounded lingering close
                } else {
                    out.close(); // chunked: writes the terminating chunk (any drain is bounded by the still-active deadline watch)
                }
            } catch (IOException e) {
                sc.fail(FailureKind.CLIENT_ABORT, "client went away before the response completed");
                at.ignore();
                return;
            }
            at.success();
            ok = true;
        } finally {
            at.ignore();
            if (capture != null) {
                capture.complete(ok && sc.failure == FailureKind.NONE);
            }
        }
    }

    private void clientGone(Scope sc, Attempt at, UpstreamClient.Response up, HttpExchange ex) {
        sc.fail(FailureKind.CLIENT_ABORT, "client disconnected");
        up.close(); // cancels the upstream exchange
        at.ignore();
        abort(ex, sc);
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    /**
     * Terminates the client connection without completing the response. The JDK server offers no abort call and
     * {@code close()} would send the final chunk of a chunked body, making a truncated response look complete; so the
     * thread is interrupted first, which makes the channel write inside {@code close()} fail and closes the connection.
     */
    private static void abort(HttpExchange ex, Scope sc) {
        if (sc.req != null && sc.req.bodyGuard != null) {
            sc.req.bodyGuard.interruptReader(); // may hold the channel's read lock (see GuardedInputStream)
        }
        Thread.currentThread().interrupt();
        try {
            ex.close();
        } catch (RuntimeException ignored) {
            // connection already gone
        }
        Thread.interrupted();
    }

    // -------------------------------------------------------------------------------------------------- errors

    private void respondFailure(ProxyRequest req, Scope sc, FailureKind kind, String detail) throws IOException {
        String retryAfter = switch (kind) {
            case NO_UPSTREAM, BREAKER_OPEN, CONCURRENCY_LIMIT, OVERLOAD -> "1";
            default -> null;
        };
        String msg = switch (kind) {
            case NO_UPSTREAM -> "no healthy upstream is available for this route";
            case BREAKER_OPEN -> "the circuit breaker is open for every upstream of this route";
            case CONCURRENCY_LIMIT -> "every upstream of this route is at its concurrency limit";
            case CONNECT_FAILED -> "could not connect to the upstream";
            case UPSTREAM_RESET -> "the upstream closed the connection or returned an invalid response";
            case UPSTREAM_TIMEOUT -> "the upstream did not respond in time";
            case DEADLINE_EXCEEDED -> "the request deadline was exceeded";
            default -> "request failed";
        };
        if (kind == FailureKind.INVALID_REQUEST) {
            sendError(req, sc, kind, 400, "the request cannot be forwarded: " + detail, true, null);
            return;
        }
        sc.detail = detail;
        sendError(req, sc, kind, kind.status(), msg, false, retryAfter);
    }

    private void sendError(ProxyRequest req, Scope sc, FailureKind kind, int status, String message, boolean close, String retryAfter) {
        sc.req = req;
        sendError(req.exchange, sc, kind, status, message, close, retryAfter);
    }

    private void sendError(HttpExchange ex, Scope sc, FailureKind kind, int status, String message, boolean close, String retryAfter) {
        Thread.interrupted(); // a deadline interrupt that was not consumed must not break writing the error response
        if (sc.requestId == null) {
            sc.requestId = RequestValidator.newRequestId();
        }
        sc.fail(kind, sc.detail == null ? message : sc.detail);
        sc.status = status;
        if (sc.responseStarted) {
            abort(ex, sc);
            return;
        }
        sc.responseStarted = true;
        try {
            ProxyErrors.send(ex, status, kind, message, sc.requestId, server.requestIdHeader(), close, "HEAD".equals(sc.method), retryAfter);
        } catch (IOException | RuntimeException e) {
            // the client is already gone; nothing more to do
        }
        completeExchange(ex, sc);
    }

    // ---------------------------------------------------------------------------------------------- accounting

    private void finish(Scope sc) {
        long nanos = time.nanoTime() - sc.start;
        int status = sc.status == 0 ? (sc.failure == FailureKind.CLIENT_ABORT ? 499 : 200) : sc.status;
        RouteRuntime r = sc.route;
        int cls = sc.failure == FailureKind.CLIENT_ABORT ? 5 : ProxyMetrics.statusClassIndex(status);
        if (r != null) {
            r.requestsByClass[cls].inc();
            r.duration.observeNanos(nanos);
            r.requestWindow.recordNanos(nanos);
            r.bytesIn.add(Math.max(0, sc.bytesIn));
            r.bytesOut.add(Math.max(0, sc.bytesOut));
            if (sc.failure != FailureKind.NONE) {
                r.error(sc.failure).inc();
                switch (sc.failure) {
                    case UPSTREAM_TIMEOUT -> r.timeout("response").inc();
                    case DEADLINE_EXCEEDED -> r.timeout("request").inc();
                    case CONNECT_FAILED -> {
                        if (sc.connectTimeout) {
                            r.timeout("connect").inc();
                        }
                    }
                    default -> {
                    }
                }
            }
        } else {
            metrics.requests.labels(NO_ROUTE_LABEL, ProxyMetrics.STATUS_CLASSES[cls]).inc();
            metrics.requestDuration.labels(NO_ROUTE_LABEL).observeNanos(nanos);
            if (sc.failure != FailureKind.NONE) {
                metrics.errors.labels(NO_ROUTE_LABEL, sc.failure.code()).inc();
            }
        }
        if (logging.accessLogEnabled() && log.isEnabled(StructuredLogger.Level.INFO)
                && (logging.accessLogSampleRate() >= 1.0 || ThreadLocalRandom.current().nextDouble() < logging.accessLogSampleRate())) {
            String path = logging.includeQuery() && sc.query != null ? sc.path + "?" + sc.query : sc.path;
            log.info("request",
                    "request_id", sc.requestId,
                    "route", r == null ? NO_ROUTE_LABEL : r.id,
                    "method", sc.method,
                    "path", path,
                    "status", status,
                    "duration_ms", Math.round(nanos / 1000.0) / 1000.0,
                    "upstream", sc.upstream == null ? null : sc.upstream.id,
                    "attempts", sc.attempts,
                    "upstream_ttfb_ms", sc.ttfbNanos < 0 ? null : Math.round(sc.ttfbNanos / 1000.0) / 1000.0,
                    "bytes_in", sc.bytesIn,
                    "bytes_out", sc.bytesOut,
                    "failure", sc.failure == FailureKind.NONE ? null : sc.failure.code(),
                    "failure_detail", sc.failure == FailureKind.NONE ? null : sc.detail,
                    "fallback", sc.viaFallback ? Boolean.TRUE : null,
                    "client_ip", logging.includeClientIp() ? sc.client : null);
        }
    }
}
