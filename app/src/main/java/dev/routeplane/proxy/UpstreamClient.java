package dev.routeplane.proxy;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * One {@link HttpClient} per upstream (own connection pool, forced HTTP/1.1, no redirects, no system proxy).
 * Everything specific to the JDK client lives here: restricted headers, body publishers, and translating its
 * exceptions into {@link FailureKind}s. See ADR-0001 for the behaviours this relies on.
 */
final class UpstreamClient implements AutoCloseable {
    private static final ExecutorService EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * A response whose headers have arrived; the body is streamed from {@link #body}.
     *
     * <p>{@link #close()} <em>cancels</em> the exchange (and the connection with it). Call it only when the body was not
     * read to its end. After a clean end of stream the exchange is complete and the JDK has already returned the
     * connection to its pool; cancelling then is pointless work and could only interfere with that connection's reuse.
     * Use {@link #finish()} for that case.
     */
    record Response(int status, HttpHeaders headers, InputStream body, long headersNanos) implements AutoCloseable {
        @Override
        public void close() {
            try {
                body.close();
            } catch (IOException ignored) {
                // cancelling
            }
        }

        /**
         * For responses that have no body (HEAD, 204, 304) or whose body has been consumed: confirms end of stream
         * without cancelling. If unexpected bytes turn up, the exchange is cancelled instead.
         */
        void finish() {
            try {
                if (body.read() >= 0) {
                    close();
                }
            } catch (IOException e) {
                close();
            }
        }
    }

    /** Failure to obtain a response, already classified. */
    static final class UpstreamException extends Exception {
        private static final long serialVersionUID = 1L;
        final FailureKind kind;
        /** True when the failure was a connect timeout rather than a refused/unreachable connect. */
        final boolean connectTimeout;

        UpstreamException(FailureKind kind, boolean connectTimeout, Throwable cause) {
            super(kind.code() + (cause == null ? "" : ": " + cause), cause);
            this.kind = kind;
            this.connectTimeout = connectTimeout;
        }
    }

    private final HttpClient client;
    private final URI base;

    UpstreamClient(URI base, Duration connectTimeout) {
        this.base = base;
        this.client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(connectTimeout)
                .proxy(ProxySelector.of(null))
                .executor(EXECUTOR)
                .build();
    }

    URI base() {
        return base;
    }

    /**
     * Sends a request and waits for the response headers.
     *
     * @param pathAndQuery normalised path plus optional {@code ?query}, appended to the upstream's base path
     * @throws UpstreamException classified failure (connect, reset, timeout)
     * @throws InterruptedException when the handling thread was interrupted (deadline or shutdown)
     */
    Response send(String method, String pathAndQuery, List<String[]> headers, BodySource body, Duration headerTimeout)
            throws UpstreamException, InterruptedException {
        HttpRequest.Builder b;
        try {
            String basePath = base.getRawPath() == null ? "" : base.getRawPath();
            b = HttpRequest.newBuilder(URI.create("http://" + base.getRawAuthority() + basePath + pathAndQuery)).timeout(headerTimeout);
            for (String[] h : headers) {
                b.header(h[0], h[1]);
            }
        } catch (IllegalArgumentException e) {
            throw new UpstreamException(FailureKind.INVALID_REQUEST, false, e);
        }
        b.method(method, publisher(body));
        long t0 = System.nanoTime();
        try {
            HttpResponse<InputStream> r = client.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
            return new Response(r.statusCode(), r.headers(), r.body(), System.nanoTime() - t0);
        } catch (HttpConnectTimeoutException e) {
            throw new UpstreamException(FailureKind.CONNECT_FAILED, true, e);
        } catch (HttpTimeoutException e) {
            throw new UpstreamException(FailureKind.UPSTREAM_TIMEOUT, false, e);
        } catch (IOException e) {
            throw new UpstreamException(isConnectFailure(e) ? FailureKind.CONNECT_FAILED : FailureKind.UPSTREAM_RESET, false, e);
        } catch (IllegalArgumentException | SecurityException e) {
            throw new UpstreamException(FailureKind.INVALID_REQUEST, false, e);
        }
    }

    private static HttpRequest.BodyPublisher publisher(BodySource body) {
        return switch (body) {
            case BodySource.Empty e -> HttpRequest.BodyPublishers.noBody();
            case BodySource.Buffered buf -> HttpRequest.BodyPublishers.ofByteArray(buf.bytes());
            case BodySource.Streaming s -> s.length() >= 0
                    ? HttpRequest.BodyPublishers.fromPublisher(HttpRequest.BodyPublishers.ofInputStream(s::stream), s.length())
                    : HttpRequest.BodyPublishers.ofInputStream(s::stream);
        };
    }

    private static boolean isConnectFailure(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof ConnectException || c instanceof UnresolvedAddressException || c instanceof NoRouteToHostException
                    || c instanceof UnknownHostException) {
                return true;
            }
            if (c.getCause() == c) {
                break;
            }
        }
        return false;
    }

    /** Active health probe: GET {@code path}; returns the status or throws. The body is discarded. */
    int probe(String path, Duration timeout) throws UpstreamException, InterruptedException {
        Response r = send("GET", path, java.util.Collections.singletonList(new String[] {"User-Agent", "routeplane-health"}), new BodySource.Empty(), timeout);
        try {
            byte[] body = r.body().readNBytes(4096);
            if (body.length < 4096) {
                r.finish(); // reached the end of the body: do not cancel a completed exchange
            } else {
                r.close();
            }
            return r.status();
        } catch (IOException e) {
            r.close();
            throw new UpstreamException(FailureKind.UPSTREAM_RESET, false, e);
        }
    }

    @Override
    public void close() {
        client.shutdownNow();
    }
}
