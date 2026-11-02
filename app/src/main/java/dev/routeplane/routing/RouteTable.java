package dev.routeplane.routing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Routes in evaluation order: higher {@code priority} first; among equal priorities the more specific matcher first;
 * among equal specificity, configuration order. The first route whose matcher accepts the request wins.
 */
public final class RouteTable<R> {
    private record Entry<R>(RouteMatcher matcher, int priority, int order, R route) {
    }

    private final List<Entry<R>> entries;

    private RouteTable(List<Entry<R>> entries) {
        this.entries = entries;
    }

    public static final class Builder<R> {
        private final List<Entry<R>> list = new ArrayList<>();

        public Builder<R> add(RouteMatcher matcher, int priority, R route) {
            list.add(new Entry<>(matcher, priority, list.size(), route));
            return this;
        }

        public RouteTable<R> build() {
            List<Entry<R>> sorted = new ArrayList<>(list);
            sorted.sort(Comparator.<Entry<R>>comparingInt(e -> -e.priority())
                    .thenComparingInt(e -> -e.matcher().specificity())
                    .thenComparingInt(Entry::order));
            return new RouteTable<>(List.copyOf(sorted));
        }
    }

    public static <R> Builder<R> builder() {
        return new Builder<>();
    }

    public Optional<R> match(RoutingRequest request) {
        for (Entry<R> e : entries) {
            if (e.matcher().matches(request)) {
                return Optional.of(e.route());
            }
        }
        return Optional.empty();
    }

    /** Routes in evaluation order (for the admin API). */
    public List<R> inOrder() {
        List<R> out = new ArrayList<>(entries.size());
        for (Entry<R> e : entries) {
            out.add(e.route());
        }
        return out;
    }
}
