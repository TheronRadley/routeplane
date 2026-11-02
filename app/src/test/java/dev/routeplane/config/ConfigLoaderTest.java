package dev.routeplane.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.routeplane.config.RouteplaneConfig.RouteConfig;
import dev.routeplane.routing.PolicyRegistry;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConfigLoaderTest {
    private final ConfigLoader loader = new ConfigLoader(PolicyRegistry.load());

    private RouteplaneConfig parse(String yaml) {
        return parse(yaml, Map.of());
    }

    private RouteplaneConfig parse(String yaml, Map<String, String> env) {
        return loader.parse(yaml, "test.yaml", env::get);
    }

    private String errorsOf(String yaml) {
        return assertThrows(ConfigException.class, () -> parse(yaml)).getMessage();
    }

    private static final String MINIMAL = """
            server:
              port: 8080
            routes:
              - id: api
                match:
                  pathPrefix: /api
                upstreams:
                  - id: primary-a
                    url: http://localhost:9001
                    weight: 70
                  - id: primary-b
                    url: http://localhost:9002
                    weight: 30
                policy: weighted-round-robin
            """;

    @Test
    void acceptsTheDocumentedMinimalSchemaAndAppliesSafeDefaults() {
        RouteplaneConfig c = parse(MINIMAL);
        assertEquals(8080, c.server().port());
        assertEquals("127.0.0.1", c.server().host(), "proxy listener defaults to loopback");
        assertEquals("127.0.0.1", c.admin().host(), "admin listener defaults to loopback");
        assertEquals(9090, c.admin().port());
        assertNull(c.admin().bearerToken());
        RouteConfig r = c.routes().get(0);
        assertEquals("api", r.id());
        assertEquals("/api", r.match().pathPrefix());
        assertEquals(2, r.upstreams().size());
        assertEquals(70, r.upstreams().get(0).weight());
        assertEquals("weighted-round-robin", r.policy().type());
        assertFalse(r.retry().enabled(), "retries are off unless configured");
        assertEquals(java.util.Set.of("GET", "HEAD", "OPTIONS"), r.retry().methods(), "retry defaults to safe methods only");
        assertNull(r.circuitBreaker());
        assertNull(r.shadow(), "shadowing is opt-in");
        assertEquals(Duration.ofSeconds(1), r.timeouts().connect());
        assertEquals("http://localhost:9001", r.upstreams().get(0).url().toString());
    }

    @Test
    void reportsUnknownKeysWithPositionAndSuggestion() {
        String msg = errorsOf("""
                server:
                  port: 8080
                routes:
                  - id: api
                    match:
                      pathPrefx: /api
                    upstreams:
                      - id: a
                        url: http://localhost:9001
                """);
        assertTrue(msg.contains("test.yaml:6:"), msg);
        assertTrue(msg.contains("unknown key 'pathPrefx'"), msg);
        assertTrue(msg.contains("did you mean 'pathPrefix'"), msg);
    }

    @Test
    void reportsAllProblemsAtOnce() {
        String msg = errorsOf("""
                server:
                  port: 99999
                  maxHeaderBytes: tiny
                routes:
                  - id: "bad id!"
                    match:
                      pathPrefix: api
                    upstreams:
                      - id: a
                        url: ftp://x
                        weight: 0
                """);
        assertTrue(msg.contains("server.port"), msg);
        assertTrue(msg.contains("server.maxHeaderBytes"), msg);
        assertTrue(msg.contains("routes[0].id"), msg);
        assertTrue(msg.contains("routes[0].match.pathPrefix"), msg);
        assertTrue(msg.contains("routes[0].upstreams[0].url"), msg);
        assertTrue(msg.contains("routes[0].upstreams[0].weight"), msg);
        assertTrue(msg.startsWith("6 configuration problems") || msg.matches("(?s)\\d+ configuration problems.*"), msg);
    }

    @Test
    void rejectsDuplicateKeysAndDuplicateIds() {
        assertTrue(errorsOf("""
                server:
                  port: 1
                  port: 2
                routes: []
                """).contains("duplicate key 'port'"));
        String dup = errorsOf("""
                routes:
                  - id: a
                    match: {}
                    upstreams: [{id: u, url: "http://h:1"}, {id: u, url: "http://h:2"}]
                  - id: a
                    match: {}
                    upstreams: [{id: u, url: "http://h:1"}]
                """);
        assertTrue(dup.contains("duplicate upstream id 'u'"), dup);
        assertTrue(dup.contains("duplicate route id 'a'"), dup);
    }

    @Test
    void booleansAndNumbersAreStrict() {
        assertTrue(errorsOf("""
                server:
                  watchConfig: yes
                routes: [{id: a, match: {}, upstreams: [{id: u, url: "http://h:1"}]}]
                """).contains("expected true or false but found 'yes'"));
        assertTrue(errorsOf("""
                server:
                  port: 0x1F90
                routes: [{id: a, match: {}, upstreams: [{id: u, url: "http://h:1"}]}]
                """).contains("expected an integer"));
    }

    @Test
    void emptyAndMultiDocumentFilesAreRejected() {
        assertTrue(assertThrows(ConfigException.class, () -> parse("")).getMessage().contains("empty"));
        assertTrue(assertThrows(ConfigException.class, () -> parse("a: 1\n---\nb: 2\n")).getMessage().contains("multiple YAML documents"));
        assertTrue(assertThrows(ConfigException.class, () -> parse("routes: [\n")).getMessage().contains("YAML syntax error"));
    }

    @Test
    void environmentSubstitutionAppliesToValuesOnly() {
        RouteplaneConfig c = parse("""
                server:
                  port: ${PORT:-8081}
                routes:
                  - id: api
                    match: {pathPrefix: /}
                    upstreams:
                      - id: a
                        url: http://${UPSTREAM_HOST}:9001
                """, Map.of("UPSTREAM_HOST", "backend.internal"));
        assertEquals(8081, c.server().port());
        assertEquals("backend.internal", c.routes().get(0).upstreams().get(0).url().getHost());
        String msg = assertThrows(ConfigException.class, () -> parse("""
                server:
                  port: ${PORT}
                routes: [{id: a, match: {}, upstreams: [{id: u, url: "http://h:1"}]}]
                """)).getMessage();
        assertTrue(msg.contains("environment variable PORT is not set"), msg);
        // an env value cannot inject YAML structure or new keys
        RouteplaneConfig safe = parse("""
                server:
                  host: ${H}
                routes: [{id: a, match: {}, upstreams: [{id: u, url: "http://h:1"}]}]
                """, Map.of("H", "10.0.0.1"));
        assertEquals("10.0.0.1", safe.server().host());
    }

    @Test
    void upstreamUrlsAreRestricted() {
        for (String bad : new String[] {"https://h:1", "http://user:pw@h:1", "http://h:1/?q=1", "http://h:1/#f", "http://:1", "http://169.254.169.254/", "http://0.0.0.0:1", "http://[fe80::1]:1", "http://h:70000", "h:1"}) {
            String msg = errorsOf("routes: [{id: a, match: {}, upstreams: [{id: u, url: \"" + bad + "\"}]}]");
            assertTrue(msg.contains("routes[0].upstreams[0].url"), bad + " -> " + msg);
        }
        RouteplaneConfig ok = parse("routes: [{id: a, match: {}, upstreams: [{id: u, url: \"http://127.0.0.1:9/base/\"}, {id: v, url: \"http://10.0.0.5\"}]}]");
        assertEquals("http://127.0.0.1:9/base", ok.routes().get(0).upstreams().get(0).url().toString());
        assertEquals(80, ok.routes().get(0).upstreams().get(1).url().getPort());
    }

    @Test
    void remoteAdminRequiresTokenAndTls() {
        String msg = errorsOf("""
                admin:
                  host: 0.0.0.0
                routes: [{id: a, match: {}, upstreams: [{id: u, url: "http://h:1"}]}]
                """);
        assertTrue(msg.contains("requires auth.bearerToken"), msg);
        assertTrue(msg.contains("requires tls"), msg);
        // loopback needs neither
        parse("admin: {host: localhost}\nroutes: [{id: a, match: {}, upstreams: [{id: u, url: \"http://h:1\"}]}]");
        parse("admin: {host: \"::1\"}\nroutes: [{id: a, match: {}, upstreams: [{id: u, url: \"http://h:1\"}]}]");
        // a short token is refused even on loopback
        assertTrue(errorsOf("admin: {auth: {bearerToken: short}}\nroutes: [{id: a, match: {}, upstreams: [{id: u, url: \"http://h:1\"}]}]").contains("at least 16"));
    }

    @Test
    void adminAndProxyPortsMustDiffer() {
        assertTrue(errorsOf("""
                server: {port: 8080}
                admin: {port: 8080}
                routes: [{id: a, match: {}, upstreams: [{id: u, url: "http://h:1"}]}]
                """).contains("must differ from server.port"));
    }

    @Test
    void secretsNeverAppearInToString() {
        RouteplaneConfig c = parse("""
                admin:
                  host: localhost
                  auth: {bearerToken: "0123456789abcdefSECRET"}
                routes: [{id: a, match: {}, upstreams: [{id: u, url: "http://h:1"}]}]
                """);
        assertFalse(c.admin().toString().contains("SECRET"));
        assertFalse(c.toString().contains("SECRET"));
        assertNotNull(c.admin().bearerToken());
    }

    @Test
    void policiesAreValidated() {
        assertTrue(errorsOf("routes: [{id: a, match: {}, policy: random-ish, upstreams: [{id: u, url: \"http://h:1\"}]}]").contains("unknown routing policy 'random-ish'"));
        assertTrue(errorsOf("routes: [{id: a, match: {}, policy: hash, upstreams: [{id: u, url: \"http://h:1\"}]}]").contains("option 'key' is required"));
        RouteplaneConfig c = parse("""
                routes:
                  - id: a
                    match: {}
                    policy:
                      type: hash
                      options: {key: "header:X-User-Id"}
                    upstreams: [{id: u, url: "http://h:1"}]
                """);
        assertEquals("header:X-User-Id", c.routes().get(0).policy().options().get("key"));
    }

    @Test
    void timeoutRelationshipsAreChecked() {
        assertTrue(errorsOf("""
                routes:
                  - id: a
                    match: {}
                    timeouts: {response: 10s, request: 5s}
                    upstreams: [{id: u, url: "http://h:1"}]
                """).contains("must not exceed timeouts.request"));
    }

    @Test
    void retryAndBreakerSettingsAreChecked() {
        String msg = errorsOf("""
                routes:
                  - id: a
                    match: {}
                    upstreams: [{id: u, url: "http://h:1"}]
                    retry:
                      maxAttempts: 11
                      methods: [get, TRACE]
                      backoff: {initial: 5s, max: 1s}
                    circuitBreaker:
                      minimumRequests: 50
                      slidingWindow: {type: count, size: 10}
                      failureStatuses: [700]
                """);
        assertTrue(msg.contains("retry.maxAttempts"), msg);
        assertTrue(msg.contains("methods must be upper case"), msg);
        assertTrue(msg.contains("unsupported method 'TRACE'"), msg);
        assertTrue(msg.contains("backoff.initial") || msg.contains("must not exceed backoff.max"), msg);
        assertTrue(msg.contains("must be at least minimumRequests"), msg);
        assertTrue(msg.contains("'700' is not a status code"), msg);
    }

    @Test
    void validBreakerRetryAndHealthParse() {
        RouteConfig r = parse("""
                routes:
                  - id: a
                    match: {pathPrefix: /x}
                    upstreams: [{id: u, url: "http://h:1", maxConcurrent: 50}]
                    retry: {maxAttempts: 3, methods: [GET, PUT], retryStatuses: [503], backoff: {initial: 10ms, max: 200ms, multiplier: 3, jitter: equal}, budget: {ratio: 0.1, minRetriesPerSecond: 2, window: 5s}}
                    circuitBreaker:
                      failureRateThreshold: 40
                      minimumRequests: 10
                      slidingWindow: {type: time, duration: 20s}
                      openDuration: 3s
                      halfOpenMaxProbes: 2
                      recoverySuccessThreshold: 4
                      failureStatuses: [500, "502-504"]
                    health:
                      active: {path: /healthz, interval: 2s, timeout: 500ms}
                      passive: {consecutiveFailures: 3, ejectionTime: 5s}
                """).routes().get(0);
        assertEquals(3, r.retry().maxAttempts());
        assertEquals(java.util.Set.of(503), r.retry().retryStatuses());
        assertEquals(dev.routeplane.config.RouteplaneConfig.JitterMode.EQUAL, r.retry().jitter());
        assertEquals(40.0, r.circuitBreaker().failureRateThreshold());
        assertEquals(dev.routeplane.config.RouteplaneConfig.WindowType.TIME, r.circuitBreaker().windowType());
        assertTrue(r.circuitBreaker().failureStatuses().get(503));
        assertFalse(r.circuitBreaker().failureStatuses().get(501));
        assertEquals(50, r.upstreams().get(0).maxConcurrent());
        assertEquals("/healthz", r.health().active().path());
        assertEquals(5, r.health().passive().ejectionTime().toSeconds());
    }

    @Test
    void shadowingIsOptInAndSafeByDefault() {
        String base = """
                routes:
                  - id: a
                    match: {}
                    upstreams: [{id: u, url: "http://h:1"}]
                    shadow:
                      %s
                      targets:
                        - id: s
                          url: http://shadow:2
                          %s
                """;
        assertTrue(errorsOf(base.formatted("methods: [GET]", "timeout: 1s")).contains("missing required key 'samplePercent'"));
        assertTrue(errorsOf(base.formatted("methods: [GET, POST]", "samplePercent: 10")).contains("POST is not a safe method"));
        RouteConfig ok = parse(base.formatted("methods: [GET, POST]\n      allowUnsafeMethods: true", "samplePercent: 12.5\n          sampling: deterministic")).routes().get(0);
        assertEquals(12.5, ok.shadow().targets().get(0).samplePercent());
        assertEquals(RouteplaneConfig.SamplingMode.DETERMINISTIC, ok.shadow().targets().get(0).sampling());
        assertTrue(errorsOf("""
                routes:
                  - id: a
                    match: {}
                    upstreams: [{id: u, url: "http://h:1"}]
                    shadow:
                      targets: [{id: s, url: "http://h:1", samplePercent: 5}]
                """).contains("must not be the same URL as primary upstream"));
    }

    @Test
    void headerAndQueryMatchersNeedExactlyOneCondition() {
        assertTrue(errorsOf("""
                routes:
                  - id: a
                    match:
                      headers: [{name: X-Env, equals: canary, prefix: c}]
                    upstreams: [{id: u, url: "http://h:1"}]
                """).contains("exactly one of equals, prefix or present: true"));
        RouteConfig ok = parse("""
                routes:
                  - id: a
                    match:
                      methods: [GET, POST]
                      headers: [{name: X-Env, equals: canary}, {name: X-Debug, present: true}]
                      query: [{name: v, prefix: "2"}]
                    upstreams: [{id: u, url: "http://h:1"}]
                """).routes().get(0);
        assertEquals(2, ok.match().headers().size());
        assertTrue(ok.match().headers().get(1).present());
    }

    @Test
    void stripPrefixNeedsAPrefix() {
        assertTrue(errorsOf("routes: [{id: a, stripPrefix: true, match: {path: /x}, upstreams: [{id: u, url: \"http://h:1\"}]}]").contains("requires match.pathPrefix"));
    }

    @Test
    void atLeastOneRouteIsRequired() {
        assertTrue(errorsOf("server: {port: 1}\nroutes: []").contains("at least one route is required"));
    }
}
