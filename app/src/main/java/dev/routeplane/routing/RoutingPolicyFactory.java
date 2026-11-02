package dev.routeplane.routing;

import java.util.List;
import java.util.Map;

/** Creates {@link RoutingPolicy} instances from the {@code policy.options} of a route. Discovered with {@link java.util.ServiceLoader}. */
public interface RoutingPolicyFactory {

    /** The value used in {@code policy: <id>}. */
    String id();

    /** @return human-readable problems with {@code options}; empty when valid. Called before activation. */
    default List<String> validate(Map<String, String> options) {
        return List.of();
    }

    /** Creates a fresh policy instance for one route. Options have already passed {@link #validate}. */
    RoutingPolicy create(Map<String, String> options);
}
