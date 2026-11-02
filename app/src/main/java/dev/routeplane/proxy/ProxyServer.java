package dev.routeplane.proxy;

import com.sun.net.httpserver.HttpServer;
import dev.routeplane.config.RouteplaneConfig.LoggingConfig;
import dev.routeplane.config.RouteplaneConfig.ServerConfig;
import dev.routeplane.observability.StructuredLogger;
import dev.routeplane.util.TimeSource;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/** The proxy listener: a JDK {@link HttpServer} whose exchanges are handled on virtual threads. */
public final class ProxyServer implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService executor;
    private final ProxyHandler handler;
    private final StructuredLogger log;
    private volatile boolean stopped;

    private ProxyServer(HttpServer server, ExecutorService executor, ProxyHandler handler, StructuredLogger log) {
        this.server = server;
        this.executor = executor;
        this.handler = handler;
        this.log = log;
    }

    public static ProxyServer start(ServerConfig cfg, LoggingConfig logging, Supplier<RuntimeState> state, ProxyMetrics metrics,
                                    TimeSource time, StructuredLogger log, String viaToken) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getByName(cfg.host()), cfg.port()), cfg.backlog());
        ExecutorService executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("proxy-", 0).factory());
        server.setExecutor(executor);
        ProxyHandler handler = new ProxyHandler(state, cfg, logging, metrics, time, log, viaToken);
        server.createContext("/", handler);
        server.start();
        return new ProxyServer(server, executor, handler, log);
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public int inFlight() {
        return handler.inFlight();
    }

    /** True from the moment draining starts (readiness goes red immediately), not only once shutdown completes. */
    public boolean isDraining() {
        return handler.isDraining();
    }

    /** Stops accepting new work: new requests are answered with 503 and {@code Connection: close}. */
    public void drain() {
        handler.startDraining();
    }

    /**
     * Graceful shutdown: drain, wait up to {@code grace} for in-flight requests to finish, then close the listener and
     * every remaining connection.
     *
     * @return true if everything finished within the grace period
     */
    public boolean shutdown(Duration grace) {
        if (stopped) {
            return true;
        }
        stopped = true;
        drain();
        long deadline = System.nanoTime() + grace.toNanos();
        while (handler.inFlight() > 0 && System.nanoTime() < deadline) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        boolean clean = handler.inFlight() == 0;
        if (!clean) {
            log.warn("shutdown_grace_exceeded", "in_flight", handler.inFlight());
        }
        server.stop(0);
        executor.shutdownNow();
        return clean;
    }

    @Override
    public void close() {
        shutdown(Duration.ZERO);
    }
}
