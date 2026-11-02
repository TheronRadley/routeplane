package dev.routeplane.routing;

/** What a {@link RoutingPolicy} may know about an upstream: identity, weight and current load. Nothing about networking. */
public interface Candidate {
    /** Stable identifier, unique within the route. */
    String id();

    /** Configured weight, at least 1. */
    int weight();

    /** Requests currently in flight to this upstream. */
    int activeRequests();
}
