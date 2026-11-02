package dev.routeplane.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.routeplane.routing.RoutingTestSupport.Req;
import dev.routeplane.routing.RoutingTestSupport.Up;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BuiltInPoliciesTest {
    private final PolicyRegistry registry = PolicyRegistry.load();

    private RoutingPolicy policy(String id, Map<String, String> options) {
        return registry.find(id).orElseThrow().create(options);
    }

    @Test
    void smoothWeightedRoundRobinIsDeterministicAndExact() {
        RoutingPolicy p = policy("weighted-round-robin", Map.of());
        List<Up> ups = List.of(new Up("a", 5), new Up("b", 1), new Up("c", 1));
        StringBuilder seq = new StringBuilder();
        for (int i = 0; i < 7; i++) {
            seq.append(p.select(new Req(), ups).id());
        }
        assertEquals("aabacaa", seq.toString(), "nginx smooth WRR reference sequence for weights 5/1/1");
        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < 7000; i++) {
            counts.merge(p.select(new Req(), ups).id(), 1, Integer::sum);
        }
        assertEquals(5000, counts.get("a"));
        assertEquals(1000, counts.get("b"));
        assertEquals(1000, counts.get("c"));
    }

    @Test
    void weightedRoundRobinCopesWithShrinkingCandidateSets() {
        RoutingPolicy p = policy("weighted-round-robin", Map.of());
        Up a = new Up("a", 1);
        Up b = new Up("b", 1);
        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < 100; i++) {
            counts.merge(p.select(new Req(), List.of(a)).id(), 1, Integer::sum);
        }
        for (int i = 0; i < 100; i++) {
            counts.merge(p.select(new Req(), List.of(a, b)).id(), 1, Integer::sum);
        }
        assertEquals(50, counts.get("b"), "after b returns, it receives its fair share");
    }

    @Test
    void weightedRandomFollowsWeightsAndIsReproducibleWithASeed() {
        List<Up> ups = List.of(new Up("a", 70), new Up("b", 30));
        RoutingPolicy p1 = policy("weighted-random", Map.of("seed", "42"));
        RoutingPolicy p2 = policy("weighted-random", Map.of("seed", "42"));
        int a = 0;
        for (int i = 0; i < 20_000; i++) {
            Candidate c1 = p1.select(new Req(), ups);
            assertEquals(c1.id(), p2.select(new Req(), ups).id(), "same seed, same sequence");
            if (c1.id().equals("a")) {
                a++;
            }
        }
        double share = a / 20_000.0;
        assertTrue(share > 0.68 && share < 0.72, "share of a was " + share);
    }

    @Test
    void roundRobinCyclesInOrder() {
        RoutingPolicy p = policy("round-robin", Map.of());
        List<Up> ups = List.of(new Up("a", 1), new Up("b", 9), new Up("c", 1));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            sb.append(p.select(new Req(), ups).id());
        }
        assertEquals("abcabc", sb.toString(), "weights are ignored by plain round-robin");
    }

    @Test
    void leastActivePicksTheLeastLoadedWeightAware() {
        RoutingPolicy p = policy("least-active", Map.of());
        assertEquals("b", p.select(new Req(), List.of(new Up("a", 1).active(5), new Up("b", 1).active(1), new Up("c", 1).active(3))).id());
        // 6 active on a weight-3 upstream (2.0 per weight) is lighter than 3 active on weight-1 (3.0 per weight)
        assertEquals("heavy", p.select(new Req(), List.of(new Up("light", 1).active(3), new Up("heavy", 3).active(6))).id());
        // ties rotate rather than always choosing the first
        List<Up> idle = List.of(new Up("a", 1), new Up("b", 1), new Up("c", 1));
        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < 300; i++) {
            counts.merge(p.select(new Req(), idle).id(), 1, Integer::sum);
        }
        assertEquals(100, counts.get("a"));
        assertEquals(100, counts.get("b"));
        assertEquals(100, counts.get("c"));
    }

    @Test
    void hashIsStickyDeterministicAndMinimallyDisruptive() {
        RoutingPolicy p = policy("hash", Map.of("key", "header:X-User-Id"));
        List<Up> three = List.of(new Up("a", 1), new Up("b", 1), new Up("c", 1));
        Map<String, String> assignment = new HashMap<>();
        Map<String, Integer> spread = new HashMap<>();
        for (int i = 0; i < 3000; i++) {
            String user = "user-" + i;
            String chosen = p.select(new Req().header("X-User-Id", user), three).id();
            assertEquals(chosen, p.select(new Req().header("X-User-Id", user), three).id(), "sticky");
            assignment.put(user, chosen);
            spread.merge(chosen, 1, Integer::sum);
        }
        for (int c : spread.values()) {
            assertTrue(c > 800 && c < 1200, "roughly even spread: " + spread);
        }
        List<Up> two = List.of(three.get(0), three.get(2));
        int moved = 0;
        for (int i = 0; i < 3000; i++) {
            String user = "user-" + i;
            String before = assignment.get(user);
            String after = p.select(new Req().header("X-User-Id", user), two).id();
            if (!before.equals("b")) {
                assertEquals(before, after, "keys that did not map to the removed upstream must not move");
            } else {
                moved++;
            }
        }
        assertTrue(moved > 800, "keys of the removed upstream were redistributed");
    }

    @Test
    void hashHonoursWeights() {
        RoutingPolicy p = policy("hash", Map.of("key", "query:k"));
        List<Up> ups = List.of(new Up("light", 1), new Up("heavy", 3));
        int heavy = 0;
        for (int i = 0; i < 8000; i++) {
            if (p.select(new Req().query("k=key" + i), ups).id().equals("heavy")) {
                heavy++;
            }
        }
        double share = heavy / 8000.0;
        assertTrue(share > 0.72 && share < 0.78, "heavy share was " + share);
    }

    @Test
    void hashKeySourcesAndMissingKeyFallback() {
        List<Up> ups = List.of(new Up("a", 1), new Up("b", 1), new Up("c", 1), new Up("d", 1));
        assertEquals(policy("hash", Map.of("key", "path")).select(new Req().path("/x"), ups).id(), policy("hash", Map.of("key", "path")).select(new Req().path("/x"), ups).id());
        RoutingPolicy cookie = policy("hash", Map.of("key", "cookie:sid"));
        assertEquals(cookie.select(new Req().cookie("sid", "abc"), ups).id(), cookie.select(new Req().cookie("sid", "abc"), ups).id());
        RoutingPolicy ip = policy("hash", Map.of("key", "client-ip"));
        assertEquals(ip.select(new Req().client("10.1.1.1"), ups).id(), ip.select(new Req().client("10.1.1.1"), ups).id());
        // without the key, requests are spread (round-robin) instead of pinned to one upstream
        RoutingPolicy h = policy("hash", Map.of("key", "header:X-Missing"));
        List<String> seen = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            seen.add(h.select(new Req(), ups).id());
        }
        assertEquals(4, seen.stream().distinct().count());
    }

    @Test
    void hashOptionsAreValidated() {
        RoutingPolicyFactory f = registry.find("hash").orElseThrow();
        assertTrue(f.validate(Map.of()).get(0).contains("required"));
        assertTrue(f.validate(Map.of("key", "header:")).get(0).contains("must be"));
        assertTrue(f.validate(Map.of("key", "weird")).get(0).contains("must be"));
        assertTrue(f.validate(Map.of("key", "client-ip")).isEmpty());
        assertNotEquals(0, registry.find("weighted-random").orElseThrow().validate(Map.of("seed", "x")).size());
    }

    /** A third-party policy discovered through ServiceLoader (see src/test/resources/META-INF/services). */
    @Test
    void customPoliciesAreDiscoveredThroughServiceLoaderWithoutNetworkingTypes() {
        RoutingPolicy p = registry.find("test-last").orElseThrow().create(Map.of());
        assertEquals("c", p.select(new Req(), List.of(new Up("a", 1), new Up("b", 1), new Up("c", 1))).id());
    }
}
