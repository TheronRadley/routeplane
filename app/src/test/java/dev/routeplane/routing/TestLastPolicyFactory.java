package dev.routeplane.routing;

import java.util.Map;

/** Example of a custom policy: always the last candidate. Registered via META-INF/services for the test above. */
public final class TestLastPolicyFactory implements RoutingPolicyFactory {
    @Override
    public String id() {
        return "test-last";
    }

    @Override
    public RoutingPolicy create(Map<String, String> options) {
        return (request, candidates) -> candidates.get(candidates.size() - 1);
    }
}
