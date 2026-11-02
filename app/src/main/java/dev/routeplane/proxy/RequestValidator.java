package dev.routeplane.proxy;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import dev.routeplane.config.RouteplaneConfig;
import dev.routeplane.config.RouteplaneConfig.ServerConfig;
import dev.routeplane.routing.PathNormalizer;
import dev.routeplane.routing.QueryString;
import java.net.InetAddress;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * Hardens the request the JDK server hands us. The JDK parser is deliberately lenient in places (see ADR-0001), so
 * everything that matters for request-smuggling, SSRF and cache-poisoning defence is re-checked here before the
 * request is routed. A rejected request always gets a response with {@code Connection: close} when its framing is in
 * doubt, so a poisoned connection can never be reused.
 */
final class RequestValidator {
    /** Why a request was refused. */
    record Rejection(FailureKind kind, int status, String message, boolean closeConnection) {
    }

    /** Either a request or a rejection. */
    record Result(ProxyRequest request, Rejection rejection) {
        boolean ok() {
            return request != null;
        }
    }

    private static final Pattern TOKEN = Pattern.compile("^[!#$%&'*+\\-.^_`|~0-9A-Za-z]+$");
    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("^[A-Za-z0-9._:\\-]{1,128}$");
    private static final Pattern CONTENT_LENGTH = Pattern.compile("^[0-9]{1,18}$");
    private static final int MAX_TARGET_CHARS = 8192;
    private static final int MAX_HEADER_COUNT = 100;

    private final ServerConfig server;
    private final String viaToken;

    RequestValidator(ServerConfig server, String viaToken) {
        this.server = server;
        this.viaToken = viaToken;
    }

    Result validate(HttpExchange ex, long startNanos) {
        String protocol = ex.getProtocol();
        boolean http10 = "HTTP/1.0".equals(protocol);
        if (!http10 && !"HTTP/1.1".equals(protocol)) {
            return reject(FailureKind.INVALID_REQUEST, 505, "HTTP version not supported", true);
        }
        String method = ex.getRequestMethod();
        if (!TOKEN.matcher(method).matches() || !method.equals(method.toUpperCase(Locale.ROOT))) {
            return reject(FailureKind.INVALID_REQUEST, 400, "malformed request method", true);
        }
        if (!RouteplaneConfig.SUPPORTED_METHODS.contains(method)) {
            return reject(FailureKind.INVALID_REQUEST, method.equals("TRACE") || method.equals("CONNECT") ? 405 : 501,
                    "method " + method + " is not supported", true);
        }
        URI uri = ex.getRequestURI();
        if (uri.getScheme() != null || uri.getRawAuthority() != null || uri.getRawFragment() != null) {
            return reject(FailureKind.INVALID_REQUEST, 400, "only origin-form request targets are accepted (no scheme or authority)", true);
        }
        String rawPath = uri.getRawPath();
        String rawQuery = uri.getRawQuery();
        if (rawPath == null || rawPath.length() > MAX_TARGET_CHARS || (rawQuery != null && rawQuery.length() > MAX_TARGET_CHARS)) {
            return reject(FailureKind.INVALID_REQUEST, 414, "request target too long", true);
        }
        PathNormalizer.Result p = PathNormalizer.normalize(rawPath, server.allowEncodedSlashes());
        if (!p.ok()) {
            return reject(FailureKind.INVALID_REQUEST, 400, "invalid request path: " + p.error(), true);
        }
        if (rawQuery != null && !QueryString.isWellFormed(rawQuery)) {
            return reject(FailureKind.INVALID_REQUEST, 400, "malformed query string", true);
        }
        try {
            URI.create("http://placeholder" + p.path() + (rawQuery == null ? "" : "?" + rawQuery));
        } catch (IllegalArgumentException e) {
            return reject(FailureKind.INVALID_REQUEST, 400, "request target contains characters that are not valid in a URI", true);
        }

        Headers h = ex.getRequestHeaders();
        if (h.size() > MAX_HEADER_COUNT) {
            return reject(FailureKind.INVALID_REQUEST, 431, "too many request headers", true);
        }
        long headerBytes = method.length() + rawPath.length() + (rawQuery == null ? 0 : rawQuery.length()) + 12L;
        for (Map.Entry<String, List<String>> e : h.entrySet()) {
            if (!TOKEN.matcher(e.getKey()).matches()) {
                return reject(FailureKind.INVALID_REQUEST, 400, "malformed header name", true);
            }
            for (String v : e.getValue()) {
                headerBytes += e.getKey().length() + v.length() + 4L;
                for (int i = 0; i < v.length(); i++) {
                    char c = v.charAt(i);
                    if ((c < 0x20 && c != '\t') || c == 0x7F) {
                        return reject(FailureKind.INVALID_REQUEST, 400, "control character in header '" + e.getKey() + "'", true);
                    }
                }
            }
        }
        if (headerBytes > server.maxHeaderBytes()) {
            return reject(FailureKind.INVALID_REQUEST, 431, "request headers exceed " + server.maxHeaderBytes() + " bytes", true);
        }

        List<String> hosts = h.get("Host");
        String host = null;
        if (hosts != null) {
            if (hosts.size() != 1) {
                return reject(FailureKind.INVALID_REQUEST, 400, "exactly one Host header is required", true);
            }
            host = hosts.get(0).trim();
            if (host.isEmpty() || host.length() > 255 || host.chars().anyMatch(c -> c <= 0x20 || c == '/' || c == '@' || c == '\\')) {
                return reject(FailureKind.INVALID_REQUEST, 400, "malformed Host header", true);
            }
        } else if (!http10) {
            return reject(FailureKind.INVALID_REQUEST, 400, "HTTP/1.1 requests must carry a Host header", true);
        }

        long contentLength = -1;
        List<String> cls = h.get("Content-Length");
        List<String> tes = h.get("Transfer-Encoding");
        boolean chunked = false;
        if (tes != null) {
            if (cls != null) {
                return reject(FailureKind.INVALID_REQUEST, 400, "Content-Length and Transfer-Encoding must not both be present", true);
            }
            if (tes.size() != 1 || !tes.get(0).trim().equalsIgnoreCase("chunked")) {
                return reject(FailureKind.INVALID_REQUEST, 501, "unsupported Transfer-Encoding", true);
            }
            chunked = true;
        }
        if (cls != null) {
            if (cls.size() != 1 || !CONTENT_LENGTH.matcher(cls.get(0).trim()).matches()) {
                return reject(FailureKind.INVALID_REQUEST, 400, "invalid Content-Length", true);
            }
            contentLength = Long.parseLong(cls.get(0).trim());
            if (contentLength > server.maxRequestBodyBytes()) {
                return reject(FailureKind.BODY_TOO_LARGE, 413, "request body exceeds " + server.maxRequestBodyBytes() + " bytes", true);
            }
        }
        if (contentLength == 0) {
            contentLength = -1;
        }
        List<String> via = h.get("Via");
        if (via != null && via.stream().mapToInt(v -> countOccurrences(v, viaToken)).sum() >= 2) {
            return new Result(null, new Rejection(FailureKind.INVALID_REQUEST, 508, "forwarding loop detected", true));
        }

        String id = h.getFirst(server.requestIdHeader());
        if (id == null || !SAFE_REQUEST_ID.matcher(id).matches()) {
            id = newRequestId();
        }
        return new Result(new ProxyRequest(ex, id, method, p.path(), rawQuery, clientAddress(ex), host, contentLength, chunked, startNanos), null);
    }

    static String clientAddress(HttpExchange ex) {
        InetAddress a = ex.getRemoteAddress().getAddress();
        String s = a.getHostAddress();
        int zone = s.indexOf('%');
        return zone < 0 ? s : s.substring(0, zone);
    }

    static String newRequestId() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        return String.format("%016x%016x", r.nextLong(), r.nextLong());
    }

    private static int countOccurrences(String s, String token) {
        int n = 0;
        int i = 0;
        while ((i = s.indexOf(token, i)) >= 0) {
            n++;
            i += token.length();
        }
        return n;
    }

    private static Result reject(FailureKind kind, int status, String message, boolean close) {
        return new Result(null, new Rejection(kind, status, message, close));
    }
}
