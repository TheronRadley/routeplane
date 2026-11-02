package dev.routeplane.config;

import dev.routeplane.config.RouteplaneConfig.*;
import dev.routeplane.observability.StructuredLogger;
import dev.routeplane.routing.PolicyRegistry;
import dev.routeplane.routing.RoutingPolicyFactory;
import dev.routeplane.util.Cidr;
import java.io.IOException;
import java.io.StringReader;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.MarkedYAMLException;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.nodes.Node;

/**
 * Loads and strictly validates {@code routeplane.yaml}. The YAML is composed into a node tree (never into arbitrary
 * Java objects), every key must be known, every value is range-checked, and all problems are reported together with
 * their line and column. Nothing here touches the network or the file system beyond reading the config itself and
 * checking that a configured key store exists.
 */
public final class ConfigLoader {
    private static final long MAX_BYTES = 1L << 20;
    private static final Pattern ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");
    private static final Pattern HEADER_NAME = Pattern.compile("^[!#$%&'*+\\-.^_`|~0-9A-Za-z]{1,64}$");
    private static final Duration ZERO = Duration.ZERO;
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    private final PolicyRegistry policies;

    public ConfigLoader(PolicyRegistry policies) {
        this.policies = policies;
    }

    public static RouteplaneConfig load(Path file) throws IOException {
        return new ConfigLoader(PolicyRegistry.load()).loadFile(file, System::getenv);
    }

    public RouteplaneConfig loadFile(Path file, Function<String, String> env) throws IOException {
        String source = file.toString();
        if (Files.size(file) > MAX_BYTES) {
            throw single(source, "file is larger than " + MAX_BYTES + " bytes");
        }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(Files.readAllBytes(file))).toString();
        } catch (CharacterCodingException e) {
            throw single(source, "file is not valid UTF-8");
        }
        return parse(text, source, env);
    }

    private static ConfigException single(String source, String message) {
        return new ConfigException(List.of(new ConfigIssue(source, 0, 0, "", message)));
    }

    // ------------------------------------------------------------------------------------------------ entry

    public RouteplaneConfig parse(String yamlText, String source, Function<String, String> env) {
        ConfigNode.Ctx ctx = new ConfigNode.Ctx(source, new EnvSubstitutor(env));
        Node root = compose(yamlText, source);
        ConfigNode top = ConfigNode.of(ctx, root, "");
        top.integer("version", 1, 1, 1);
        ServerConfig server = server(section(ctx, top, "server"));
        AdminConfig admin = admin(section(ctx, top, "admin"), server);
        LoggingConfig logging = logging(section(ctx, top, "logging"));
        MetricsConfig metrics = metrics(section(ctx, top, "metrics"));
        List<RouteConfig> routes = routes(ctx, top, server);
        top.rejectUnknown();
        if (!ctx.issues.isEmpty()) {
            throw new ConfigException(ctx.issues);
        }
        return new RouteplaneConfig(server, admin, logging, metrics, List.copyOf(routes));
    }

    private static Node compose(String text, String source) {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setAllowRecursiveKeys(false);
        options.setMaxAliasesForCollections(20);
        options.setNestingDepthLimit(40);
        options.setCodePointLimit((int) MAX_BYTES);
        options.setProcessComments(false);
        try {
            List<Node> docs = new ArrayList<>();
            for (Node n : new Yaml(options).composeAll(new StringReader(text))) {
                docs.add(n);
                if (docs.size() > 1) {
                    throw single(source, "multiple YAML documents found; a configuration file must contain exactly one");
                }
            }
            if (docs.isEmpty()) {
                throw single(source, "the file is empty");
            }
            return docs.get(0);
        } catch (MarkedYAMLException e) {
            var m = e.getProblemMark() != null ? e.getProblemMark() : e.getContextMark();
            throw new ConfigException(List.of(new ConfigIssue(source, m == null ? 0 : m.getLine() + 1, m == null ? 0 : m.getColumn() + 1, "",
                    "YAML syntax error: " + (e.getProblem() == null ? e.getMessage() : e.getProblem()))));
        } catch (YAMLException e) {
            throw single(source, "YAML error: " + e.getMessage());
        }
    }

    private static ConfigNode section(ConfigNode.Ctx ctx, ConfigNode parent, String key) {
        ConfigNode m = parent.map(key);
        return m != null ? m : ConfigNode.empty(ctx, parent.pathOf(key));
    }

    // ----------------------------------------------------------------------------------------------- server

    private ServerConfig server(ConfigNode n) {
        String host = n.string("host", "127.0.0.1");
        if (host.isBlank() || host.chars().anyMatch(Character::isWhitespace)) {
            n.error("host", "must be a host name or IP address");
            host = "127.0.0.1";
        }
        int port = n.integer("port", 8080, 0, 65535);
        int backlog = n.integer("backlog", 1024, 1, 65535);
        int maxConnections = n.integer("maxConnections", 10_000, 1, 1_000_000);
        int maxConcurrentRequests = n.integer("maxConcurrentRequests", 5_000, 1, 1_000_000);
        int maxHeaderBytes = n.integer("maxHeaderBytes", 16 * 1024, 1024, 1 << 20);
        long maxBody = n.longValue("maxRequestBodyBytes", 10L << 20, 0, 1L << 40);
        int maxBuffered = n.integer("maxBufferedBodyBytes", 256 * 1024, 0, 16 << 20);
        Duration headerTimeout = n.duration("requestHeaderTimeout", Duration.ofSeconds(10), Duration.ofSeconds(1), Duration.ofMinutes(10));
        Duration idle = n.duration("idleTimeout", Duration.ofSeconds(30), Duration.ofSeconds(1), Duration.ofHours(1));
        Duration maxResponse = n.duration("maxResponseTime", Duration.ofMinutes(10), Duration.ofSeconds(1), Duration.ofHours(24));
        Duration grace = n.duration("shutdownGracePeriod", Duration.ofSeconds(30), ZERO, Duration.ofMinutes(10));
        String idHeader = n.string("requestIdHeader", "X-Request-Id");
        if (!HEADER_NAME.matcher(idHeader).matches()) {
            n.error("requestIdHeader", "must be a valid HTTP header name");
            idHeader = "X-Request-Id";
        }
        ForwardingConfig forwarding = forwarding(section(n.ctxFor(), n, "forwarding"));
        HostHeaderMode hostMode = n.enumValue("hostHeader", HostHeaderMode.class, HostHeaderMode.UPSTREAM);
        boolean allowEncodedSlashes = n.bool("allowEncodedSlashes", false);
        boolean watch = n.bool("watchConfig", false);
        Duration watchInterval = n.duration("watchInterval", Duration.ofSeconds(2), Duration.ofMillis(500), Duration.ofHours(1));
        n.rejectUnknown();
        return new ServerConfig(host, port, backlog, maxConnections, maxConcurrentRequests, maxHeaderBytes, maxBody, maxBuffered,
                headerTimeout, idle, maxResponse, grace, idHeader, forwarding, hostMode, allowEncodedSlashes, watch, watchInterval);
    }

    private ForwardingConfig forwarding(ConfigNode n) {
        ForwardingMode mode = n.enumValue("mode", ForwardingMode.class, ForwardingMode.OVERWRITE);
        List<Cidr> trusted = new ArrayList<>();
        int i = 0;
        for (String s : n.strings("trustedProxies")) {
            try {
                trusted.add(Cidr.parse(s));
            } catch (IllegalArgumentException e) {
                n.error("trustedProxies", "entry " + i + ": " + e.getMessage());
            }
            i++;
        }
        String proto = n.string("proto", "http");
        if (!proto.equals("http") && !proto.equals("https")) {
            n.error("proto", "must be http or https (the value advertised in X-Forwarded-Proto) but is '" + proto + "'");
            proto = "http";
        }
        boolean emit = n.bool("emitForwardedHeader", false);
        if (mode == ForwardingMode.TRUSTED_APPEND && trusted.isEmpty()) {
            n.error("mode", "trusted-append requires at least one entry in trustedProxies");
        }
        n.rejectUnknown();
        return new ForwardingConfig(mode, List.copyOf(trusted), proto, emit);
    }

    // ------------------------------------------------------------------------------------------------ admin

    private AdminConfig admin(ConfigNode n, ServerConfig server) {
        boolean enabled = n.bool("enabled", true);
        String host = n.string("host", "127.0.0.1");
        if (host.isBlank() || host.chars().anyMatch(Character::isWhitespace)) {
            n.error("host", "must be a host name or IP address");
            host = "127.0.0.1";
        }
        int port = n.integer("port", 9090, 0, 65535);
        String token = null;
        ConfigNode auth = n.map("auth");
        if (auth != null) {
            token = auth.string("bearerToken", null);
            if (token != null && (token.length() < 16 || token.chars().anyMatch(Character::isWhitespace))) {
                auth.error("bearerToken", "must be at least 16 characters long and contain no whitespace");
            }
            auth.rejectUnknown();
        }
        TlsConfig tls = null;
        ConfigNode tn = n.map("tls");
        if (tn != null) {
            String ks = tn.requireString("keyStore");
            String type = tn.string("keyStoreType", "PKCS12").toUpperCase(Locale.ROOT);
            if (!type.equals("PKCS12") && !type.equals("JKS")) {
                tn.error("keyStoreType", "must be PKCS12 or JKS");
            }
            String pw = tn.requireString("keyStorePassword");
            Path p = Path.of(ks.isEmpty() ? "." : ks);
            if (!ks.isEmpty() && !Files.isReadable(p)) {
                tn.error("keyStore", "file '" + ks + "' does not exist or is not readable");
            }
            tn.rejectUnknown();
            tls = new TlsConfig(p, type, pw);
        }
        List<String> allowedHosts = n.strings("allowedHosts");
        n.rejectUnknown();
        if (enabled && !isLoopback(host)) {
            if (token == null) {
                n.error("auth", "binding the admin listener to non-loopback address '" + host + "' requires auth.bearerToken");
            }
            if (tls == null) {
                n.error("tls", "binding the admin listener to non-loopback address '" + host + "' requires tls (never expose admin credentials over plain HTTP)");
            }
        }
        if (enabled && port != 0 && port == server.port()) {
            n.error("port", "must differ from server.port (" + server.port() + ")");
        }
        return new AdminConfig(enabled, host, port, token, tls, List.copyOf(allowedHosts));
    }

    /** Loopback means 'localhost' or a loopback IP literal. Host names are never resolved here. */
    public static boolean isLoopback(String host) {
        String h = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        if (h.equalsIgnoreCase("localhost")) {
            return true;
        }
        if (!Cidr.isIpLiteral(h)) {
            return false;
        }
        try {
            return InetAddress.getByName(h).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------------------------- logging, metrics

    private LoggingConfig logging(ConfigNode n) {
        StructuredLogger.Level level = n.enumValue("level", StructuredLogger.Level.class, StructuredLogger.Level.INFO);
        ConfigNode access = section(n.ctxFor(), n, "accessLog");
        boolean enabled = access.bool("enabled", true);
        double rate = access.number("sampleRate", 1.0, 0.0, 1.0);
        boolean includeQuery = access.bool("includeQuery", false);
        boolean includeIp = access.bool("includeClientIp", true);
        access.rejectUnknown();
        int queue = n.integer("queueCapacity", 8192, 256, 1_000_000);
        n.rejectUnknown();
        return new LoggingConfig(level, enabled, rate, includeQuery, includeIp, queue);
    }

    private MetricsConfig metrics(ConfigNode n) {
        Duration window = n.duration("latencyWindow", Duration.ofSeconds(60), Duration.ofSeconds(10), Duration.ofHours(1));
        int series = n.integer("maxSeriesPerFamily", 2000, 100, 100_000);
        n.rejectUnknown();
        return new MetricsConfig(window, series);
    }

    // ------------------------------------------------------------------------------------------------ routes

    private List<RouteConfig> routes(ConfigNode.Ctx ctx, ConfigNode top, ServerConfig server) {
        List<ConfigNode> nodes = top.mapList("routes");
        if (nodes.isEmpty()) {
            top.error("routes", "at least one route is required");
        }
        List<RouteConfig> out = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (ConfigNode rn : nodes) {
            RouteConfig r = route(ctx, rn, server);
            if (!r.id().isEmpty() && !ids.add(r.id())) {
                rn.error("id", "duplicate route id '" + r.id() + "'");
            }
            out.add(r);
        }
        return out;
    }

    private RouteConfig route(ConfigNode.Ctx ctx, ConfigNode n, ServerConfig server) {
        String id = n.requireString("id");
        if (!id.isEmpty() && !ID.matcher(id).matches()) {
            n.error("id", "must match " + ID.pattern() + " (letters, digits, '.', '_' and '-'; at most 64 characters) because it is used as a metric label");
        }
        int priority = n.integer("priority", 0, -1000, 1000);
        MatchConfig match = match(n.requireMap("match"));
        boolean strip = n.bool("stripPrefix", false);
        if (strip && match.pathPrefix() == null) {
            n.error("stripPrefix", "requires match.pathPrefix");
        }
        List<UpstreamConfig> upstreams = upstreams(ctx, n, "upstreams", true);
        PolicyConfig policy = policy(ctx, n);
        FallbackConfig fallback = fallback(ctx, n, upstreams);
        TimeoutConfig timeouts = timeouts(section(ctx, n, "timeouts"));
        RetryConfig retry = retry(section(ctx, n, "retry"));
        BreakerConfig breaker = breaker(n.map("circuitBreaker"));
        HealthConfig health = health(n.map("health"));
        ShadowConfig shadow = shadow(ctx, n.map("shadow"), upstreams, server);
        n.rejectUnknown();
        return new RouteConfig(id, priority, match, strip, upstreams, policy, fallback, timeouts, retry, breaker, health, shadow);
    }

    private MatchConfig match(ConfigNode n) {
        String path = n.string("path", null);
        String prefix = n.string("pathPrefix", null);
        if (path != null && prefix != null) {
            n.error("path", "use either path (exact) or pathPrefix, not both");
        }
        path = checkPath(n, "path", path);
        prefix = checkPath(n, "pathPrefix", prefix);
        Set<String> methods = methods(n, "methods", Set.of());
        List<ValueMatch> headers = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ConfigNode h : n.mapList("headers")) {
            ValueMatch v = valueMatch(h, true);
            if (!seen.add(v.name().toLowerCase(Locale.ROOT))) {
                h.error("name", "header '" + v.name() + "' is matched twice");
            }
            headers.add(v);
        }
        List<ValueMatch> query = new ArrayList<>();
        for (ConfigNode q : n.mapList("query")) {
            query.add(valueMatch(q, false));
        }
        n.rejectUnknown();
        return new MatchConfig(path, prefix, methods, List.copyOf(headers), List.copyOf(query));
    }

    private String checkPath(ConfigNode n, String key, String value) {
        if (value == null) {
            return null;
        }
        if (!value.startsWith("/")) {
            n.error(key, "must start with '/' but is '" + value + "'");
            return null;
        }
        if (value.chars().anyMatch(c -> c <= 0x20 || c == 0x7F || c == '?' || c == '#' || c == '\\')) {
            n.error(key, "must not contain whitespace, control characters, '?', '#' or '\\'");
            return null;
        }
        if (value.contains("//") || value.contains("/./") || value.contains("/../") || value.endsWith("/..") || value.endsWith("/.")) {
            n.error(key, "must be in normal form (no '//' and no dot segments)");
            return null;
        }
        return value;
    }

    private ValueMatch valueMatch(ConfigNode n, boolean header) {
        String name = n.requireString("name");
        if (header && !name.isEmpty() && !HEADER_NAME.matcher(name).matches()) {
            n.error("name", "must be a valid HTTP header name");
        }
        String eq = n.string("equals", null);
        String prefix = n.string("prefix", null);
        boolean presentSet = n.has("present");
        boolean present = n.bool("present", false);
        int kinds = (eq != null ? 1 : 0) + (prefix != null ? 1 : 0) + (presentSet && present ? 1 : 0);
        if (kinds != 1) {
            n.errorHere("exactly one of equals, prefix or present: true is required");
        }
        if (presentSet && !present) {
            n.error("present", "only 'true' is meaningful; remove the key to not match on presence");
        }
        n.rejectUnknown();
        return new ValueMatch(name, eq, prefix, present);
    }

    private Set<String> methods(ConfigNode n, String key, Set<String> def) {
        if (!n.has(key)) {
            return def;
        }
        Set<String> out = new LinkedHashSet<>();
        for (String m : n.strings(key)) {
            String up = m.toUpperCase(Locale.ROOT);
            if (!RouteplaneConfig.SUPPORTED_METHODS.contains(up)) {
                n.error(key, "unsupported method '" + m + "' (supported: " + new java.util.TreeSet<>(RouteplaneConfig.SUPPORTED_METHODS) + ")");
            } else if (!m.equals(up)) {
                n.error(key, "methods must be upper case ('" + up + "' not '" + m + "')");
            } else {
                out.add(up);
            }
        }
        return Set.copyOf(out);
    }

    // ------------------------------------------------------------------------------------------- upstreams

    private List<UpstreamConfig> upstreams(ConfigNode.Ctx ctx, ConfigNode parent, String key, boolean required) {
        List<ConfigNode> nodes = parent.mapList(key);
        if (nodes.isEmpty() && required) {
            parent.error(key, "at least one upstream is required");
        }
        List<UpstreamConfig> out = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (ConfigNode un : nodes) {
            String id = un.requireString("id");
            if (!id.isEmpty() && !ID.matcher(id).matches()) {
                un.error("id", "must match " + ID.pattern());
            }
            if (!id.isEmpty() && !ids.add(id)) {
                un.error("id", "duplicate upstream id '" + id + "'");
            }
            URI url = url(un, "url");
            int weight = un.integer("weight", 1, 1, 1000);
            int maxConcurrent = un.integer("maxConcurrent", 1000, 1, 1_000_000);
            un.rejectUnknown();
            out.add(new UpstreamConfig(id, url, weight, maxConcurrent));
        }
        return out;
    }

    /** Validates an upstream or shadow URL: http only, no credentials, no query/fragment, no unspecified/link-local IP literals. */
    private URI url(ConfigNode n, String key) {
        String s = n.requireString(key);
        if (s.isEmpty()) {
            return null;
        }
        URI u;
        try {
            u = new URI(s);
        } catch (URISyntaxException e) {
            n.error(key, "is not a valid URL: " + e.getReason());
            return null;
        }
        String scheme = u.getScheme();
        if (scheme == null || !scheme.equalsIgnoreCase("http")) {
            n.error(key, "https".equalsIgnoreCase(scheme)
                    ? "https upstreams are not supported in this release (TLS towards upstreams is out of scope, see docs/security.md)"
                    : "must be an absolute http:// URL but is '" + s + "'");
            return null;
        }
        String host = u.getHost();
        if (host == null || host.isEmpty()) {
            n.error(key, "must contain a host");
            return null;
        }
        if (u.getUserInfo() != null) {
            n.error(key, "must not contain credentials");
            return null;
        }
        if (u.getRawQuery() != null || u.getRawFragment() != null) {
            n.error(key, "must not contain a query or fragment");
            return null;
        }
        int port = u.getPort() == -1 ? 80 : u.getPort();
        if (port < 1 || port > 65535) {
            n.error(key, "port must be between 1 and 65535");
            return null;
        }
        String path = u.getRawPath() == null ? "" : u.getRawPath();
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        if (!path.isEmpty() && (!path.startsWith("/") || path.contains("//") || path.contains("/../") || path.contains("/./") || path.endsWith("/.."))) {
            n.error(key, "base path must be normalised");
            return null;
        }
        String bare = host.startsWith("[") ? host.substring(1, host.length() - 1) : host;
        if (Cidr.isIpLiteral(bare)) {
            try {
                InetAddress ia = InetAddress.getByName(bare);
                if (ia.isAnyLocalAddress() || ia.isMulticastAddress() || ia.isLinkLocalAddress()) {
                    n.error(key, "address " + bare + " is not allowed (unspecified, multicast and link-local addresses such as cloud metadata endpoints are refused)");
                    return null;
                }
            } catch (UnknownHostException e) {
                n.error(key, "invalid IP address");
                return null;
            }
        }
        try {
            return new URI("http", null, host.startsWith("[") ? host : host, port, path.isEmpty() ? null : path, null, null);
        } catch (URISyntaxException e) {
            n.error(key, "is not a valid URL");
            return null;
        }
    }

    // ----------------------------------------------------------------------------------------- policy etc.

    private PolicyConfig policy(ConfigNode.Ctx ctx, ConfigNode route) {
        String type = "weighted-round-robin";
        Map<String, String> options = Map.of();
        ConfigNode pn = null;
        if (route.isMapping("policy")) {
            pn = route.map("policy");
            type = pn.string("type", type);
            options = pn.stringMap("options");
            pn.rejectUnknown();
        } else {
            type = route.string("policy", type);
        }
        ConfigNode at = pn != null ? pn : route;
        String where = pn != null ? "type" : "policy";
        var factory = policies.find(type);
        if (factory.isEmpty()) {
            at.error(where, "unknown routing policy '" + type + "'; available: " + policies.ids());
        } else {
            RoutingPolicyFactory f = factory.get();
            for (String problem : f.validate(options)) {
                at.error(pn != null ? "options" : "policy", "policy '" + type + "': " + problem);
            }
        }
        return new PolicyConfig(type, Map.copyOf(options));
    }

    private FallbackConfig fallback(ConfigNode.Ctx ctx, ConfigNode route, List<UpstreamConfig> primaries) {
        ConfigNode n = route.map("fallback");
        if (n == null) {
            return null;
        }
        List<UpstreamConfig> ups = upstreams(ctx, n, "upstreams", false);
        StaticResponse resp = null;
        ConfigNode rn = n.map("response");
        if (rn != null) {
            int status = rn.integer("status", 503, 200, 599);
            String body = rn.string("body", "");
            String ct = rn.string("contentType", "text/plain; charset=utf-8");
            if (body.length() > 4096) {
                rn.error("body", "must be at most 4096 characters");
            }
            if (ct.indexOf('\r') >= 0 || ct.indexOf('\n') >= 0) {
                rn.error("contentType", "must not contain line breaks");
            }
            rn.rejectUnknown();
            resp = new StaticResponse(status, body, ct);
        }
        if (ups.isEmpty() && resp == null) {
            n.errorHere("a fallback needs upstreams and/or a static response");
        }
        Set<String> primaryIds = new HashSet<>();
        primaries.forEach(p -> primaryIds.add(p.id()));
        for (UpstreamConfig u : ups) {
            if (primaryIds.contains(u.id())) {
                n.error("upstreams", "fallback upstream id '" + u.id() + "' duplicates a primary upstream id");
            }
        }
        n.rejectUnknown();
        return new FallbackConfig(List.copyOf(ups), resp);
    }

    private TimeoutConfig timeouts(ConfigNode n) {
        Duration connect = n.duration("connect", Duration.ofSeconds(1), Duration.ofMillis(10), Duration.ofSeconds(60));
        Duration response = n.duration("response", Duration.ofSeconds(10), Duration.ofMillis(10), Duration.ofMinutes(10));
        Duration request = n.duration("request", Duration.ofSeconds(30), Duration.ofMillis(10), Duration.ofHours(1));
        if (response.compareTo(request) > 0) {
            n.error("response", "must not exceed timeouts.request (" + dev.routeplane.util.Durations.format(request) + ")");
        }
        if (connect.compareTo(request) > 0) {
            n.error("connect", "must not exceed timeouts.request (" + dev.routeplane.util.Durations.format(request) + ")");
        }
        n.rejectUnknown();
        return new TimeoutConfig(connect, response, request);
    }

    private RetryConfig retry(ConfigNode n) {
        int maxAttempts = n.integer("maxAttempts", 1, 1, 10);
        Set<String> methods = methods(n, "methods", Set.of("GET", "HEAD", "OPTIONS"));
        Set<Integer> statuses = new LinkedHashSet<>();
        if (n.has("retryStatuses")) {
            for (String s : n.strings("retryStatuses")) {
                try {
                    int c = Integer.parseInt(s);
                    if (c < 100 || c > 599) {
                        throw new NumberFormatException();
                    }
                    statuses.add(c);
                } catch (NumberFormatException e) {
                    n.error("retryStatuses", "'" + s + "' is not an HTTP status code (100-599)");
                }
            }
        } else {
            statuses.addAll(List.of(502, 503, 504));
        }
        boolean onTimeout = n.bool("retryOnTimeout", true);
        ConfigNode b = section(n.ctxFor(), n, "backoff");
        Duration initial = b.duration("initial", Duration.ofMillis(25), Duration.ofMillis(1), Duration.ofSeconds(10));
        Duration max = b.duration("max", Duration.ofSeconds(1), Duration.ofMillis(1), Duration.ofSeconds(60));
        double multiplier = b.number("multiplier", 2.0, 1.0, 10.0);
        JitterMode jitter = b.enumValue("jitter", JitterMode.class, JitterMode.FULL);
        if (initial.compareTo(max) > 0) {
            b.error("initial", "must not exceed backoff.max");
        }
        b.rejectUnknown();
        ConfigNode bud = section(n.ctxFor(), n, "budget");
        double ratio = bud.number("ratio", 0.2, 0.0, 1.0);
        double minPerSecond = bud.number("minRetriesPerSecond", 5.0, 0.0, 10_000.0);
        Duration window = bud.duration("window", Duration.ofSeconds(10), Duration.ofSeconds(1), Duration.ofMinutes(5));
        bud.rejectUnknown();
        n.rejectUnknown();
        return new RetryConfig(maxAttempts, methods, Set.copyOf(statuses), onTimeout, initial, max, multiplier, jitter, ratio, minPerSecond, window);
    }

    private BreakerConfig breaker(ConfigNode n) {
        if (n == null) {
            return null;
        }
        double threshold = n.number("failureRateThreshold", 50.0, 1.0, 100.0);
        int minimum = n.integer("minimumRequests", 20, 1, 100_000);
        ConfigNode w = section(n.ctxFor(), n, "slidingWindow");
        WindowType type = w.enumValue("type", WindowType.class, WindowType.COUNT);
        int count = w.integer("size", 100, 1, 100_000);
        Duration duration = w.duration("duration", Duration.ofSeconds(10), Duration.ofSeconds(1), Duration.ofMinutes(10));
        if (type == WindowType.COUNT && count < minimum) {
            w.error("size", "must be at least minimumRequests (" + minimum + ") or the breaker could never open");
        }
        if (type == WindowType.TIME && duration.toMillis() % 1000 != 0) {
            w.error("duration", "must be a whole number of seconds");
        }
        w.rejectUnknown();
        Duration open = n.duration("openDuration", Duration.ofSeconds(10), Duration.ofMillis(100), Duration.ofHours(1));
        int probes = n.integer("halfOpenMaxProbes", 3, 1, 1000);
        int recovery = n.integer("recoverySuccessThreshold", 3, 1, 1000);
        BitSet statuses = statusSet(n, "failureStatuses", List.of("500-599"));
        boolean timeouts = n.bool("countTimeoutsAsFailures", true);
        n.rejectUnknown();
        return new BreakerConfig(threshold, minimum, type, count, duration, open, probes, recovery, statuses, timeouts);
    }

    private BitSet statusSet(ConfigNode n, String key, List<String> def) {
        List<String> items = n.has(key) ? n.strings(key) : def;
        BitSet bits = new BitSet(600);
        for (String item : items) {
            try {
                int dash = item.indexOf('-');
                int lo = Integer.parseInt(dash < 0 ? item : item.substring(0, dash).trim());
                int hi = dash < 0 ? lo : Integer.parseInt(item.substring(dash + 1).trim());
                if (lo < 100 || hi > 599 || lo > hi) {
                    throw new NumberFormatException();
                }
                bits.set(lo, hi + 1);
            } catch (NumberFormatException e) {
                n.error(key, "'" + item + "' is not a status code or range such as 500-599 (100-599)");
            }
        }
        return bits;
    }

    private HealthConfig health(ConfigNode n) {
        if (n == null) {
            return null;
        }
        ActiveHealth active = null;
        ConfigNode a = n.map("active");
        if (a != null) {
            String path = a.requireString("path");
            if (!path.isEmpty() && (!path.startsWith("/") || path.chars().anyMatch(c -> c <= 0x20 || c == 0x7F))) {
                a.error("path", "must start with '/' and contain no whitespace");
            }
            Duration interval = a.duration("interval", Duration.ofSeconds(5), Duration.ofMillis(100), Duration.ofMinutes(10));
            Duration timeout = a.duration("timeout", Duration.ofSeconds(1), Duration.ofMillis(50), Duration.ofMinutes(1));
            if (timeout.compareTo(interval) > 0) {
                a.error("timeout", "must not exceed interval");
            }
            int unhealthy = a.integer("unhealthyThreshold", 3, 1, 100);
            int healthy = a.integer("healthyThreshold", 2, 1, 100);
            BitSet expected = statusSet(a, "expectStatus", List.of("200-399"));
            a.rejectUnknown();
            active = new ActiveHealth(path, interval, timeout, unhealthy, healthy, expected);
        }
        PassiveHealth passive = null;
        ConfigNode p = n.map("passive");
        if (p != null) {
            int failures = p.integer("consecutiveFailures", 5, 1, 1000);
            Duration eject = p.duration("ejectionTime", Duration.ofSeconds(30), Duration.ofMillis(100), Duration.ofHours(1));
            p.rejectUnknown();
            passive = new PassiveHealth(failures, eject);
        }
        if (active == null && passive == null) {
            n.errorHere("health needs an 'active' and/or a 'passive' section");
        }
        n.rejectUnknown();
        return new HealthConfig(active, passive);
    }

    // -------------------------------------------------------------------------------------------- shadowing

    private ShadowConfig shadow(ConfigNode.Ctx ctx, ConfigNode n, List<UpstreamConfig> primaries, ServerConfig server) {
        if (n == null) {
            return null;
        }
        Set<String> methods = methods(n, "methods", SAFE_METHODS);
        boolean unsafe = n.bool("allowUnsafeMethods", false);
        for (String m : methods) {
            if (!SAFE_METHODS.contains(m) && !unsafe) {
                n.error("methods", m + " is not a safe method; mirroring it can duplicate side effects. Set allowUnsafeMethods: true only if every shadow target is a safe sandbox");
            }
        }
        int maxBody = n.integer("maxBodyBytes", 64 * 1024, 0, 16 << 20);
        if (maxBody > server.maxBufferedBodyBytes()) {
            n.error("maxBodyBytes", "must not exceed server.maxBufferedBodyBytes (" + server.maxBufferedBodyBytes() + ") because shadowed bodies are buffered");
        }
        int maxResponse = n.integer("maxResponseBytes", 1 << 20, 1024, 64 << 20);
        Set<String> strip = new LinkedHashSet<>();
        Set<String> allow = new LinkedHashSet<>();
        Map<String, String> set = new LinkedHashMap<>();
        ConfigNode h = n.map("headers");
        if (h != null) {
            h.strings("strip").forEach(s -> addHeaderName(h, "strip", s, strip));
            h.strings("allow").forEach(s -> addHeaderName(h, "allow", s, allow));
            for (Map.Entry<String, String> e : h.stringMap("set").entrySet()) {
                if (!HEADER_NAME.matcher(e.getKey()).matches() || e.getValue().indexOf('\r') >= 0 || e.getValue().indexOf('\n') >= 0) {
                    h.error("set", "'" + e.getKey() + "' is not a valid header");
                } else {
                    set.put(e.getKey(), e.getValue());
                }
            }
            h.rejectUnknown();
        }
        CompareConfig compare = new CompareConfig(false, true, Set.of());
        ConfigNode c = n.map("compare");
        if (c != null) {
            boolean enabled = c.bool("enabled", true);
            boolean body = c.bool("body", true);
            Set<String> hs = new LinkedHashSet<>();
            c.strings("headers").forEach(s -> addHeaderName(c, "headers", s, hs));
            c.rejectUnknown();
            compare = new CompareConfig(enabled, body, hs);
        }
        List<ShadowTarget> targets = new ArrayList<>();
        List<ConfigNode> tnodes = n.mapList("targets");
        if (tnodes.isEmpty()) {
            n.error("targets", "at least one shadow target is required");
        }
        Set<String> ids = new HashSet<>();
        for (ConfigNode tn : tnodes) {
            String id = tn.requireString("id");
            if (!id.isEmpty() && !ID.matcher(id).matches()) {
                tn.error("id", "must match " + ID.pattern());
            }
            if (!id.isEmpty() && !ids.add(id)) {
                tn.error("id", "duplicate shadow target id '" + id + "'");
            }
            URI url = url(tn, "url");
            if (!tn.has("samplePercent")) {
                tn.errorHere("missing required key 'samplePercent' (shadowing is opt-in; choose the fraction of traffic explicitly)");
            }
            double pct = tn.number("samplePercent", 0.0, 0.0, 100.0);
            SamplingMode sampling = tn.enumValue("sampling", SamplingMode.class, SamplingMode.RANDOM);
            String key = tn.string("sampleKey", "request-id");
            if (!validKey(key)) {
                tn.error("sampleKey", "must be header:<name>, cookie:<name>, query:<name>, path, client-ip or request-id");
            }
            Duration connect = tn.duration("connectTimeout", Duration.ofSeconds(1), Duration.ofMillis(10), Duration.ofSeconds(60));
            Duration timeout = tn.duration("timeout", Duration.ofSeconds(5), Duration.ofMillis(10), Duration.ofMinutes(5));
            int maxConcurrent = tn.integer("maxConcurrent", 16, 1, 10_000);
            int queue = tn.integer("queueCapacity", 256, 1, 100_000);
            Duration wait = tn.duration("queueMaxWait", timeout, Duration.ofMillis(1), Duration.ofMinutes(5));
            tn.rejectUnknown();
            if (url != null) {
                for (UpstreamConfig p : primaries) {
                    if (p.url() != null && p.url().equals(url)) {
                        tn.error("url", "shadow target must not be the same URL as primary upstream '" + p.id() + "'");
                    }
                }
            }
            targets.add(new ShadowTarget(id, url, pct, sampling, key, connect, timeout, maxConcurrent, queue, wait));
        }
        n.rejectUnknown();
        return new ShadowConfig(List.copyOf(targets), methods, unsafe, maxBody, maxResponse, Set.copyOf(strip), Set.copyOf(allow), Map.copyOf(set), compare);
    }

    private static boolean validKey(String key) {
        return key.equals("path") || key.equals("client-ip") || key.equals("request-id")
                || ((key.startsWith("header:") || key.startsWith("cookie:") || key.startsWith("query:")) && key.indexOf(':') < key.length() - 1);
    }

    private void addHeaderName(ConfigNode n, String key, String name, Set<String> into) {
        if (!HEADER_NAME.matcher(name).matches()) {
            n.error(key, "'" + name + "' is not a valid header name");
        } else {
            into.add(name.toLowerCase(Locale.ROOT));
        }
    }
}
