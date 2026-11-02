package dev.routeplane.proxy;

import com.sun.net.httpserver.HttpExchange;
import dev.routeplane.observability.Json;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Writes proxy-generated responses. They always carry {@code X-Routeplane-Error}, which upstream responses never can. */
final class ProxyErrors {
    private ProxyErrors() {
    }

    static void send(HttpExchange ex, int status, FailureKind kind, String message, String requestId, String requestIdHeader,
                     boolean closeConnection, boolean head, String retryAfterSeconds) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", kind.code());
        body.put("message", message);
        body.put("requestId", requestId);
        byte[] bytes = Json.write(body).getBytes(StandardCharsets.UTF_8);
        var h = ex.getResponseHeaders();
        h.set("Content-Type", "application/json");
        h.set("X-Routeplane-Error", kind.code());
        if (requestId != null) {
            h.set(requestIdHeader, requestId);
        }
        if (closeConnection) {
            h.set("Connection", "close");
        }
        if (retryAfterSeconds != null) {
            h.set("Retry-After", retryAfterSeconds);
        }
        if (head) {
            h.set("Content-Length", Integer.toString(bytes.length));
            ex.sendResponseHeaders(status, -1);
            return;
        }
        ex.sendResponseHeaders(status, bytes.length);
        OutputStream os = ex.getResponseBody();
        os.write(bytes);
        os.flush();
        // the caller completes the exchange (normal close or abort) because only it knows whether the request body is unread
    }
}
