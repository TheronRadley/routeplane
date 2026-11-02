package dev.routeplane.proxy;

import dev.routeplane.config.RouteplaneConfig.ServerConfig;
import dev.routeplane.observability.StructuredLogger;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The JDK HTTP server is tuned through JVM-wide system properties that are read once, when the first server is created
 * (ADR-0001). This class derives them from {@code server.*} and sets them <em>before</em> any server exists. A property
 * that the operator already set with {@code -D} is never overridden. Because the values are frozen for the life of the
 * JVM, changing the corresponding settings requires a restart (configuration reload rejects such changes).
 */
public final class JdkTuning {
    private JdkTuning() {
    }

    /** @return the properties that were applied (for logging/tests); already-set properties are left untouched */
    public static Map<String, String> apply(ServerConfig cfg, StructuredLogger log) {
        Map<String, String> wanted = new LinkedHashMap<>();
        // The hard limit sits above the soft limit enforced (with a 431 response) by RequestValidator, because at the
        // JDK limit the connection is dropped without any response.
        wanted.put("sun.net.httpserver.maxReqHeaderSize", Integer.toString((int) Math.min(1 << 20, Math.max(cfg.maxHeaderBytes() * 2L, cfg.maxHeaderBytes() + 8192L))));
        wanted.put("sun.net.httpserver.maxReqTime", Long.toString(Math.max(1, cfg.requestHeaderTimeout().toSeconds())));
        wanted.put("sun.net.httpserver.maxRspTime", Long.toString(Math.max(1, cfg.maxResponseTime().toSeconds())));
        wanted.put("sun.net.httpserver.idleInterval", Long.toString(Math.max(1, cfg.idleTimeout().toSeconds())));
        wanted.put("sun.net.httpserver.nodelay", "true");
        wanted.put("jdk.httpserver.maxConnections", Integer.toString(cfg.maxConnections()));
        Map<String, String> applied = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : wanted.entrySet()) {
            if (System.getProperty(e.getKey()) == null) {
                System.setProperty(e.getKey(), e.getValue());
                applied.put(e.getKey(), e.getValue());
            } else if (log != null) {
                log.info("jdk_property_preset", "property", e.getKey(), "value", System.getProperty(e.getKey()), "configured", e.getValue());
            }
        }
        return applied;
    }

    /** True when {@code Host} may be set on upstream requests (needed for {@code hostHeader: preserve}). */
    public static boolean hostHeaderAllowed() {
        String v = System.getProperty("jdk.httpclient.allowRestrictedHeaders");
        if (v == null) {
            return false;
        }
        for (String t : v.split(",")) {
            if (t.trim().equalsIgnoreCase("host")) {
                return true;
            }
        }
        return false;
    }
}
