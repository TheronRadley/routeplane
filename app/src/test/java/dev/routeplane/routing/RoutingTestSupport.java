package dev.routeplane.routing;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Builders for network-free routing tests. */
final class RoutingTestSupport {
    private RoutingTestSupport() {
    }

    static final class Req implements RoutingRequest {
        String method = "GET";
        String path = "/";
        String query;
        String client = "198.51.100.7";
        String id = "req-1";
        final Map<String, String> headers = new HashMap<>();
        final Map<String, String> cookies = new HashMap<>();

        Req method(String m) {
            method = m;
            return this;
        }

        Req path(String p) {
            path = p;
            return this;
        }

        Req query(String q) {
            query = q;
            return this;
        }

        Req header(String n, String v) {
            headers.put(n.toLowerCase(Locale.ROOT), v);
            return this;
        }

        Req cookie(String n, String v) {
            cookies.put(n, v);
            return this;
        }

        Req client(String c) {
            client = c;
            return this;
        }

        Req id(String i) {
            id = i;
            return this;
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
            return query;
        }

        @Override
        public String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }

        @Override
        public String queryParam(String name) {
            List<String> v = QueryString.parse(query).get(name);
            return v == null || v.isEmpty() ? null : v.get(0);
        }

        @Override
        public String cookie(String name) {
            return cookies.get(name);
        }

        @Override
        public String clientAddress() {
            return client;
        }

        @Override
        public String requestId() {
            return id;
        }
    }

    static final class Up implements Candidate {
        final String id;
        final int weight;
        int active;

        Up(String id, int weight) {
            this.id = id;
            this.weight = weight;
        }

        Up active(int a) {
            active = a;
            return this;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public int weight() {
            return weight;
        }

        @Override
        public int activeRequests() {
            return active;
        }

        @Override
        public String toString() {
            return id;
        }
    }
}
