package dev.routeplane.bench;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One experiment definition, loaded from experiments/scenarios/*.yaml (see docs/benchmarking.md for the format). */
public final class Scenario {
    /** A fault-injection upstream started for the experiment. */
    public record UpstreamSpec(String id, Map<String, Object> profile) {
    }

    /** A change applied to an upstream at a point of the measurement window (via its control API). */
    public record TimelineEvent(Duration at, String upstream, Map<String, Object> profile, Long recoverMillis, String label, boolean recovery) {
        /** True for events that end a fault: a gradual recovery ramp, or an event marked {@code recovery: true}. */
        public boolean isRecovery() {
            return recovery || recoverMillis != null;
        }
    }

    public String id;
    public String description = "";
    /** steady: one run per offered rate. failure: one fixed rate with a fault timeline. */
    public String kind = "steady";
    /** True: the load generator talks to the first upstream directly (no proxy): the baseline. */
    public boolean direct;
    /** Proxy configuration per variant (name -> YAML). One entry named "default" unless the scenario defines variants. */
    public final Map<String, String> variants = new LinkedHashMap<>();
    public List<String> proxyJvmOptions = List.of("-Xms256m", "-Xmx512m");
    public final List<UpstreamSpec> upstreams = new ArrayList<>();
    public String path = "/api/x";
    public String method = "GET";
    public int bodyBytes;
    public List<Integer> rates = List.of(100, 500);
    public int rate = 500;
    public Duration warmup = Duration.ofSeconds(5);
    public Duration duration = Duration.ofSeconds(15);
    public Duration timeout = Duration.ofSeconds(5);
    public int maxInFlight = 4000;
    public int runs = 3;
    /** Runs for tiers of 5000 rps and above, which are expected to saturate a small machine: one run shows where it falls over. */
    public int highRateRuns = 1;
    public final List<TimelineEvent> timeline = new ArrayList<>();
    /** Notes shown in the report next to the scenario (what it is meant to show, caveats). */
    public Map<String, String> notes = new LinkedHashMap<>();

    public int runsFor(int tier) {
        return tier >= 5000 ? Math.min(runs, highRateRuns) : runs;
    }

    public boolean isFailureScenario() {
        return kind.equals("failure");
    }

    /** Offered rates to run: all rates for steady scenarios, the single fixed rate for failure scenarios. */
    public List<Integer> tiers() {
        return isFailureScenario() ? List.of(rate) : rates;
    }
}
