package dev.routeplane.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import dev.routeplane.testing.RawClient;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Executable evidence for ADR-0001. These tests use a bare JDK server and client (none of Routeplane's code) and pin
 * the JDK behaviours the proxy's design depends on. If a future JDK changes one of them, this class fails and points at
 * the assumption that needs revisiting.
 */
@Timeout(60)
class JdkHttpStackContractTest {

    private static HttpServer server(com.sun.net.httpserver.HttpHandler handler) throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 16);
        s.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        s.createContext("/", handler);
        s.start();
        return s;
    }

    @Test
    void interruptingAHandlerThreadBlockedInAWriteUnblocksItAndClosesTheConnection() throws Exception {
        AtomicReference<Thread> handler = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        HttpServer s = server(ex -> {
            handler.set(Thread.currentThread());
            ex.sendResponseHeaders(200, 0);
            OutputStream out = ex.getResponseBody();
            byte[] chunk = new byte[65536];
            try {
                for (int i = 0; i < 4000; i++) {
                    out.write(chunk);
                }
            } catch (IOException e) {
                failure.set(e);
            }
            done.countDown();
        });
        try (RawClient c = new RawClient(s.getAddress().getPort(), 5000)) {
            c.socket().setReceiveBufferSize(4096);
            c.send("GET / HTTP/1.1\r\nHost: t\r\n\r\n");
            Thread.sleep(800); // the client never reads: the handler blocks once the socket buffers are full
            assertTrue(handler.get() != null && done.getCount() == 1, "handler is blocked in write");
            long t0 = System.nanoTime();
            handler.get().interrupt();
            assertTrue(done.await(3, TimeUnit.SECONDS), "interrupt must unblock the write: this is the deadline mechanism");
            assertTrue((System.nanoTime() - t0) / 1_000_000 < 2000);
            assertTrue(failure.get() instanceof java.nio.channels.ClosedByInterruptException || failure.get() instanceof IOException, String.valueOf(failure.get()));
        } finally {
            s.stop(0);
        }
    }

    @Test
    void interruptThenCloseDoesNotSendTheTerminatingChunk() throws Exception {
        HttpServer s = server(ex -> {
            ex.sendResponseHeaders(200, 0);
            OutputStream out = ex.getResponseBody();
            out.write("hello".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            Thread.currentThread().interrupt(); // the abort idiom used by ProxyHandler.abort
            try {
                ex.close();
            } catch (RuntimeException ignored) {
                // expected: the connection is torn down
            }
            Thread.interrupted();
        });
        try (RawClient c = new RawClient(s.getAddress().getPort(), 5000)) {
            c.send("GET / HTTP/1.1\r\nHost: t\r\n\r\n");
            byte[] all = c.readUntilClosed();
            String text = new String(all, StandardCharsets.ISO_8859_1);
            assertTrue(text.contains("hello"), text);
            assertFalse(text.endsWith("0\r\n\r\n"), "a truncated response must not look complete: " + text.replace("\r\n", "|"));
        } finally {
            s.stop(0);
        }
    }

    @Test
    void closingAnExchangeWithAnUnreadRequestBodyBlocksUntilTheClientDeliversOrLeaves() throws Exception {
        AtomicLong closeMillis = new AtomicLong(-1);
        HttpServer s = server(ex -> {
            byte[] b = "ok".getBytes(StandardCharsets.US_ASCII);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            long t0 = System.nanoTime();
            ex.close(); // never read the request body: the JDK drains it with a blocking read
            closeMillis.set((System.nanoTime() - t0) / 1_000_000);
        });
        try (RawClient c = new RawClient(s.getAddress().getPort(), 5000)) {
            c.send("POST / HTTP/1.1\r\nHost: t\r\nContent-Length: 1000\r\n\r\n0123456789");
            Thread.sleep(700);
            boolean stillBlocked = closeMillis.get() < 0;
            Assumptions.assumeTrue(stillBlocked, "this JDK no longer blocks on close(): the bounded lingering close in ProxyHandler may be removable");
            c.close();
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (closeMillis.get() < 0 && System.nanoTime() < end) {
                Thread.sleep(20);
            }
            assertTrue(closeMillis.get() >= 500, "close() returned only after the client left: " + closeMillis.get() + " ms");
        } finally {
            s.stop(0);
        }
    }

    @Test
    void theHttpClientReportsTruncatedBodiesAsErrors() throws Exception {
        try (ServerSocket ss = new ServerSocket(0, 5, java.net.InetAddress.getLoopbackAddress())) {
            Thread t = Thread.ofVirtual().start(() -> {
                try (Socket sock = ss.accept()) {
                    sock.getInputStream().read(new byte[4096]);
                    sock.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: 1000\r\n\r\n" + "x".repeat(300)).getBytes(StandardCharsets.US_ASCII));
                    sock.getOutputStream().flush();
                } catch (IOException ignored) {
                    // test server
                }
            });
            HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
            HttpResponse<InputStream> r = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + ss.getLocalPort() + "/")).build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            assertEquals(200, r.statusCode());
            assertThrows(IOException.class, () -> r.body().readAllBytes(), "a short body must surface as an IOException, never as a clean EOF");
            t.join();
        }
    }

    @Test
    void theHttpClientSendIsInterruptibleAndCancelsTheExchange() throws Exception {
        CountDownLatch seen = new CountDownLatch(1);
        HttpServer s = server(ex -> {
            seen.countDown();
            try {
                Thread.sleep(5000);
            } catch (InterruptedException ignored) {
                // test server
            }
        });
        try {
            HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
            AtomicReference<Throwable> thrown = new AtomicReference<>();
            Thread caller = Thread.ofVirtual().start(() -> {
                try {
                    client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + s.getAddress().getPort() + "/")).build(), HttpResponse.BodyHandlers.discarding());
                } catch (Throwable e) {
                    thrown.set(e);
                }
            });
            assertTrue(seen.await(3, TimeUnit.SECONDS));
            long t0 = System.nanoTime();
            caller.interrupt();
            caller.join(3000);
            assertTrue(thrown.get() instanceof InterruptedException, "send() throws InterruptedException when interrupted: " + thrown.get());
            assertTrue((System.nanoTime() - t0) / 1_000_000 < 2000);
        } finally {
            s.stop(0);
        }
    }

    @Test
    void theHttpClientRefusesRestrictedHeadersButAcceptsTransferEncoding() {
        for (String h : new String[] {"Connection", "Content-Length", "Expect", "Upgrade"}) {
            assertThrows(IllegalArgumentException.class, () -> HttpRequest.newBuilder(URI.create("http://127.0.0.1/")).header(h, "x").build(), h);
        }
        // Not restricted by the builder - which is why HeaderPolicy must strip them itself.
        HttpRequest.newBuilder(URI.create("http://127.0.0.1/")).header("Transfer-Encoding", "chunked").build();
        HttpRequest.newBuilder(URI.create("http://127.0.0.1/")).header("TE", "trailers").build();
    }
}
