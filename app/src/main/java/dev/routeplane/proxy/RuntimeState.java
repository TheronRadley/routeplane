package dev.routeplane.proxy;

import dev.routeplane.config.RouteplaneConfig;
import dev.routeplane.routing.RouteTable;
import dev.routeplane.routing.RoutingRequest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * An immutable snapshot of one activated configuration: the config itself, the route table, and the runtime objects
 * built from it. Requests capture the current snapshot once at their start and use it until they finish, so a reload
 * is atomic from every request's point of view.
 */
public final class RuntimeState {
    private final long version;
    private final String fileSha256;
    private final long loadedAtMillis;
    private final RouteplaneConfig config;
    private final RouteTable<RouteRuntime> table;
    private final List<RouteRuntime> routes;
    private final Map<String, UpstreamRuntime> upstreams;

    RuntimeState(long version, String fileSha256, long loadedAtMillis, RouteplaneConfig config, RouteTable<RouteRuntime> table,
                 List<RouteRuntime> routes, Map<String, UpstreamRuntime> upstreams) {
        this.version = version;
        this.fileSha256 = fileSha256;
        this.loadedAtMillis = loadedAtMillis;
        this.config = config;
        this.table = table;
        this.routes = List.copyOf(routes);
        this.upstreams = Collections.unmodifiableMap(upstreams);
    }

    public long version() {
        return version;
    }

    public String fileSha256() {
        return fileSha256;
    }

    public long loadedAtMillis() {
        return loadedAtMillis;
    }

    public RouteplaneConfig config() {
        return config;
    }

    Optional<RouteRuntime> match(RoutingRequest request) {
        return table.match(request);
    }

    /** Routes in evaluation order. */
    List<RouteRuntime> routesInOrder() {
        return table.inOrder();
    }

    List<RouteRuntime> routes() {
        return routes;
    }

    Map<String, UpstreamRuntime> upstreams() {
        return upstreams;
    }

    /** Resources of this snapshot that {@code next} no longer uses (to be closed after in-flight requests drain). */
    List<AutoCloseable> retiredBy(RuntimeState next) {
        Set<Object> keep = Collections.newSetFromMap(new IdentityHashMap<>());
        keep.addAll(next.upstreams.values());
        for (RouteRuntime r : next.routes) {
            if (r.shadow != null) {
                keep.add(r.shadow);
            }
        }
        List<AutoCloseable> out = new ArrayList<>();
        for (UpstreamRuntime u : upstreams.values()) {
            if (!keep.contains(u)) {
                out.add(u);
            }
        }
        for (RouteRuntime r : routes) {
            if (r.shadow != null && !keep.contains(r.shadow)) {
                out.add(r.shadow);
            }
        }
        return out;
    }

    /** Every resource of this snapshot (for shutdown). */
    List<AutoCloseable> allResources() {
        List<AutoCloseable> out = new ArrayList<>(upstreams.values());
        for (RouteRuntime r : routes) {
            if (r.shadow != null) {
                out.add(r.shadow);
            }
        }
        return out;
    }
}
