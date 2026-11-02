package dev.routeplane.routing;

import dev.routeplane.config.RouteplaneConfig.MatchConfig;
import dev.routeplane.config.RouteplaneConfig.ValueMatch;
import java.util.List;
import java.util.Set;

/**
 * Compiled form of a route's {@code match} block. All configured conditions must hold (logical AND).
 *
 * <ul>
 *   <li>{@code path}: exact match on the normalised path.</li>
 *   <li>{@code pathPrefix}: matches on path-segment boundaries - {@code /api} matches {@code /api}, {@code /api/} and
 *       {@code /api/x} but not {@code /apix}.</li>
 *   <li>{@code methods}: request method is in the set.</li>
 *   <li>{@code headers}/{@code query}: each entry must hold; header names are case-insensitive, values are
 *       case-sensitive, and the first value of a repeated header/parameter is used.</li>
 * </ul>
 */
public final class RouteMatcher {
    private final String exactPath;
    private final String prefix;
    private final Set<String> methods;
    private final List<ValueMatch> headers;
    private final List<ValueMatch> query;
    private final int specificity;

    private RouteMatcher(MatchConfig m) {
        this.exactPath = m.path();
        this.prefix = m.pathPrefix() == null ? null : trimTrailingSlash(m.pathPrefix());
        this.methods = m.methods();
        this.headers = m.headers();
        this.query = m.query();
        int s = 0;
        if (exactPath != null) {
            s += 3_000_000 + exactPath.length();
        } else if (prefix != null) {
            s += 2_000_000 + prefix.length();
        }
        s += 1000 * (headers.size() + query.size()) + (methods.isEmpty() ? 0 : 500);
        this.specificity = s;
    }

    public static RouteMatcher of(MatchConfig m) {
        return new RouteMatcher(m);
    }

    /** Larger is more specific: exact path, then longer prefix, then more header/query/method constraints. */
    public int specificity() {
        return specificity;
    }

    public boolean matches(RoutingRequest r) {
        if (!methods.isEmpty() && !methods.contains(r.method())) {
            return false;
        }
        String p = r.path();
        if (exactPath != null) {
            if (!exactPath.equals(p)) {
                return false;
            }
        } else if (prefix != null && !prefix.isEmpty()) {
            if (!(p.equals(prefix) || p.startsWith(prefix + "/"))) {
                return false;
            }
        }
        for (ValueMatch h : headers) {
            if (!valueMatches(h, r.header(h.name()))) {
                return false;
            }
        }
        for (ValueMatch q : query) {
            if (!valueMatches(q, r.queryParam(q.name()))) {
                return false;
            }
        }
        return true;
    }

    /** Removes the configured prefix from {@code path} (for {@code stripPrefix: true}); the result always starts with '/'. */
    public String stripPrefix(String path) {
        if (prefix == null || prefix.isEmpty() || !path.startsWith(prefix)) {
            return path;
        }
        String rest = path.substring(prefix.length());
        return rest.isEmpty() ? "/" : rest;
    }

    private static boolean valueMatches(ValueMatch m, String actual) {
        if (m.present()) {
            return actual != null;
        }
        if (actual == null) {
            return false;
        }
        if (m.equals() != null) {
            return m.equals().equals(actual);
        }
        return m.prefix() != null && actual.startsWith(m.prefix());
    }

    private static String trimTrailingSlash(String p) {
        String t = p;
        while (t.length() > 1 && t.endsWith("/")) {
            t = t.substring(0, t.length() - 1);
        }
        return t.equals("/") ? "" : t;
    }
}
