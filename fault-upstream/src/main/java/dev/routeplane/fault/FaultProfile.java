package dev.routeplane.fault;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable description of how a {@link FaultUpstream} misbehaves. All probabilities are evaluated from a single
 * seeded random draw per request, in the fixed order reset, close, partial, error, so a given seed and arrival order
 * always produce the same sequence of faults.
 */
public final class FaultProfile {
    public static final FaultProfile DEFAULT = new FaultProfile(0, 0, 500, 0, 0, 0, 0, 0, 256, 256, 0, 0, false, 1L);

    /** Fixed service time added to every response. */
    public final long delayMs;
    /** Extra uniformly distributed delay in [0, jitterMs]. */
    public final long jitterMs;
    /** Status returned for injected errors. */
    public final int errorStatus;
    /** Probability of answering with {@link #errorStatus}. */
    public final double errorRate;
    /** Probability of resetting the connection (TCP RST) without answering. */
    public final double resetRate;
    /** Probability of closing the connection (FIN) without answering. */
    public final double closeRate;
    /** Probability of sending the headers and half of the body, then closing (truncated response). */
    public final double partialRate;
    /** Body throughput limit in bytes per second (0 = unlimited). */
    public final long slowBytesPerSecond;
    public final int payloadMin;
    public final int payloadMax;
    /** Capacity: at most this many requests are served concurrently (0 = unlimited). */
    public final int maxConcurrency;
    /** How long an over-capacity request waits for a slot before getting 503. */
    public final long queueWaitMs;
    /** When true the upstream stops listening and drops every open connection (a crashed process). */
    public final boolean refuse;
    public final long seed;

    public FaultProfile(long delayMs, long jitterMs, int errorStatus, double errorRate, double resetRate, double closeRate,
                        double partialRate, long slowBytesPerSecond, int payloadMin, int payloadMax, int maxConcurrency,
                        long queueWaitMs, boolean refuse, long seed) {
        this.delayMs = delayMs;
        this.jitterMs = jitterMs;
        this.errorStatus = errorStatus;
        this.errorRate = errorRate;
        this.resetRate = resetRate;
        this.closeRate = closeRate;
        this.partialRate = partialRate;
        this.slowBytesPerSecond = slowBytesPerSecond;
        this.payloadMin = payloadMin;
        this.payloadMax = Math.max(payloadMin, payloadMax);
        this.maxConcurrency = maxConcurrency;
        this.queueWaitMs = queueWaitMs;
        this.refuse = refuse;
        this.seed = seed;
        if (errorRate < 0 || resetRate < 0 || closeRate < 0 || partialRate < 0 || errorRate + resetRate + closeRate + partialRate > 1.0000001) {
            throw new IllegalArgumentException("fault probabilities must be non-negative and sum to at most 1");
        }
        if (errorStatus < 100 || errorStatus > 599) {
            throw new IllegalArgumentException("errorStatus must be an HTTP status code");
        }
        if (payloadMin < 0 || payloadMax > 64 * 1024 * 1024) {
            throw new IllegalArgumentException("payload size must be between 0 and 64 MiB");
        }
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("delayMs", delayMs);
        m.put("jitterMs", jitterMs);
        m.put("errorStatus", errorStatus);
        m.put("errorRate", errorRate);
        m.put("resetRate", resetRate);
        m.put("closeRate", closeRate);
        m.put("partialRate", partialRate);
        m.put("slowBytesPerSecond", slowBytesPerSecond);
        m.put("payloadMin", payloadMin);
        m.put("payloadMax", payloadMax);
        m.put("maxConcurrency", maxConcurrency);
        m.put("queueWaitMs", queueWaitMs);
        m.put("refuse", refuse);
        m.put("seed", seed);
        return m;
    }

    /** Returns a copy of {@code base} with the keys present in {@code patch} replaced. Unknown keys are rejected. */
    public static FaultProfile merge(FaultProfile base, Map<String, Object> patch) {
        Map<String, Object> m = new LinkedHashMap<>(base.toMap());
        for (Map.Entry<String, Object> e : patch.entrySet()) {
            if (!m.containsKey(e.getKey())) {
                throw new IllegalArgumentException("unknown profile field '" + e.getKey() + "'; known: " + m.keySet());
            }
            m.put(e.getKey(), e.getValue());
        }
        return new FaultProfile(
                num(m, "delayMs").longValue(), num(m, "jitterMs").longValue(), num(m, "errorStatus").intValue(),
                num(m, "errorRate").doubleValue(), num(m, "resetRate").doubleValue(), num(m, "closeRate").doubleValue(),
                num(m, "partialRate").doubleValue(), num(m, "slowBytesPerSecond").longValue(), num(m, "payloadMin").intValue(),
                num(m, "payloadMax").intValue(), num(m, "maxConcurrency").intValue(), num(m, "queueWaitMs").longValue(),
                Boolean.parseBoolean(String.valueOf(m.get("refuse"))), num(m, "seed").longValue());
    }

    private static Number num(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v instanceof Number n) {
            return n;
        }
        throw new IllegalArgumentException("profile field '" + key + "' must be a number");
    }

    public FaultProfile with(String key, Object value) {
        return merge(this, Map.of(key, value));
    }

    @Override
    public String toString() {
        return toMap().toString();
    }
}
