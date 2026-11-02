package dev.routeplane.routing;

import java.util.List;

/**
 * Chooses one upstream for a request. Implementations must be thread-safe and fast (they run on the request path).
 *
 * <p>Contract:
 * <ul>
 *   <li>{@code candidates} is never empty, is in configuration order, and only contains upstreams that are currently
 *       eligible (healthy, breaker not rejecting, below their concurrency limit).</li>
 *   <li>The returned value must be one of the elements of {@code candidates}.</li>
 *   <li>Do not block and do not perform I/O. If a policy throws or returns something else, Routeplane logs the
 *       problem, counts it in {@code routeplane_policy_errors_total} and falls back to the first candidate - a faulty
 *       policy degrades balancing but never fails the request.</li>
 * </ul>
 *
 * <p>Register a custom policy by implementing {@link RoutingPolicyFactory} and listing it in
 * {@code META-INF/services/dev.routeplane.routing.RoutingPolicyFactory}; select it with {@code policy: <id>}.
 */
@FunctionalInterface
public interface RoutingPolicy {
    Candidate select(RoutingRequest request, List<? extends Candidate> candidates);
}
