package dev.routeplane.routing;

import java.util.Map;

/** A deliberately broken policy used to prove that a faulty custom policy cannot fail requests. */
public final class TestThrowingPolicyFactory implements RoutingPolicyFactory {
    @Override
    public String id() {
        return "test-throw";
    }

    @Override
    public RoutingPolicy create(Map<String, String> options) {
        return (request, candidates) -> {
            throw new IllegalStateException("policy bug");
        };
    }
}
