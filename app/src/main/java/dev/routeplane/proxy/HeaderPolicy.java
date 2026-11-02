package dev.routeplane.proxy;

import com.sun.net.httpserver.Headers;
import dev.routeplane.config.RouteplaneConfig.ForwardingConfig;
import dev.routeplane.config.RouteplaneConfig.ForwardingMode;
import dev.routeplane.config.RouteplaneConfig.HostHeaderMode;
import dev.routeplane.config.RouteplaneConfig.ServerConfig;
import dev.routeplane.util.Cidr;
import java.net.InetAddress;
import java.net.http.HttpHeaders;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The single place that decides which headers cross the proxy. Policy (documented in docs/security.md):
 *
 * <ul>
 *   <li><b>Hop-by-hop headers</b> ({@code Connection}, {@code Keep-Alive}, {@code Proxy-*}, {@code TE},
 *       {@code Trailer}, {@code Transfer-Encoding}, {@code Upgrade}) and every header named in {@code Connection} are
 *       removed in both directions. Message framing ({@code Content-Length}, chunking) is always re-derived.</li>
 *   <li><b>Client-supplied forwarding headers are never trusted</b>: {@code Forwarded}, {@code X-Forwarded-*} and
 *       {@code X-Real-IP} are dropped and recomputed from the TCP peer, unless the peer is a configured trusted proxy
 *       and the mode is {@code trusted-append}.</li>
 *   <li>{@code Host} sent upstream is the upstream's own authority by default; the original is passed in
 *       {@code X-Forwarded-Host}. {@code hostHeader: preserve} forwards the validated client value.</li>
 *   <li>{@code X-Routeplane-*} is reserved: stripped from requests and from upstream responses so that neither side
 *       can forge proxy-generated markers.</li>
 *   <li>A {@code Via} entry identifies this proxy and doubles as loop detection.</li>
 * </ul>
 */
final class HeaderPolicy {
    static final Set<String> HOP_BY_HOP = Set.of("connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "proxy-connection", "te", "trailer", "transfer-encoding", "upgrade");
    private static final Pattern XFF_OK = Pattern.compile("^[0-9A-Fa-f:.,\\[\\] ]{1,512}$|^unknown$");
    private static final Pattern HOST_OK = Pattern.compile("^[A-Za-z0-9.\\-\\[\\]:]{1,255}$");

    private final ServerConfig server;
    private final ForwardingConfig forwarding;
    private final String viaToken;
    private final String requestIdHeaderLower;

    HeaderPolicy(ServerConfig server, String viaToken) {
        this.server = server;
        this.forwarding = server.forwarding();
        this.viaToken = viaToken;
        this.requestIdHeaderLower = server.requestIdHeader().toLowerCase(Locale.ROOT);
    }

    /** Names listed in any {@code Connection} header value, lower-cased. */
    static Set<String> connectionTokens(List<String> connectionValues) {
        Set<String> out = new HashSet<>();
        if (connectionValues != null) {
            for (String v : connectionValues) {
                for (String t : v.split(",")) {
                    String s = t.trim().toLowerCase(Locale.ROOT);
                    if (!s.isEmpty()) {
                        out.add(s);
                    }
                }
            }
        }
        return out;
    }

    /** Headers for the upstream request, as ordered name/value pairs (a name may repeat). */
    List<String[]> upstreamRequestHeaders(ProxyRequest req) {
        Headers in = req.exchange.getRequestHeaders();
        Set<String> conn = connectionTokens(in.get("Connection"));
        List<String[]> out = new ArrayList<>(in.size() + 6);
        boolean hasUserAgent = false;
        for (Map.Entry<String, List<String>> e : in.entrySet()) {
            String ln = e.getKey().toLowerCase(Locale.ROOT);
            if (HOP_BY_HOP.contains(ln) || conn.contains(ln) || ln.equals("host") || ln.equals("content-length") || ln.equals("expect")
                    || ln.equals("forwarded") || ln.startsWith("x-forwarded-") || ln.equals("x-real-ip") || ln.startsWith("x-routeplane-")
                    || ln.equals(requestIdHeaderLower) || ln.equals("via")) {
                continue;
            }
            if (ln.equals("user-agent")) {
                hasUserAgent = true;
            }
            for (String v : e.getValue()) {
                out.add(new String[] {e.getKey(), v});
            }
        }
        if (!hasUserAgent) {
            out.add(new String[] {"User-Agent", ""}); // the JDK client would otherwise inject "Java-http-client/<version>"
        }
        List<String> via = in.get("Via");
        String ours = "1.1 " + viaToken;
        out.add(new String[] {"Via", via == null || via.isEmpty() ? ours : String.join(", ", via) + ", " + ours});
        out.add(new String[] {server.requestIdHeader(), req.requestId});
        addForwarding(req, in, out);
        if (server.hostHeader() == HostHeaderMode.PRESERVE && req.hostHeader != null) {
            out.add(new String[] {"Host", req.hostHeader});
        }
        return out;
    }

    private void addForwarding(ProxyRequest req, Headers in, List<String[]> out) {
        if (forwarding.mode() == ForwardingMode.OFF) {
            return;
        }
        String peer = req.clientAddress;
        String xff = peer;
        String proto = forwarding.proto();
        String host = req.hostHeader;
        if (forwarding.mode() == ForwardingMode.TRUSTED_APPEND && isTrusted(req)) {
            String incoming = join(in.get("X-Forwarded-For"));
            if (incoming != null && XFF_OK.matcher(incoming).matches()) {
                xff = incoming + ", " + peer;
            }
            String p = in.getFirst("X-Forwarded-Proto");
            if (p != null && (p.equals("http") || p.equals("https"))) {
                proto = p;
            }
            String h = in.getFirst("X-Forwarded-Host");
            if (h != null && HOST_OK.matcher(h).matches()) {
                host = h;
            }
        }
        out.add(new String[] {"X-Forwarded-For", xff});
        out.add(new String[] {"X-Forwarded-Proto", proto});
        if (host != null) {
            out.add(new String[] {"X-Forwarded-Host", host});
        }
        if (forwarding.emitForwardedHeader()) {
            StringBuilder f = new StringBuilder("for=").append(quoteNode(peer)).append(";proto=").append(proto);
            if (host != null) {
                f.append(";host=\"").append(host.replace("\"", "")).append('"');
            }
            out.add(new String[] {"Forwarded", f.toString()});
        }
    }

    private boolean isTrusted(ProxyRequest req) {
        InetAddress a = req.exchange.getRemoteAddress().getAddress();
        for (Cidr c : forwarding.trustedProxies()) {
            if (c.contains(a)) {
                return true;
            }
        }
        return false;
    }

    private static String quoteNode(String ip) {
        return ip.indexOf(':') >= 0 ? "\"[" + ip + "]\"" : ip;
    }

    private static String join(List<String> values) {
        return values == null || values.isEmpty() ? null : String.join(", ", values);
    }

    /** Copies upstream response headers to the client response, dropping hop-by-hop and reserved headers. */
    void copyResponseHeaders(HttpHeaders upstream, Headers client, ProxyRequest req) {
        Set<String> conn = connectionTokens(upstream.allValues("connection"));
        for (Map.Entry<String, List<String>> e : upstream.map().entrySet()) {
            String ln = e.getKey().toLowerCase(Locale.ROOT);
            if (HOP_BY_HOP.contains(ln) || conn.contains(ln) || ln.equals("content-length") || ln.startsWith("x-routeplane-")
                    || ln.equals(requestIdHeaderLower) || ln.equals("via") || ln.startsWith(":")) {
                continue;
            }
            for (String v : e.getValue()) {
                client.add(e.getKey(), v);
            }
        }
        List<String> via = upstream.allValues("via");
        String ours = "1.1 " + viaToken;
        client.set("Via", via.isEmpty() ? ours : String.join(", ", via) + ", " + ours);
        client.set(server.requestIdHeader(), req.requestId);
    }
}
