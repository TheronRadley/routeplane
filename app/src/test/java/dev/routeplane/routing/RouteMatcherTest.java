package dev.routeplane.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.routeplane.config.RouteplaneConfig.MatchConfig;
import dev.routeplane.config.RouteplaneConfig.ValueMatch;
import dev.routeplane.routing.RoutingTestSupport.Req;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RouteMatcherTest {

    private static MatchConfig match(String path, String prefix, Set<String> methods, List<ValueMatch> headers, List<ValueMatch> query) {
        return new MatchConfig(path, prefix, methods, headers, query);
    }

    private static RouteMatcher prefix(String p) {
        return RouteMatcher.of(match(null, p, Set.of(), List.of(), List.of()));
    }

    @Test
    void exactPathMatchesOnlyThatPath() {
        RouteMatcher m = RouteMatcher.of(match("/health", null, Set.of(), List.of(), List.of()));
        assertTrue(m.matches(new Req().path("/health")));
        assertFalse(m.matches(new Req().path("/health/")));
        assertFalse(m.matches(new Req().path("/healthz")));
        assertFalse(m.matches(new Req().path("/health/x")));
    }

    @Test
    void prefixMatchesOnSegmentBoundaries() {
        RouteMatcher m = prefix("/api");
        assertTrue(m.matches(new Req().path("/api")));
        assertTrue(m.matches(new Req().path("/api/")));
        assertTrue(m.matches(new Req().path("/api/v1/users")));
        assertFalse(m.matches(new Req().path("/apix")));
        assertFalse(m.matches(new Req().path("/ap")));
        assertTrue(prefix("/api/").matches(new Req().path("/api/x")), "trailing slash in config is normalised");
        assertTrue(prefix("/").matches(new Req().path("/anything")));
    }

    @Test
    void methodsHeadersAndQueryAreAnded() {
        RouteMatcher m = RouteMatcher.of(match(null, "/api", Set.of("GET", "POST"),
                List.of(new ValueMatch("X-Env", "canary", null, false), new ValueMatch("X-Debug", null, null, true)),
                List.of(new ValueMatch("v", null, "2", false))));
        Req ok = new Req().method("POST").path("/api/x").header("x-env", "canary").header("X-DEBUG", "").query("v=2.1&z=1");
        assertTrue(m.matches(ok));
        assertFalse(m.matches(new Req().method("PUT").path("/api/x").header("x-env", "canary").header("X-Debug", "1").query("v=2")));
        assertFalse(m.matches(new Req().method("GET").path("/api/x").header("x-env", "stable").header("X-Debug", "1").query("v=2")));
        assertFalse(m.matches(new Req().method("GET").path("/api/x").header("x-env", "canary").query("v=2")));
        assertFalse(m.matches(new Req().method("GET").path("/api/x").header("x-env", "canary").header("X-Debug", "1").query("v=3")));
        assertFalse(m.matches(new Req().method("GET").path("/api/x").header("x-env", "Canary").header("X-Debug", "1").query("v=2")), "header values are case-sensitive");
    }

    @Test
    void stripPrefixKeepsALeadingSlash() {
        RouteMatcher m = prefix("/api");
        assertEquals("/users", m.stripPrefix("/api/users"));
        assertEquals("/", m.stripPrefix("/api"));
        assertEquals("/", m.stripPrefix("/api/"));
    }

    @Test
    void priorityThenSpecificityThenOrderDecide() {
        RouteTable.Builder<String> b = RouteTable.builder();
        b.add(prefix("/"), 0, "catch-all");
        b.add(prefix("/api"), 0, "api");
        b.add(prefix("/api/v1"), 0, "api-v1");
        b.add(RouteMatcher.of(match("/api/v1/health", null, Set.of(), List.of(), List.of())), 0, "health-exact");
        b.add(RouteMatcher.of(match(null, "/api", Set.of("POST"), List.of(), List.of())), 0, "api-post");
        b.add(prefix("/api"), 100, "api-high-priority");
        b.add(prefix("/api"), 0, "api-duplicate-later");
        RouteTable<String> t = b.build();
        assertEquals(Optional.of("api-high-priority"), t.match(new Req().path("/api/v1/x")), "explicit priority beats specificity");

        RouteTable.Builder<String> b2 = RouteTable.builder();
        b2.add(prefix("/"), 0, "catch-all");
        b2.add(prefix("/api"), 0, "api");
        b2.add(prefix("/api/v1"), 0, "api-v1");
        b2.add(RouteMatcher.of(match("/api/v1/health", null, Set.of(), List.of(), List.of())), 0, "health-exact");
        b2.add(RouteMatcher.of(match(null, "/api", Set.of("POST"), List.of(), List.of())), 0, "api-post");
        b2.add(prefix("/api"), 0, "api-duplicate-later");
        RouteTable<String> t2 = b2.build();
        assertEquals("health-exact", t2.match(new Req().path("/api/v1/health")).orElseThrow());
        assertEquals("api-v1", t2.match(new Req().path("/api/v1/other")).orElseThrow());
        assertEquals("api-post", t2.match(new Req().method("POST").path("/api/zzz")).orElseThrow(), "method constraint adds specificity");
        assertEquals("api", t2.match(new Req().path("/api/zzz")).orElseThrow(), "earlier route wins an exact tie");
        assertEquals("catch-all", t2.match(new Req().path("/other")).orElseThrow());
    }

    @Test
    void noMatchIsEmpty() {
        RouteTable.Builder<String> b = RouteTable.builder();
        b.add(prefix("/api"), 0, "api");
        assertTrue(b.build().match(new Req().path("/other")).isEmpty());
    }
}
