package dev.routeplane.bench;

import dev.routeplane.bench.RunSummary.Latency;
import dev.routeplane.bench.RunSummary.SecondPoint;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;
import org.HdrHistogram.ConcurrentHistogram;
import org.HdrHistogram.Histogram;

/**
 * Open-loop load generator.
 *
 * <p><b>Why open loop.</b> A closed-loop tool (N threads, each waiting for its response before sending the next request)
 * slows down exactly when the system under test does, so stalls disappear from the data ("coordinated omission"). Here
 * request <em>i</em> has an intended send time {@code start + i / rate} that does not depend on any response. Latency is
 * measured from that intended time to the completion, so a request that had to wait because the generator or the system
 * was behind still reports the full time it would have waited. Requests are in flight concurrently (asynchronous client,
 * virtual-thread executor); if {@code maxInFlight} is reached the request is <em>counted as dropped by the generator</em>
 * and the run is flagged - nothing is silently skipped.
 *
 * <p>Pacing never spins: the pacing thread parks until the next intended time, so at very high rates a request may be sent
 * up to about 50 microseconds before its intended time; latency is measured from the intended time regardless.
 */
public final class LoadGenerator {
    public record Config(URI target, String method, int bodyBytes, double rate, Duration warmup, Duration duration, Duration timeout, int maxInFlight) {
    }

    /** Called when the measurement window opens and closes (to start/stop resource sampling and the fault timeline). */
    public interface Hooks {
        void windowStarted(long nanoTime);

        void windowEnded(long nanoTime);
    }

    /** Raw load-generator outcome for one run. */
    public static final class Result {
        public long offered;
        public long completed;
        public long ok;
        public long dropped;
        public Map<String, Long> outcomes = new TreeMap<>();
        public Latency latencyAll;
        public Latency latencyOk;
        public double lagP99Ms;
        public double lagMaxMs;
        public double cpuSeconds;
        public List<SecondPoint> series = new ArrayList<>();
        public double windowSeconds;
    }

    private static final long MAX_MICROS = 600_000_000L;

    /** Minimum number of samples for a percentile to be reported: ten observations in the tail beyond it. */
    public static long minSamples(double q) {
        return (long) Math.ceil(10.0 / (1.0 - q) - 1e-6);
    }

    public Result run(Config c, Hooks hooks) throws InterruptedException {
        HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(c.timeout())
                .executor(Executors.newVirtualThreadPerTaskExecutor()).build();
        HttpRequest.Builder rb = HttpRequest.newBuilder(c.target()).timeout(c.timeout());
        rb.method(c.method(), c.bodyBytes() > 0 ? HttpRequest.BodyPublishers.ofByteArray(new byte[c.bodyBytes()]) : HttpRequest.BodyPublishers.noBody());
        HttpRequest request = rb.build();

        int seconds = (int) Math.ceil(c.duration().toMillis() / 1000.0);
        AtomicLongArray offeredPerSec = new AtomicLongArray(seconds + 1);
        AtomicLongArray completedPerSec = new AtomicLongArray(seconds + 1);
        AtomicLongArray okPerSec = new AtomicLongArray(seconds + 1);
        AtomicLongArray proxyErrPerSec = new AtomicLongArray(seconds + 1);
        AtomicLongArray upstreamErrPerSec = new AtomicLongArray(seconds + 1);
        AtomicLongArray transportErrPerSec = new AtomicLongArray(seconds + 1);
        AtomicLongArray droppedPerSec = new AtomicLongArray(seconds + 1);
        ConcurrentHistogram[] perSecond = new ConcurrentHistogram[seconds + 1];
        for (int i = 0; i < perSecond.length; i++) {
            perSecond[i] = new ConcurrentHistogram(1, MAX_MICROS, 2);
        }
        ConcurrentHistogram all = new ConcurrentHistogram(1, MAX_MICROS, 3);
        ConcurrentHistogram okH = new ConcurrentHistogram(1, MAX_MICROS, 3);
        ConcurrentHistogram lag = new ConcurrentHistogram(1, MAX_MICROS, 2);
        ConcurrentHashMap<String, LongAdder> outcomes = new ConcurrentHashMap<>();
        LongAdder offered = new LongAdder();
        LongAdder completed = new LongAdder();
        LongAdder okCount = new LongAdder();
        LongAdder dropped = new LongAdder();
        AtomicInteger inFlight = new AtomicInteger();

        long start = System.nanoTime() + 300_000_000L;
        long windowStart = start + c.warmup().toNanos();
        long windowEnd = windowStart + c.duration().toNanos();
        long total = (long) ((c.warmup().toNanos() + c.duration().toNanos()) / 1e9 * c.rate());
        double interval = 1e9 / c.rate();
        boolean started = false;
        long cpuBefore = 0;

        for (long i = 0; i < total; i++) {
            long intended = start + (long) (i * interval);
            long wait = intended - System.nanoTime();
            if (wait > 50_000) {
                LockSupport.parkNanos(wait);
            }
            long now = System.nanoTime();
            if (!started && intended >= windowStart) {
                started = true;
                cpuBefore = processCpuNanos();
                hooks.windowStarted(now);
            }
            boolean inWindow = intended >= windowStart && intended < windowEnd;
            int slot = inWindow ? (int) ((intended - windowStart) / 1_000_000_000L) : -1;
            if (inWindow) {
                offered.increment();
                offeredPerSec.incrementAndGet(slot);
                lag.recordValue(Math.max(1, Math.min(MAX_MICROS, (now - intended) / 1000)));
            }
            if (inFlight.get() >= c.maxInFlight()) {
                if (inWindow) {
                    dropped.increment();
                    droppedPerSec.incrementAndGet(slot);
                    outcomes.computeIfAbsent("dropped_by_generator", k -> new LongAdder()).increment();
                }
                continue;
            }
            inFlight.incrementAndGet();
            final int fslot = slot;
            CompletableFuture<HttpResponse<Void>> f = client.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                    .orTimeout(c.timeout().toMillis(), TimeUnit.MILLISECONDS);
            f.whenComplete((resp, err) -> {
                long end = System.nanoTime();
                inFlight.decrementAndGet();
                if (fslot < 0) {
                    return;
                }
                String category = classify(resp, err);
                long micros = Math.max(1, Math.min(MAX_MICROS, (end - intended) / 1000));
                completed.increment();
                completedPerSec.incrementAndGet(fslot);
                all.recordValue(micros);
                perSecond[fslot].recordValue(micros);
                outcomes.computeIfAbsent(category, k -> new LongAdder()).increment();
                if (category.equals("ok")) {
                    okCount.increment();
                    okPerSec.incrementAndGet(fslot);
                    okH.recordValue(micros);
                } else if (category.startsWith("proxy:")) {
                    proxyErrPerSec.incrementAndGet(fslot);
                } else if (category.equals("upstream_5xx") || category.equals("http_4xx")) {
                    upstreamErrPerSec.incrementAndGet(fslot);
                } else {
                    transportErrPerSec.incrementAndGet(fslot);
                }
            });
        }
        long loopEnd = System.nanoTime();
        long cpuAfter = processCpuNanos();
        hooks.windowEnded(Math.min(loopEnd, windowEnd));
        // let stragglers finish (bounded: the client timeout plus a margin); whatever is still open then is reported as incomplete
        long drainDeadline = System.nanoTime() + c.timeout().toNanos() + 2_000_000_000L;
        while (inFlight.get() > 0 && System.nanoTime() < drainDeadline) {
            Thread.sleep(20);
        }
        long incomplete = inFlight.get();
        if (incomplete > 0) {
            outcomes.computeIfAbsent("incomplete", k -> new LongAdder()).add(incomplete);
        }

        Result r = new Result();
        r.offered = offered.sum();
        r.completed = completed.sum();
        r.ok = okCount.sum();
        r.dropped = dropped.sum();
        outcomes.forEach((k, v) -> r.outcomes.put(k, v.sum()));
        r.latencyAll = latency(all);
        r.latencyOk = latency(okH);
        r.lagP99Ms = lag.getValueAtPercentile(99.0) / 1000.0;
        r.lagMaxMs = lag.getMaxValue() / 1000.0;
        r.cpuSeconds = (cpuAfter - cpuBefore) / 1e9;
        r.windowSeconds = c.duration().toMillis() / 1000.0;
        for (int s = 0; s < seconds; s++) {
            SecondPoint p = new SecondPoint();
            p.t = s;
            p.offered = offeredPerSec.get(s);
            p.completed = completedPerSec.get(s);
            p.ok = okPerSec.get(s);
            p.proxyErr = proxyErrPerSec.get(s);
            p.upstreamErr = upstreamErrPerSec.get(s);
            p.transportErr = transportErrPerSec.get(s);
            p.dropped = droppedPerSec.get(s);
            Histogram h = perSecond[s];
            if (h.getTotalCount() >= LoadGenerator.minSamples(0.5)) {
                p.p50ms = h.getValueAtPercentile(50) / 1000.0;
            }
            if (h.getTotalCount() >= 100) { // per-second p99 is shown as a trend line; the run-level p99 is the reported figure
                p.p99ms = h.getValueAtPercentile(99) / 1000.0;
            }
            r.series.add(p);
        }
        return r;
    }

    static Latency latency(Histogram h) {
        Latency l = new Latency();
        l.samples = h.getTotalCount();
        if (l.samples == 0) {
            return l;
        }
        l.p50ms = pct(h, 0.5);
        l.p90ms = pct(h, 0.9);
        l.p95ms = pct(h, 0.95);
        l.p99ms = pct(h, 0.99);
        l.p999ms = pct(h, 0.999);
        l.maxMs = h.getMaxValue() / 1000.0;
        l.meanMs = h.getMean() / 1000.0;
        return l;
    }

    private static Double pct(Histogram h, double q) {
        return h.getTotalCount() >= minSamples(q) ? h.getValueAtPercentile(q * 100.0) / 1000.0 : null;
    }

    /**
     * Outcome category of one request. {@code proxy:<code>} means the response carried {@code X-Routeplane-Error}, i.e. the
     * proxy itself produced it (upstream responses can never carry that header; the proxy strips it).
     */
    public static String classify(HttpResponse<?> resp, Throwable err) {
        if (err != null) {
            Throwable t = err;
            while (t instanceof CompletionException && t.getCause() != null) {
                t = t.getCause();
            }
            if (t instanceof HttpConnectTimeoutException || t instanceof ConnectException) {
                return "connect_error";
            }
            if (t instanceof HttpTimeoutException || t instanceof TimeoutException) {
                return "timeout";
            }
            if (t instanceof IOException) {
                return "io_error";
            }
            return "error:" + t.getClass().getSimpleName();
        }
        String proxyError = resp.headers().firstValue("x-routeplane-error").orElse(null);
        if (proxyError != null) {
            return "proxy:" + proxyError;
        }
        int s = resp.statusCode();
        if (s < 400) {
            return "ok";
        }
        return s < 500 ? "http_4xx" : "upstream_5xx";
    }

    private static long processCpuNanos() {
        var os = ManagementFactory.getOperatingSystemMXBean();
        return os instanceof com.sun.management.OperatingSystemMXBean sun ? Math.max(0, sun.getProcessCpuTime()) : 0;
    }
}
