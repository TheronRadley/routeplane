package dev.routeplane.routing;

import dev.routeplane.util.Hashing;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/** The routing policies shipped with Routeplane. See docs/routing.md for semantics and guarantees. */
public final class BuiltInPolicies {
    private BuiltInPolicies() {
    }

    public static List<RoutingPolicyFactory> factories() {
        return List.of(new WeightedRoundRobinFactory(), new WeightedRandomFactory(), new RoundRobinFactory(),
                new LeastActiveFactory(), new HashFactory());
    }

    // ------------------------------------------------------------------------------ weighted round robin

    /**
     * Smooth weighted round-robin (the algorithm used by nginx): deterministic, evenly interleaved, exact proportions
     * over every cycle of {@code sum(weights)} selections. With weights 5/1/1 the sequence is a a b a c a a.
     */
    static final class WeightedRoundRobinFactory implements RoutingPolicyFactory {
        @Override
        public String id() {
            return "weighted-round-robin";
        }

        @Override
        public RoutingPolicy create(Map<String, String> options) {
            return new SmoothWeightedRoundRobin();
        }
    }

    static final class SmoothWeightedRoundRobin implements RoutingPolicy {
        private final Map<String, long[]> current = new java.util.HashMap<>();

        @Override
        public synchronized Candidate select(RoutingRequest request, List<? extends Candidate> candidates) {
            long total = 0;
            Candidate best = null;
            long bestValue = Long.MIN_VALUE;
            for (Candidate c : candidates) {
                long[] cw = current.computeIfAbsent(c.id(), k -> new long[1]);
                cw[0] += c.weight();
                total += c.weight();
                if (cw[0] > bestValue) {
                    bestValue = cw[0];
                    best = c;
                }
            }
            current.get(best.id())[0] -= total;
            return best;
        }
    }

    // ------------------------------------------------------------------------------------ weighted random

    static final class WeightedRandomFactory implements RoutingPolicyFactory {
        @Override
        public String id() {
            return "weighted-random";
        }

        @Override
        public List<String> validate(Map<String, String> options) {
            List<String> problems = new ArrayList<>();
            String seed = options.get("seed");
            if (seed != null && !seed.matches("-?[0-9]{1,18}")) {
                problems.add("option 'seed' must be an integer");
            }
            return problems;
        }

        @Override
        public RoutingPolicy create(Map<String, String> options) {
            String seed = options.get("seed");
            return seed == null ? new WeightedRandom(null) : new WeightedRandom(new SplittableRandom(Long.parseLong(seed)));
        }
    }

    /** Picks proportionally to weight. With a {@code seed} option the sequence is reproducible (single-threaded). */
    static final class WeightedRandom implements RoutingPolicy {
        private final SplittableRandom seeded;

        WeightedRandom(SplittableRandom seeded) {
            this.seeded = seeded;
        }

        @Override
        public Candidate select(RoutingRequest request, List<? extends Candidate> candidates) {
            long total = 0;
            for (Candidate c : candidates) {
                total += c.weight();
            }
            long r;
            if (seeded != null) {
                synchronized (this) {
                    r = seeded.nextLong(total);
                }
            } else {
                r = ThreadLocalRandom.current().nextLong(total);
            }
            for (Candidate c : candidates) {
                r -= c.weight();
                if (r < 0) {
                    return c;
                }
            }
            return candidates.get(candidates.size() - 1);
        }
    }

    // ------------------------------------------------------------------------------------- round robin

    static final class RoundRobinFactory implements RoutingPolicyFactory {
        @Override
        public String id() {
            return "round-robin";
        }

        @Override
        public RoutingPolicy create(Map<String, String> options) {
            AtomicInteger counter = new AtomicInteger();
            return (request, candidates) -> candidates.get(Math.floorMod(counter.getAndIncrement(), candidates.size()));
        }
    }

    // --------------------------------------------------------------------------------------- least active

    static final class LeastActiveFactory implements RoutingPolicyFactory {
        @Override
        public String id() {
            return "least-active";
        }

        @Override
        public RoutingPolicy create(Map<String, String> options) {
            return new LeastActive();
        }
    }

    /** Lowest {@code activeRequests / weight}; ties are broken by a rotating start index so load spreads evenly. */
    static final class LeastActive implements RoutingPolicy {
        private final AtomicInteger rotation = new AtomicInteger();

        @Override
        public Candidate select(RoutingRequest request, List<? extends Candidate> candidates) {
            int n = candidates.size();
            int start = Math.floorMod(rotation.getAndIncrement(), n);
            Candidate best = null;
            double bestScore = Double.MAX_VALUE;
            for (int i = 0; i < n; i++) {
                Candidate c = candidates.get((start + i) % n);
                double score = (double) c.activeRequests() / c.weight();
                if (score < bestScore) {
                    bestScore = score;
                    best = c;
                }
            }
            return best;
        }
    }

    // --------------------------------------------------------------------------------------------- hash

    static final class HashFactory implements RoutingPolicyFactory {
        @Override
        public String id() {
            return "hash";
        }

        @Override
        public List<String> validate(Map<String, String> options) {
            List<String> problems = new ArrayList<>();
            String key = options.get("key");
            if (key == null) {
                problems.add("option 'key' is required (header:<name>, cookie:<name>, query:<name>, path, client-ip or request-id)");
            } else if (!isValidKey(key)) {
                problems.add("option 'key' must be header:<name>, cookie:<name>, query:<name>, path, client-ip or request-id but is '" + key + "'");
            }
            return problems;
        }

        static boolean isValidKey(String key) {
            return key.equals("path") || key.equals("client-ip") || key.equals("request-id")
                    || ((key.startsWith("header:") || key.startsWith("cookie:") || key.startsWith("query:")) && key.indexOf(':') < key.length() - 1);
        }

        @Override
        public RoutingPolicy create(Map<String, String> options) {
            return new RendezvousHash(options.get("key"));
        }
    }

    /**
     * Weighted rendezvous (highest-random-weight) hashing: a given key always maps to the same upstream while the
     * candidate set is unchanged, and when an upstream leaves only the keys that mapped to it move. Requests without
     * the key fall back to round-robin so they are spread rather than pinned to one upstream.
     */
    static final class RendezvousHash implements RoutingPolicy {
        private final String key;
        private final AtomicInteger missing = new AtomicInteger();

        RendezvousHash(String key) {
            this.key = key;
        }

        @Override
        public Candidate select(RoutingRequest request, List<? extends Candidate> candidates) {
            String value = extract(request);
            if (value == null || value.isEmpty()) {
                return candidates.get(Math.floorMod(missing.getAndIncrement(), candidates.size()));
            }
            long keyHash = Hashing.hash64(value);
            Candidate best = null;
            double bestScore = Double.MAX_VALUE;
            for (Candidate c : candidates) {
                double u = Hashing.toUnitInterval(Hashing.combine(keyHash, Hashing.hash64(c.id())));
                double score = -Math.log(u) / c.weight();
                if (score < bestScore) {
                    bestScore = score;
                    best = c;
                }
            }
            return best;
        }

        private String extract(RoutingRequest r) {
            if (key.equals("path")) {
                return r.path();
            }
            if (key.equals("client-ip")) {
                return r.clientAddress();
            }
            if (key.equals("request-id")) {
                return r.requestId();
            }
            String name = key.substring(key.indexOf(':') + 1);
            if (key.startsWith("header:")) {
                return r.header(name);
            }
            if (key.startsWith("cookie:")) {
                return r.cookie(name);
            }
            return r.queryParam(name);
        }
    }
}
