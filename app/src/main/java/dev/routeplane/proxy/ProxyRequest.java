package dev.routeplane.proxy;

import com.sun.net.httpserver.HttpExchange;
import dev.routeplane.routing.QueryString;
import dev.routeplane.routing.RoutingRequest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** A validated incoming request: the exchange plus everything derived from it exactly once. */
final class ProxyRequest implements RoutingRequest {
    final HttpExchange exchange;
    final String requestId;
    final String method;
    /** Normalised path (see PathNormalizer); this is what is matched <em>and</em> forwarded. */
    final String path;
    /** Raw query string without '?', or null. */
    final String rawQuery;
    final String clientAddress;
    /** The validated Host header value, or null (HTTP/1.0 without Host). */
    final String hostHeader;
    final boolean head;
    /** Declared body length, or -1 when there is none or it is chunked. */
    final long contentLength;
    final boolean chunked;
    final long startNanos;

    /** Set when the body is consumed through a guard; null while the body has not been touched. */
    volatile GuardedInputStream bodyGuard;

    private Map<String, List<String>> queryParams;
    private Map<String, String> cookies;

    ProxyRequest(HttpExchange exchange, String requestId, String method, String path, String rawQuery, String clientAddress,
                 String hostHeader, long contentLength, boolean chunked, long startNanos) {
        this.exchange = exchange;
        this.requestId = requestId;
        this.method = method;
        this.path = path;
        this.rawQuery = rawQuery;
        this.clientAddress = clientAddress;
        this.hostHeader = hostHeader;
        this.head = method.equals("HEAD");
        this.contentLength = contentLength;
        this.chunked = chunked;
        this.startNanos = startNanos;
    }

    boolean hasBody() {
        return chunked || contentLength > 0;
    }

    /**
     * True if the client announced a body that has not been read completely. The JDK server drains such a body with an
     * unbounded blocking read when the exchange completes, which a stalled client could abuse; so the connection is
     * aborted instead (after the response has been flushed).
     */
    boolean bodyUnread() {
        return hasBody() && (bodyGuard == null || !bodyGuard.exhausted());
    }

    String pathAndQuery() {
        return rawQuery == null ? path : path + "?" + rawQuery;
    }

    @Override
    public String method() {
        return method;
    }

    @Override
    public String path() {
        return path;
    }

    @Override
    public String query() {
        return rawQuery;
    }

    @Override
    public String header(String name) {
        return exchange.getRequestHeaders().getFirst(name);
    }

    @Override
    public String queryParam(String name) {
        if (queryParams == null) {
            queryParams = QueryString.parse(rawQuery);
        }
        List<String> v = queryParams.get(name);
        return v == null || v.isEmpty() ? null : v.get(0);
    }

    @Override
    public String cookie(String name) {
        if (cookies == null) {
            Map<String, String> m = new HashMap<>();
            List<String> values = exchange.getRequestHeaders().get("Cookie");
            if (values != null) {
                for (String line : values) {
                    for (String part : line.split(";")) {
                        int eq = part.indexOf('=');
                        if (eq > 0) {
                            m.putIfAbsent(part.substring(0, eq).trim(), part.substring(eq + 1).trim());
                        }
                    }
                }
            }
            cookies = m;
        }
        return cookies.get(name);
    }

    @Override
    public String clientAddress() {
        return clientAddress;
    }

    @Override
    public String requestId() {
        return requestId;
    }
}
