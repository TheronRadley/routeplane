package dev.routeplane.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class ScenarioLoaderTest {
    private static final String VALID = """
            id: demo
            description: demo scenario
            kind: failure
            upstreams:
              - {id: a, profile: {delayMs: 5}}
              - {id: b}
            proxy:
              jvmOptions: ["-Xmx256m"]
              base: |
                routes:
                  - id: api
                    match: {pathPrefix: /api}
                    upstreams: [{id: a, url: "http://127.0.0.1:${UP_A}"}]
              variants:
                baseline: |
                  timeouts: {request: 1s}
                resilient: |
                  timeouts: {request: 1s}
                  retry: {maxAttempts: 2}
            load: {path: /api/x, rate: 300, warmup: 2s, duration: 20s, timeout: 2s}
            runs: 2
            timeline:
              - {at: 5s, upstream: a, set: {refuse: true}, label: down}
              - {at: 10s, upstream: a, set: {refuse: false}, label: up, recovery: true}
              - {at: 12s, upstream: b, recover: 5s, label: ramp}
            """;

    @Test
    void parsesVariantsTimelineAndLoad() {
        Scenario s = ScenarioLoader.parse(VALID, "x");
        assertEquals("demo", s.id);
        assertTrue(s.isFailureScenario());
        assertEquals(List.of(300), s.tiers());
        assertEquals(List.of("baseline", "resilient"), List.copyOf(s.variants.keySet()));
        assertTrue(s.variants.get("resilient").contains("\n    timeouts: {request: 1s}\n    retry: {maxAttempts: 2}\n"), "variant additions are indented to attach to the route:\n" + s.variants.get("resilient"));
        assertEquals(Duration.ofSeconds(20), s.duration);
        assertEquals(3, s.timeline.size());
        assertFalse(s.timeline.get(0).isRecovery());
        assertTrue(s.timeline.get(1).isRecovery());
        assertTrue(s.timeline.get(2).isRecovery(), "a gradual recovery ramp is a recovery");
        assertEquals(5000L, s.timeline.get(2).recoverMillis());
    }

    @Test
    void rejectsMistakes() {
        assertThrows(IllegalArgumentException.class, () -> ScenarioLoader.parse(VALID.replace("kind: failure", "kind: failure\nbogus: 1"), "x"));
        assertThrows(IllegalArgumentException.class, () -> ScenarioLoader.parse(VALID.replace("upstream: a, set: {refuse: true}", "upstream: zzz, set: {refuse: true}"), "x"));
        assertThrows(IllegalArgumentException.class, () -> ScenarioLoader.parse(VALID.replace("at: 10s", "at: 30s"), "x"), "event after the end of the window");
        assertThrows(IllegalArgumentException.class, () -> ScenarioLoader.parse(VALID.replace("kind: failure", "kind: weird"), "x"));
        assertThrows(IllegalArgumentException.class, () -> ScenarioLoader.parse("id: x\nkind: steady\nupstreams: [{id: a}]\n", "x"), "a proxied scenario needs a proxy config");
        assertThrows(IllegalArgumentException.class, () -> ScenarioLoader.parse(VALID.replace("warmup: 2s", "warmup: 2seconds"), "x"));
    }

    @Test
    void highRatesGetFewerRuns() {
        Scenario s = ScenarioLoader.parse("id: d\ndirect: true\nupstreams: [{id: a}]\nload: {rates: [100, 5000]}\nruns: 3\nhighRateRuns: 1\n", "d");
        assertEquals(3, s.runsFor(100));
        assertEquals(1, s.runsFor(5000));
    }

    @Test
    void everyShippedScenarioLoadsAndIsInternallyConsistent() throws Exception {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.isDirectory(dir.resolve("experiments/scenarios"))) {
            dir = dir.getParent();
        }
        Assumptions.assumeTrue(dir != null, "repository layout not found from the working directory");
        List<Scenario> all = ScenarioLoader.loadAll(dir.resolve("experiments/scenarios"));
        assertTrue(all.size() >= 14, "6 steady + 8 failure scenarios expected, found " + all.size());
        for (Scenario s : all) {
            assertFalse(s.variants.isEmpty(), s.id);
            if (!s.direct) {
                for (String config : s.variants.values()) {
                    for (var u : s.upstreams) {
                        assertTrue(config.contains("${UP_" + u.id().toUpperCase() + "}") || s.upstreams.size() > 1 && !config.contains("UP_" + u.id().toUpperCase()), s.id + " config should reference upstream " + u.id());
                    }
                    assertTrue(config.contains("${PROXY_PORT}") && config.contains("${ADMIN_PORT}"), s.id);
                }
            }
            if (s.isFailureScenario()) {
                assertTrue(s.timeline.stream().anyMatch(Scenario.TimelineEvent::isRecovery) || s.id.contains("gradual"), s.id + " should end its fault");
            }
        }
    }
}
