package dev.routeplane.proxy;

import dev.routeplane.config.RouteplaneConfig.SamplingMode;
import dev.routeplane.config.RouteplaneConfig.ShadowConfig;
import dev.routeplane.config.RouteplaneConfig.ShadowTarget;
import dev.routeplane.observability.MetricsRegistry.Counter;
import dev.routeplane.observability.MetricsRegistry.Histogram;
import dev.routeplane.observability.StructuredLogger;
import dev.routeplane.util.Hashing;
import dev.routeplane.util.TimeSource;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpHeaders;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.random.RandomGenerator;

/**
 * Traffic shadowing: duplicates a sample of eligible requests to a separate target and discards the answer, so a new
 * version can be observed under real traffic without any effect on clients.
 *
 * <p>Guarantees (each is covered by tests):
 * <ul>
 *   <li><b>The primary path never waits for, or fails because of, shadow work.</b> {@link #offer} is non-blocking and
 *       catches everything; shadow I/O happens on dedicated worker threads with their own HTTP client and pool.</li>
 *   <li><b>Everything is bounded.</b> Per target: {@code maxConcurrent} worker threads, a queue of
 *       {@code queueCapacity} tasks, a per-request timeout, a response size cap, and a queue-wait limit after which a
 *       stale task is discarded. When the queue is full the <em>new</em> task is dropped and counted
 *       ({@code routeplane_shadow_dropped_total{reason="queue_full"}}); nothing blocks and nothing grows.</li>
 *   <li><b>Opt-in and safe by default:</b> only methods listed in {@code shadow.methods} (default GET, HEAD, OPTIONS);
 *       only bodies that were buffered completely and fit {@code maxBodyBytes}; sensitive headers are removed.</li>
 * </ul>
 */
final class ShadowDispatcher implements AutoCloseable {
    /** Bodies larger than this are not compared byte-for-byte (lengths still are). */
    static final int COMPARE_BODY_MAX = 64 * 1024;

    private static final String[] SENSITIVE_NAME_PARTS = {"auth", "token", "secret", "password", "api-key", "apikey", "session", "cookie", "credential"};

    /** A bounded copy of the primary response, filled while it streams to the client. */
    static final class PrimaryCapture {
        private final CountDownLatch done = new CountDownLatch(1);
        private final Set<String> compareHeaders;
        private final ByteArrayOutputStream body = new ByteArrayOutputStream();
        private volatile int status = -1;
        private volatile Map<String, String> headers = Map.of();
        private volatile long totalBytes;
        private volatile boolean bodyTruncated;
        private volatile boolean ok;

        PrimaryCapture(Set<String> compareHeaders) {
            this.compareHeaders = compareHeaders;
        }

        void onHeaders(int statusCode, HttpHeaders upstream) {
            Map<String, String> m = new HashMap<>();
            for (String h : compareHeaders) {
                upstream.firstValue(h).ifPresent(v -> m.put(h, v));
            }
            this.headers = m;
            this.status = statusCode;
        }

        synchronized void onBody(byte[] buf, int off, int len) {
            totalBytes += len;
            int room = COMPARE_BODY_MAX - body.size();
            if (room > 0) {
                body.write(buf, off, Math.min(room, len));
            }
            if (len > room) {
                bodyTruncated = true;
            }
        }

        void complete(boolean success) {
            this.ok = success;
            done.countDown();
        }

        boolean await(long nanos) {
            try {
                return done.await(Math.max(0, nanos), TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    private record Task(String requestId, String method, String pathAndQuery, List<String[]> headers, byte[] body,
                        long enqueuedNanos, PrimaryCapture capture) {
    }

    private final String routeId;
    private final ShadowConfig cfg;
    private final ProxyMetrics metrics;
    private final TimeSource time;
    private final StructuredLogger log;
    private final RandomGenerator random;
    private final List<Target> targets = new ArrayList<>();
    private volatile boolean closed;

    ShadowDispatcher(String routeId, ShadowConfig cfg, ProxyMetrics metrics, TimeSource time, StructuredLogger log, RandomGenerator random) {
        this.routeId = routeId;
        this.cfg = cfg;
        this.metrics = metrics;
        this.time = time;
        this.log = log;
        this.random = random;
        for (ShadowTarget t : cfg.targets()) {
            targets.add(new Target(t));
        }
    }

    ShadowConfig config() {
        return cfg;
    }

    List<TargetView> targetViews() {
        return new ArrayList<>(targets);
    }

    /** Read-only view of a target for the admin API and gauges. */
    interface TargetView {
        String id();

        int queueDepth();

        int queueCapacity();

        int inFlight();

        int maxConcurrent();

        double samplePercent();
    }

    /** True if a request with this method and declared body size could be mirrored (decides whether the body is buffered). */
    boolean wantsBody(String method, long contentLength) {
        return cfg.methods().contains(method) && contentLength <= cfg.maxBodyBytes();
    }

    boolean eligibleMethod(String method) {
        return cfg.methods().contains(method);
    }

    /**
     * Samples and enqueues. Never blocks, never throws.
     *
     * @return the capture to fill with the primary response if comparison is enabled and at least one target sampled
     *         the request; otherwise null
     */
    PrimaryCapture offer(ProxyRequest req, BodySource body, List<String[]> upstreamHeaders) {
        PrimaryCapture capture = null;
        try {
            boolean methodOk = cfg.methods().contains(req.method);
            List<String[]> headers = null;
            for (Target t : targets) {
                if (!methodOk) {
                    t.skip("method").inc();
                    continue;
                }
                if (body instanceof BodySource.Streaming) {
                    // a declared length above the shadow limit is "too large"; chunked or over-the-buffer bodies are "not replayable"
                    t.skip(req.contentLength > cfg.maxBodyBytes() ? "body_too_large" : "body_not_replayable").inc();
                    continue;
                }
                byte[] bytes = body instanceof BodySource.Buffered b ? b.bytes() : new byte[0];
                if (bytes.length > cfg.maxBodyBytes()) {
                    t.skip("body_too_large").inc();
                    continue;
                }
                Boolean sampled = t.sample(req);
                if (sampled == null) {
                    t.skip("no_sample_key").inc();
                    continue;
                }
                if (!sampled) {
                    continue;
                }
                t.sampled.inc();
                if (cfg.compare().enabled() && capture == null) {
                    capture = new PrimaryCapture(cfg.compare().headers());
                }
                if (headers == null) {
                    headers = sanitize(upstreamHeaders);
                }
                t.enqueue(new Task(req.requestId, req.method, req.pathAndQuery(), headers, bytes, time.nanoTime(), capture));
            }
        } catch (Throwable e) {
            log.error("shadow_offer_failed", e, "route", routeId);
        }
        return capture;
    }

    /** Applies the explicit header policy: allow-list or strip sensitive names, then add the configured headers. */
    List<String[]> sanitize(List<String[]> in) {
        List<String[]> out = new ArrayList<>(in.size() + 2);
        boolean allowMode = !cfg.allowHeaders().isEmpty();
        for (String[] h : in) {
            String ln = h[0].toLowerCase(Locale.ROOT);
            if (ln.equals("host")) {
                continue;
            }
            if (allowMode) {
                if (!cfg.allowHeaders().contains(ln)) {
                    continue;
                }
            } else if (cfg.stripHeaders().contains(ln) || isSensitive(ln)) {
                continue;
            }
            out.add(h);
        }
        for (Map.Entry<String, String> e : cfg.setHeaders().entrySet()) {
            out.removeIf(h -> h[0].equalsIgnoreCase(e.getKey()));
            out.add(new String[] {e.getKey(), e.getValue()});
        }
        out.add(new String[] {"X-Routeplane-Shadow", "1"});
        return out;
    }

    static boolean isSensitive(String lowerName) {
        for (String p : SENSITIVE_NAME_PARTS) {
            if (lowerName.contains(p)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void close() {
        closed = true;
        for (Target t : targets) {
            t.close();
        }
    }

    // ------------------------------------------------------------------------------------------------ target

    private final class Target implements TargetView, AutoCloseable {
        final ShadowTarget cfgT;
        final UpstreamClient client;
        final BlockingQueue<Task> queue;
        final AtomicInteger inFlight = new AtomicInteger();
        final List<Thread> workers = new ArrayList<>();
        final Counter.Child sampled;
        final Counter.Child sent;
        final Histogram.Child duration;

        Target(ShadowTarget t) {
            this.cfgT = t;
            this.client = new UpstreamClient(t.url(), t.connectTimeout());
            this.queue = new ArrayBlockingQueue<>(t.queueCapacity());
            this.sampled = metrics.shadowSampled.labels(routeId, t.id());
            this.sent = metrics.shadowRequests.labels(routeId, t.id());
            this.duration = metrics.shadowDuration.labels(routeId, t.id());
            for (int i = 0; i < t.maxConcurrent(); i++) {
                workers.add(Thread.ofVirtual().name("shadow-" + routeId + "-" + t.id() + "-" + i).start(this::workerLoop));
            }
        }

        @Override
        public String id() {
            return cfgT.id();
        }

        @Override
        public int queueDepth() {
            return queue.size();
        }

        @Override
        public int queueCapacity() {
            return cfgT.queueCapacity();
        }

        @Override
        public int inFlight() {
            return inFlight.get();
        }

        @Override
        public int maxConcurrent() {
            return cfgT.maxConcurrent();
        }

        @Override
        public double samplePercent() {
            return cfgT.samplePercent();
        }

        Counter.Child skip(String reason) {
            return metrics.shadowSkipped.labels(routeId, cfgT.id(), reason);
        }

        Counter.Child dropped(String reason) {
            return metrics.shadowDropped.labels(routeId, cfgT.id(), reason);
        }

        Counter.Child failure(String kind) {
            return metrics.shadowFailures.labels(routeId, cfgT.id(), kind);
        }

        /** @return null when a deterministic key is required but the request does not carry it */
        Boolean sample(ProxyRequest req) {
            double pct = cfgT.samplePercent();
            if (pct <= 0) {
                return false;
            }
            if (pct >= 100) {
                return true;
            }
            if (cfgT.sampling() == SamplingMode.RANDOM) {
                return random.nextDouble() * 100.0 < pct;
            }
            String key = sampleKey(req);
            if (key == null || key.isEmpty()) {
                return null;
            }
            return Hashing.toUnitInterval(Hashing.hash64(key)) * 100.0 < pct;
        }

        private String sampleKey(ProxyRequest r) {
            String k = cfgT.sampleKey();
            if (k.equals("request-id")) {
                return r.requestId;
            }
            if (k.equals("path")) {
                return r.path;
            }
            if (k.equals("client-ip")) {
                return r.clientAddress;
            }
            String name = k.substring(k.indexOf(':') + 1);
            if (k.startsWith("header:")) {
                return r.header(name);
            }
            if (k.startsWith("cookie:")) {
                return r.cookie(name);
            }
            return r.queryParam(name);
        }

        void enqueue(Task task) {
            if (closed || !queue.offer(task)) {
                dropped(closed ? "shutdown" : "queue_full").inc();
            }
        }

        private void workerLoop() {
            try {
                while (!closed) {
                    process(queue.take());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private void process(Task t) {
            if (time.nanoTime() - t.enqueuedNanos() > cfgT.queueMaxWait().toNanos()) {
                dropped("expired").inc();
                return;
            }
            inFlight.incrementAndGet();
            long t0 = time.nanoTime();
            try (Deadlines.Watch watch = Deadlines.watch(Thread.currentThread(), cfgT.timeout().toNanos())) {
                try {
                    sent.inc();
                    BodySource body = t.body().length == 0 ? new BodySource.Empty() : new BodySource.Buffered(t.body());
                    UpstreamClient.Response r = client.send(t.method(), t.pathAndQuery(), t.headers(), body, cfgT.timeout());
                    boolean complete = false;
                    try {
                        complete = handleResponse(t, r, t0);
                    } finally {
                        if (complete) {
                            r.finish(); // body fully read: the exchange is already complete
                        } else {
                            r.close(); // aborted or failed: cancel
                        }
                    }
                } catch (UpstreamClient.UpstreamException e) {
                    failure(e.kind.code()).inc();
                } catch (InterruptedException e) {
                    failure(watch.fired() ? "upstream_timeout" : "interrupted").inc();
                    if (!watch.fired()) {
                        Thread.currentThread().interrupt();
                    }
                } catch (IOException e) {
                    failure(watch.fired() ? "upstream_timeout" : "upstream_reset").inc();
                }
            } catch (RuntimeException e) {
                failure("internal").inc();
                log.error("shadow_failed", e, "route", routeId, "target", cfgT.id());
            } finally {
                duration.observeNanos(time.nanoTime() - t0);
                inFlight.decrementAndGet();
            }
        }

        /** @return true if the body was read to its end (so the exchange completed normally) */
        private boolean handleResponse(Task t, UpstreamClient.Response r, long t0) throws IOException {
            ByteArrayOutputStream keep = new ByteArrayOutputStream();
            long total = 0;
            boolean truncated = false;
            byte[] buf = new byte[8192];
            InputStream in = r.body();
            int n;
            while ((n = in.read(buf)) >= 0) {
                total += n;
                if (total > cfg.maxResponseBytes()) {
                    failure("response_too_large").inc();
                    return false; // the caller closes the stream, which aborts the exchange; the remainder is never read
                }
                int room = COMPARE_BODY_MAX - keep.size();
                if (room > 0) {
                    keep.write(buf, 0, Math.min(room, n));
                }
                if (n > room) {
                    truncated = true;
                }
            }
            if (r.status() >= 500) {
                failure("status_5xx").inc();
            }
            if (cfg.compare().enabled() && t.capture() != null) {
                long remaining = cfgT.timeout().toNanos() - (time.nanoTime() - t0);
                String result = compare(t.capture(), r, keep.toByteArray(), total, truncated, remaining);
                metrics.shadowComparisons.labels(routeId, cfgT.id(), result).inc();
                if (!result.equals("match") && !result.equals("body_not_compared")) {
                    log.info("shadow_mismatch", "route", routeId, "target", cfgT.id(), "request_id", t.requestId(), "result", result,
                            "shadow_status", r.status(), "shadow_bytes", total);
                }
            }
            return true;
        }

        private String compare(PrimaryCapture p, UpstreamClient.Response shadow, byte[] shadowBody, long shadowTotal, boolean shadowTruncated, long remainingNanos) {
            if (!p.await(remainingNanos)) {
                return "timeout";
            }
            if (!p.ok) {
                return "primary_unavailable";
            }
            if (p.status != shadow.status()) {
                return "status_mismatch";
            }
            for (String h : cfg.compare().headers()) {
                String a = p.headers.get(h);
                String b = shadow.headers().firstValue(h).orElse(null);
                if (a == null ? b != null : !a.equals(b)) {
                    return "header_mismatch";
                }
            }
            if (!cfg.compare().compareBody()) {
                return "match";
            }
            if (p.totalBytes != shadowTotal) {
                return "body_mismatch";
            }
            if (p.bodyTruncated || shadowTruncated) {
                return "body_not_compared";
            }
            byte[] pb;
            synchronized (p) {
                pb = p.body.toByteArray();
            }
            return Arrays.equals(pb, shadowBody) ? "match" : "body_mismatch";
        }

        @Override
        public void close() {
            for (Thread w : workers) {
                w.interrupt();
            }
            Task t;
            while ((t = queue.poll()) != null) {
                dropped("shutdown").inc();
            }
            client.close();
        }
    }
}
