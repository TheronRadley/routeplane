package dev.routeplane.routing;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;

/** Built-in policies plus any {@link RoutingPolicyFactory} registered through {@link ServiceLoader}. */
public final class PolicyRegistry {
    private final Map<String, RoutingPolicyFactory> factories = new LinkedHashMap<>();

    private PolicyRegistry() {
    }

    public static PolicyRegistry load() {
        PolicyRegistry r = new PolicyRegistry();
        BuiltInPolicies.factories().forEach(f -> r.factories.put(f.id(), f));
        for (RoutingPolicyFactory f : ServiceLoader.load(RoutingPolicyFactory.class)) {
            if (r.factories.containsKey(f.id())) {
                throw new IllegalStateException("duplicate routing policy id '" + f.id() + "'");
            }
            r.factories.put(f.id(), f);
        }
        return r;
    }

    public Optional<RoutingPolicyFactory> find(String id) {
        return Optional.ofNullable(factories.get(id));
    }

    public Set<String> ids() {
        return factories.keySet();
    }
}
