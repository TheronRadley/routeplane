package dev.routeplane.bench;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Everything measured in one run (one scenario, one offered rate, one repetition). Serialised as JSON by Gson. */
public final class RunSummary {
    public String scenario;
    public String variant;
    public String description;
    public String kind;
    public boolean direct;
    public int tierRps;
    public int run;
    public long startedAtMillis;
    public double windowSeconds;

    // ---- load generator view
    public double offeredRps;
    public double achievedRps;
    public double okRps;
    public long offered;
    public long completed;
    public long ok;
    public long droppedByGenerator;
    public Map<String, Long> outcomes = new TreeMap<>();
    public double successRatePct;
    public double proxyGeneratedPct;
    public double proxyUpstreamCausedPct;
    public double proxyProtectivePct;
    public double upstreamOriginatedPct;
    public double transportPct;
    public Latency latencyAll;
    public Latency latencyOk;
    public double generatorLagP99Ms;
    public double generatorLagMaxMs;
    public double generatorCpuPct;
    public List<String> flags = new ArrayList<>();

    // ---- resources (Linux: /proc), proxy process unless stated
    public double proxyCpuSeconds;
    public double proxyCpuPctOfMachine;
    public double proxyRssMbMax;
    public double proxyHeapUsedMbMax;
    public double proxyGcCount;
    public double proxyGcSeconds;
    public double proxyThreadsMax;
    public double upstreamsCpuPctOfMachine;
    public double systemCpuPct;
    public double proxyCpuMsPerRequest;
    public double tcpDownstreamMax;
    public double tcpUpstreamMax;

    // ---- proxy telemetry deltas over the window
    public Map<String, Double> proxyDeltas = new TreeMap<>();
    public double retryAmplification;
    public double shadowQueueDepthMax;
    public Map<String, Long> upstreamServed = new TreeMap<>();
    public Double recoverySeconds;
    public String recoveryNote;

    public List<SecondPoint> series = new ArrayList<>();
    public List<EventMark> events = new ArrayList<>();
    public List<BreakerTransition> breakerTransitions = new ArrayList<>();

    /** Percentiles in milliseconds; a field is null when the run has too few samples for that percentile to be meaningful. */
    public static final class Latency {
        public long samples;
        public Double p50ms;
        public Double p90ms;
        public Double p95ms;
        public Double p99ms;
        public Double p999ms;
        public Double maxMs;
        public Double meanMs;
    }

    public static final class SecondPoint {
        public int t;
        public long offered;
        public long completed;
        public long ok;
        public long proxyErr;
        public long upstreamErr;
        public long transportErr;
        public long dropped;
        public Double p50ms;
        public Double p99ms;
        /** sampled proxy gauges for this second, e.g. breaker state per upstream, shadow queue depth */
        public Map<String, Double> gauges = new TreeMap<>();
    }

    public static final class EventMark {
        public double atSeconds;
        public String label;
        public String detail;
    }

    public static final class BreakerTransition {
        public String upstream;
        public double atSeconds;
        public String from;
        public String to;
        public String reason;
    }
}
