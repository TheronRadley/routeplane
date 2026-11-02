package dev.routeplane.fault;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;

/**
 * Test-only upstream that misbehaves on demand: delays, error statuses, connection resets, early closes, truncated
 * and throttled bodies, capacity limits, refusal (the listener goes away), gradual recovery and arbitrary payload sizes.
 *
 * <p><b>This is test tooling, not an HTTP server.</b> Protocol-level faults (RST mid-exchange, a body shorter than its
 * declared length, byte-at-a-time streaming) are exactly what a conforming server library refuses to do, so this class
 * speaks a deliberately minimal subset of HTTP/1.1 over plain sockets: it reads the request head and drains
 * {@code Content-Length} or chunked bodies, and nothing more. It must only be exposed to trusted peers on loopback;
 * the control API (separate port, loopback by default) can change its behaviour and is never served on the data port.
 *
 * <p>Determinism: every request draws once from a {@link SplittableRandom} keyed by {@code seed} and the request's
 * arrival ordinal, so the same seed and the same arrival order always yield the same sequence of faults.
 *
 * <p>Data-port paths (matched on the trailing segment, so they also work behind a route prefix such as {@code /api}):
 * {@code .../health} (200 unless a fault applies), {@code .../echo} (JSON description of the request),
 * {@code .../status/<code>}, {@code .../big?bytes=N}, {@code .../stream?chunks=N&chunkBytes=B&intervalMs=T[&abortAfterChunks=K]}
 * (chunked), {@code .../headers?h=Name:Value} (sends arbitrary response headers);
 * anything else returns a payload sized by the profile.
 */
public final class FaultUpstream implements AutoCloseable {
    private static final Gson GSON = new Gson();
    private static final byte[] PATTERN = buildPattern();
    /** -Dfaultupstream.debug=true prints the exceptions that end a connection (they are otherwise swallowed on purpose). */
    private static final boolean DEBUG = Boolean.getBoolean("faultupstream.debug");

    public record Options(String id, String host, int port, int controlPort, FaultProfile profile, int recordLimit) {
        public static Options of(String id, int port) {
            return new Options(id, "127.0.0.1", port, -1, FaultProfile.DEFAULT, 1000);
        }

        public Options withProfile(FaultProfile p) {
            return new Options(id, host, port, controlPort, p, recordLimit);
        }

        public Options withControlPort(int cp) {
            return new Options(id, host, port, cp, profile, recordLimit);
        }
    }

    public record RecordedRequest(long seq, String method, String target, List<String[]> headers, long bodyBytes, String bodyPrefix, long receivedAtMillis) {
        /** First value of a header (case-insensitive), or null. */
        public String header(String name) {
            for (String[] h : headers) {
                if (h[0].equalsIgnoreCase(name)) {
                    return h[1];
                }
            }
            return null;
        }

        public List<String> headerValues(String name) {
            List<String> out = new ArrayList<>();
            for (String[] h : headers) {
                if (h[0].equalsIgnoreCase(name)) {
                    out.add(h[1]);
                }
            }
            return out;
        }
    }

    public record Stats(long connections, long requests, long ok, long injectedErrors, long resets, long earlyCloses, long partials,
                        long capacityRejections, int active, int maxActive, long bytesSent) {
    }

    private final Options options;
    private final String id;
    private volatile FaultProfile profile;
    private volatile ServerSocket listener;
    private final int port;
    private final Set<Socket> open = ConcurrentHashMap.newKeySet();
    private final Deque<RecordedRequest> recorded = new ConcurrentLinkedDeque<>();
    private final AtomicLong ordinal = new AtomicLong();
    private final AtomicLong recordSeq = new AtomicLong();
    private final AtomicInteger active = new AtomicInteger();
    private final AtomicInteger maxActive = new AtomicInteger();
    private final AtomicInteger inCapacity = new AtomicInteger();
    private final LongAdder connections = new LongAdder();
    private final LongAdder requests = new LongAdder();
    private final LongAdder ok = new LongAdder();
    private final LongAdder injectedErrors = new LongAdder();
    private final LongAdder resets = new LongAdder();
    private final LongAdder earlyCloses = new LongAdder();
    private final LongAdder partials = new LongAdder();
    private final LongAdder capacityRejections = new LongAdder();
    private final LongAdder bytesSent = new LongAdder();
    private volatile long recoveryStartNanos;
    private volatile long recoveryDurationNanos;
    private volatile boolean closed;
    private HttpServer control;

    public static FaultUpstream start(Options options) throws IOException {
        FaultUpstream u = new FaultUpstream(options);
        u.openListener();
        if (options.controlPort() >= 0) {
            u.startControl();
        }
        return u;
    }

    private FaultUpstream(Options options) throws IOException {
        this.options = options;
        this.id = options.id();
        this.profile = options.profile();
        // Bind once to learn the port when 0 was requested; the listener is (re)opened on this fixed port afterwards.
        if (options.port() == 0) {
            try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getByName(options.host()))) {
                this.port = probe.getLocalPort();
            }
        } else {
            this.port = options.port();
        }
    }

    public String id() {
        return id;
    }

    public int port() {
        return port;
    }

    public int controlPort() {
        return control == null ? -1 : control.getAddress().getPort();
    }

    public FaultProfile profile() {
        return profile;
    }

    public Stats stats() {
        return new Stats(connections.sum(), requests.sum(), ok.sum(), injectedErrors.sum(), resets.sum(), earlyCloses.sum(), partials.sum(),
                capacityRejections.sum(), active.get(), maxActive.get(), bytesSent.sum());
    }

    public List<RecordedRequest> recorded() {
        return new ArrayList<>(recorded);
    }

    public void clearRecorded() {
        recorded.clear();
    }

    // ------------------------------------------------------------------------------------------ control

    /** Replaces the profile, cancels any gradual recovery and applies refuse/accept transitions. */
    public synchronized void setProfile(FaultProfile p) {
        boolean wasRefusing = profile.refuse;
        this.profile = p;
        this.recoveryStartNanos = 0;
        applyRefuse(wasRefusing, p.refuse);
    }

    /**
     * Starts a gradual recovery: from now on every fault probability is scaled by {@code 1 - elapsed/duration}, reaching
     * zero after {@code durationMs}. If the upstream was refusing connections it starts listening again.
     */
    public synchronized void startRecovery(long durationMs) {
        boolean wasRefusing = profile.refuse;
        if (wasRefusing) {
            this.profile = profile.with("refuse", false);
        }
        this.recoveryDurationNanos = Math.max(1, durationMs) * 1_000_000L;
        this.recoveryStartNanos = System.nanoTime();
        applyRefuse(wasRefusing, false);
    }

    private double faultScale() {
        long start = recoveryStartNanos;
        if (start == 0) {
            return 1.0;
        }
        double elapsed = (double) (System.nanoTime() - start) / recoveryDurationNanos;
        return Math.max(0.0, 1.0 - elapsed);
    }

    private void applyRefuse(boolean was, boolean now) {
        try {
            if (!was && now) {
                closeListener();
                for (Socket s : open) {
                    abort(s);
                }
            } else if (was && !now) {
                openListener();
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot change listener state on port " + port, e);
        }
    }

    private void startControl() throws IOException {
        control = HttpServer.create(new InetSocketAddress(InetAddress.getByName(options.host()), options.controlPort()), 16);
        control.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        control.createContext("/control/stats", ex -> reply(ex, 200, GSON.toJson(statsMap())));
        control.createContext("/control/requests", ex -> {
            List<Map<String, Object>> out = new ArrayList<>();
            for (RecordedRequest r : recorded) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("seq", r.seq());
                m.put("method", r.method());
                m.put("target", r.target());
                m.put("bodyBytes", r.bodyBytes());
                m.put("headers", r.headers());
                out.add(m);
            }
            reply(ex, 200, GSON.toJson(out));
        });
        control.createContext("/control/profile", ex -> {
            try {
                if (ex.getRequestMethod().equals("PUT") || ex.getRequestMethod().equals("POST")) {
                    JsonObject patch = JsonParser.parseString(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                    @SuppressWarnings("unchecked")
                    Map<String, Object> m = GSON.fromJson(patch, Map.class);
                    setProfile(FaultProfile.merge(profile, m));
                }
                reply(ex, 200, GSON.toJson(profile.toMap()));
            } catch (RuntimeException e) {
                reply(ex, 400, GSON.toJson(Map.of("error", String.valueOf(e.getMessage()))));
            }
        });
        control.createContext("/control/recover", ex -> {
            String q = ex.getRequestURI().getRawQuery();
            long ms = 10_000;
            if (q != null && q.startsWith("durationMs=")) {
                ms = Long.parseLong(q.substring("durationMs=".length()));
            }
            startRecovery(ms);
            reply(ex, 200, GSON.toJson(Map.of("recovering", true, "durationMs", ms)));
        });
        control.createContext("/control/reset", ex -> {
            setProfile(FaultProfile.DEFAULT);
            reply(ex, 200, GSON.toJson(profile.toMap()));
        });
        control.start();
    }

    private Map<String, Object> statsMap() {
        Stats s = stats();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("connections", s.connections());
        m.put("requests", s.requests());
        m.put("ok", s.ok());
        m.put("injectedErrors", s.injectedErrors());
        m.put("resets", s.resets());
        m.put("earlyCloses", s.earlyCloses());
        m.put("partials", s.partials());
        m.put("capacityRejections", s.capacityRejections());
        m.put("active", s.active());
        m.put("maxActive", s.maxActive());
        m.put("bytesSent", s.bytesSent());
        m.put("profile", profile.toMap());
        return m;
    }

    private static void reply(HttpExchange ex, int status, String json) throws IOException {
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream o = ex.getResponseBody()) {
            o.write(b);
        }
    }

    // ----------------------------------------------------------------------------------------- listener

    private synchronized void openListener() throws IOException {
        if (closed || listener != null) {
            return;
        }
        ServerSocket ss = new ServerSocket();
        ss.setReuseAddress(true);
        ss.bind(new InetSocketAddress(InetAddress.getByName(options.host()), port), 1024);
        listener = ss;
        Thread.ofVirtual().name("fault-accept-" + id).start(() -> acceptLoop(ss));
    }

    private synchronized void closeListener() throws IOException {
        ServerSocket ss = listener;
        listener = null;
        if (ss != null) {
            ss.close();
        }
    }

    private void acceptLoop(ServerSocket ss) {
        while (!ss.isClosed()) {
            try {
                Socket s = ss.accept();
                connections.increment();
                open.add(s);
                Thread.ofVirtual().name("fault-conn-" + id).start(() -> serve(s));
            } catch (SocketException e) {
                return; // listener closed
            } catch (IOException e) {
                // transient accept failure: keep going
            }
        }
    }

    @Override
    public void close() {
        closed = true;
        try {
            closeListener();
        } catch (IOException ignored) {
            // closing
        }
        for (Socket s : open) {
            try {
                s.close();
            } catch (IOException ignored) {
                // closing
            }
        }
        if (control != null) {
            control.stop(0);
        }
    }

    // ------------------------------------------------------------------------------------- connections

    private record Request(String method, String target, List<String[]> headers, long bodyBytes, boolean closeAfter, boolean head) {
        String path() {
            int q = target.indexOf('?');
            return q < 0 ? target : target.substring(0, q);
        }

        String query(String name, String def) {
            int q = target.indexOf('?');
            if (q < 0) {
                return def;
            }
            for (String pair : target.substring(q + 1).split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0 && pair.substring(0, eq).equals(name)) {
                    return pair.substring(eq + 1);
                }
            }
            return def;
        }
    }

    private void serve(Socket s) {
        try (s) {
            s.setTcpNoDelay(true);
            s.setSoTimeout(60_000);
            InputStream in = new BufferedInputStream(s.getInputStream(), 16 * 1024);
            OutputStream out = s.getOutputStream();
            while (!closed) {
                Request req = readRequest(in, out);
                if (req == null) {
                    return;
                }
                requests.increment();
                if (!handle(req, s, out)) {
                    return;
                }
            }
        } catch (IOException | RuntimeException e) {
            // connection ended (client closed, reset, timeout, or we aborted it on purpose)
            if (DEBUG) {
                System.err.println("[fault-upstream " + id + "] connection ended: " + e);
            }
        } finally {
            open.remove(s);
        }
    }

    private Request readRequest(InputStream in, OutputStream out) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream(512);
        int state = 0;
        int b;
        while (state < 4) {
            b = in.read();
            if (b < 0) {
                if (head.size() == 0) {
                    return null;
                }
                throw new IOException("EOF inside request head");
            }
            head.write(b);
            if (head.size() > 64 * 1024) {
                throw new IOException("request head too large");
            }
            state = (b == '\r' && (state == 0 || state == 2)) ? state + 1 : (b == '\n' && (state == 1 || state == 3)) ? state + 1 : (b == '\r' ? 1 : 0);
        }
        String[] lines = head.toString(StandardCharsets.ISO_8859_1).split("\r\n");
        String[] rl = lines[0].split(" ");
        if (rl.length != 3) {
            throw new IOException("bad request line");
        }
        List<String[]> headers = new ArrayList<>();
        long contentLength = -1;
        boolean chunked = false;
        boolean closeAfter = rl[2].equals("HTTP/1.0");
        boolean expectContinue = false;
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String name = lines[i].substring(0, colon).trim();
            String value = lines[i].substring(colon + 1).trim();
            headers.add(new String[] {name, value});
            String ln = name.toLowerCase(Locale.ROOT);
            if (ln.equals("content-length")) {
                contentLength = Long.parseLong(value);
            } else if (ln.equals("transfer-encoding") && value.toLowerCase(Locale.ROOT).contains("chunked")) {
                chunked = true;
            } else if (ln.equals("connection") && value.equalsIgnoreCase("close")) {
                closeAfter = true;
            } else if (ln.equals("expect") && value.equalsIgnoreCase("100-continue")) {
                expectContinue = true;
            }
        }
        if (expectContinue) {
            out.write("HTTP/1.1 100 Continue\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
        }
        ByteArrayOutputStream prefix = new ByteArrayOutputStream();
        long bodyBytes = 0;
        if (chunked) {
            while (true) {
                String sizeLine = readLine(in);
                int semi = sizeLine.indexOf(';');
                long size = Long.parseLong((semi < 0 ? sizeLine : sizeLine.substring(0, semi)).trim(), 16);
                if (size == 0) {
                    while (!readLine(in).isEmpty()) {
                        // trailers
                    }
                    break;
                }
                bodyBytes += drain(in, size, prefix);
                readLine(in); // CRLF after chunk data
            }
        } else if (contentLength > 0) {
            bodyBytes = drain(in, contentLength, prefix);
        }
        RecordedRequest rec = new RecordedRequest(recordSeq.incrementAndGet(), rl[0], rl[1], headers, bodyBytes,
                prefix.toString(StandardCharsets.ISO_8859_1), System.currentTimeMillis());
        recorded.addLast(rec);
        while (recorded.size() > options.recordLimit()) {
            recorded.pollFirst();
        }
        return new Request(rl[0], rl[1], headers, bodyBytes, closeAfter, rl[0].equals("HEAD"));
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int b;
        while ((b = in.read()) >= 0) {
            if (b == '\n') {
                int n = sb.length();
                if (n > 0 && sb.charAt(n - 1) == '\r') {
                    sb.setLength(n - 1);
                }
                return sb.toString();
            }
            sb.append((char) b);
            if (sb.length() > 8192) {
                throw new IOException("line too long");
            }
        }
        throw new IOException("EOF");
    }

    private static long drain(InputStream in, long n, ByteArrayOutputStream prefix) throws IOException {
        byte[] buf = new byte[8192];
        long left = n;
        while (left > 0) {
            int r = in.read(buf, 0, (int) Math.min(buf.length, left));
            if (r < 0) {
                throw new IOException("EOF inside body");
            }
            if (prefix.size() < 256) {
                prefix.write(buf, 0, Math.min(r, 256 - prefix.size()));
            }
            left -= r;
        }
        return n;
    }

    // -------------------------------------------------------------------------------------- behaviour

    /** @return whether the connection stays open for another request */
    private boolean handle(Request req, Socket socket, OutputStream out) throws IOException {
        FaultProfile p = profile;
        long ord = ordinal.incrementAndGet();
        SplittableRandom rng = new SplittableRandom(p.seed ^ (ord * 0x9E3779B97F4A7C15L));
        int now = active.incrementAndGet();
        maxActive.accumulateAndGet(now, Math::max);
        boolean slotHeld = false;
        try {
            if (p.maxConcurrency > 0) {
                if (!acquireSlot(p)) {
                    capacityRejections.increment();
                    writeSimple(out, 503, "Service Unavailable", "capacity", "{\"error\":\"capacity\",\"upstream\":\"" + id + "\"}", req.head());
                    return !req.closeAfter();
                }
                slotHeld = true;
            }
            long delay = p.delayMs + (p.jitterMs > 0 ? rng.nextLong(p.jitterMs + 1) : 0);
            if (delay > 0) {
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            double scale = faultScale();
            double r = rng.nextDouble();
            double reset = p.resetRate * scale;
            double close = p.closeRate * scale;
            double partial = p.partialRate * scale;
            double error = p.errorRate * scale;
            if (r < reset) {
                resets.increment();
                abort(socket);
                return false;
            }
            if (r < reset + close) {
                earlyCloses.increment();
                socket.close();
                return false;
            }
            int size = p.payloadMin + (p.payloadMax > p.payloadMin ? rng.nextInt(p.payloadMax - p.payloadMin + 1) : 0);
            if (r < reset + close + partial) {
                partials.increment();
                int declared = Math.max(2, size);
                writeHead(out, 200, "OK", "partial", declared, req.head());
                if (!req.head()) {
                    writeBody(out, declared / 2, p.slowBytesPerSecond);
                }
                out.flush();
                socket.close();
                return false;
            }
            if (r < reset + close + partial + error) {
                injectedErrors.increment();
                writeSimple(out, p.errorStatus, reason(p.errorStatus), "error",
                        "{\"error\":\"injected\",\"status\":" + p.errorStatus + ",\"upstream\":\"" + id + "\"}", req.head());
                return !req.closeAfter();
            }
            respondNormally(req, out, size, p, socket);
            ok.increment();
            return !req.closeAfter();
        } finally {
            if (slotHeld) {
                inCapacity.decrementAndGet();
            }
            active.decrementAndGet();
        }
    }

    private boolean acquireSlot(FaultProfile p) {
        long deadline = System.nanoTime() + p.queueWaitMs * 1_000_000L;
        while (true) {
            int cur = inCapacity.get();
            if (cur < p.maxConcurrency) {
                if (inCapacity.compareAndSet(cur, cur + 1)) {
                    return true;
                }
                continue;
            }
            if (System.nanoTime() >= deadline) {
                return false;
            }
            LockSupport.parkNanos(500_000);
        }
    }

    private void respondNormally(Request req, OutputStream out, int size, FaultProfile p, Socket socket) throws IOException {
        String path = req.path();
        int statusAt = path.lastIndexOf("/status/");
        if (statusAt >= 0) {
            int code;
            try {
                code = Integer.parseInt(path.substring(statusAt + "/status/".length()));
            } catch (NumberFormatException e) {
                code = 400;
            }
            writeSimple(out, code, reason(code), "status", "{\"status\":" + code + ",\"upstream\":\"" + id + "\"}", req.head());
        } else if (path.endsWith("/echo")) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("upstream", id);
            m.put("method", req.method());
            m.put("target", req.target());
            Map<String, List<String>> hs = new LinkedHashMap<>();
            for (String[] h : req.headers()) {
                hs.computeIfAbsent(h[0], k -> new ArrayList<>()).add(h[1]);
            }
            m.put("headers", hs);
            m.put("bodyBytes", req.bodyBytes());
            writeSimple(out, 200, "OK", null, GSON.toJson(m), req.head());
        } else if (path.endsWith("/headers")) {
            // test hook: /headers?h=Name:Value&h=Other:Value sends those response headers (values are URL-decoded)
            StringBuilder sb = new StringBuilder("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nX-Upstream-Id: " + id + "\r\n");
            String q = req.target().contains("?") ? req.target().substring(req.target().indexOf('?') + 1) : "";
            for (String pair : q.split("&")) {
                if (pair.startsWith("h=")) {
                    sb.append(java.net.URLDecoder.decode(pair.substring(2), StandardCharsets.UTF_8)).append("\r\n");
                }
            }
            sb.append("\r\n");
            out.write(sb.toString().getBytes(StandardCharsets.ISO_8859_1));
            if (!req.head()) {
                out.write("ok".getBytes(StandardCharsets.ISO_8859_1));
            }
            out.flush();
        } else if (path.endsWith("/stream")) {
            int abortAfter = (int) Long.parseLong(req.query("abortAfterChunks", "-1"));
            int chunks = (int) Math.min(10_000, Long.parseLong(req.query("chunks", "5")));
            int chunkBytes = (int) Math.min(1 << 20, Long.parseLong(req.query("chunkBytes", "1024")));
            long interval = Long.parseLong(req.query("intervalMs", "0"));
            writeChunkedHead(out, 200, "OK");
            if (!req.head()) {
                for (int i = 0; i < chunks; i++) {
                    if (abortAfter >= 0 && i >= abortAfter) {
                        out.flush();
                        socket.close(); // die mid-stream: no terminating chunk
                        return;
                    }
                    if (interval > 0 && i > 0) {
                        try {
                            Thread.sleep(interval);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                    out.write((Integer.toHexString(chunkBytes) + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
                    writeBody(out, chunkBytes, p.slowBytesPerSecond);
                    out.write("\r\n".getBytes(StandardCharsets.ISO_8859_1));
                    out.flush();
                }
                out.write("0\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
                out.flush();
            }
        } else {
            int n = size;
            if (path.endsWith("/big")) {
                n = (int) Math.min(64L * 1024 * 1024, Long.parseLong(req.query("bytes", "1048576")));
            } else if (path.endsWith("/health")) {
                n = 2;
            }
            writeHead(out, 200, "OK", null, n, req.head());
            if (!req.head()) {
                writeBody(out, n, p.slowBytesPerSecond);
            }
            out.flush();
        }
    }

    private void writeSimple(OutputStream out, int status, String reason, String faultKind, String json, boolean head) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        boolean bodiless = status == 204 || status == 304 || (status >= 100 && status < 200);
        StringBuilder sb = new StringBuilder(160);
        sb.append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n");
        if (!bodiless) {
            // RFC 9110: no Content-Length (or body) on 1xx and 204; 304 carries no body either
            sb.append("Content-Type: application/json\r\n");
            sb.append("Content-Length: ").append(body.length).append("\r\n");
        }
        sb.append("X-Upstream-Id: ").append(id).append("\r\n");
        if (faultKind != null) {
            sb.append("X-Fault: ").append(faultKind).append("\r\n");
        }
        sb.append("\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.ISO_8859_1));
        if (!head && !bodiless) {
            out.write(body);
            bytesSent.add(body.length);
        }
        out.flush();
    }

    private void writeHead(OutputStream out, int status, String reason, String faultKind, int contentLength, boolean head) throws IOException {
        StringBuilder sb = new StringBuilder(160);
        sb.append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n");
        sb.append("Content-Type: application/octet-stream\r\n");
        sb.append("Content-Length: ").append(contentLength).append("\r\n");
        sb.append("X-Upstream-Id: ").append(id).append("\r\n");
        if (faultKind != null) {
            sb.append("X-Fault: ").append(faultKind).append("\r\n");
        }
        sb.append("\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.ISO_8859_1));
    }

    private void writeChunkedHead(OutputStream out, int status, String reason) throws IOException {
        String h = "HTTP/1.1 " + status + " " + reason + "\r\nContent-Type: application/octet-stream\r\nTransfer-Encoding: chunked\r\nX-Upstream-Id: " + id + "\r\n\r\n";
        out.write(h.getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    private void writeBody(OutputStream out, int n, long bytesPerSecond) throws IOException {
        int left = n;
        int offset = 0;
        int slice = bytesPerSecond > 0 ? (int) Math.max(1, bytesPerSecond / 50) : PATTERN.length;
        while (left > 0) {
            int len = Math.min(left, Math.min(slice, PATTERN.length - offset));
            out.write(PATTERN, offset, len);
            bytesSent.add(len);
            left -= len;
            offset = (offset + len) % PATTERN.length;
            if (bytesPerSecond > 0) {
                out.flush();
                try {
                    Thread.sleep(Math.max(1, len * 1000L / bytesPerSecond));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted", e);
                }
            }
        }
    }

    private static void abort(Socket s) {
        try {
            s.setSoLinger(true, 0);
            s.close();
        } catch (IOException ignored) {
            // already closed
        }
    }

    private static String reason(int status) {
        return switch (status) {
            case 200 -> "OK";
            case 204 -> "No Content";
            case 304 -> "Not Modified";
            case 400 -> "Bad Request";
            case 404 -> "Not Found";
            case 429 -> "Too Many Requests";
            case 500 -> "Internal Server Error";
            case 502 -> "Bad Gateway";
            case 503 -> "Service Unavailable";
            case 504 -> "Gateway Timeout";
            default -> "Status " + status;
        };
    }

    private static byte[] buildPattern() {
        byte[] b = new byte[16 * 1024];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) ('a' + (i % 26));
        }
        return b;
    }
}
