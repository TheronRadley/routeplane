package dev.routeplane.proxy;

import dev.routeplane.routing.Candidate;

/** An upstream as one route sees it: the shared runtime plus this route's weight. This is what policies are shown. */
final class UpstreamBinding implements Candidate {
    final UpstreamRuntime runtime;
    final int weight;

    UpstreamBinding(UpstreamRuntime runtime, int weight) {
        this.runtime = runtime;
        this.weight = weight;
    }

    @Override
    public String id() {
        return runtime.id;
    }

    @Override
    public int weight() {
        return weight;
    }

    @Override
    public int activeRequests() {
        return runtime.limiter.inFlight();
    }
}
