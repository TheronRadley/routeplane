package dev.routeplane;

import dev.routeplane.admin.AdminServer;
import dev.routeplane.config.ConfigLoader;
import dev.routeplane.config.RouteplaneConfig;
import dev.routeplane.observability.JvmMetrics;
import dev.routeplane.observability.MetricsRegistry;
import dev.routeplane.observability.StructuredLogger;
import dev.routeplane.observability.TcpStats;
import dev.routeplane.proxy.ConfigManager;
import dev.routeplane.proxy.JdkTuning;
import dev.routeplane.proxy.ProxyMetrics;
import dev.routeplane.proxy.ProxyServer;
import dev.routeplane.proxy.RuntimeState;
import dev.routeplane.proxy.StateMetrics;
import dev.routeplane.routing.PolicyRegistry;
import dev.routeplane.util.TimeSource;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;

/** Wires everything together: configuration, metrics, the proxy listener and the admin listener. */
public final class RouteplaneApp implements AutoCloseable {
    private final ConfigManager configs;
    private final ProxyServer proxy;
    private final AdminServer admin;
    private final StructuredLogger log;
    private final MetricsRegistry registry;
    private final Duration grace;
    private volatile boolean closed;

    private RouteplaneApp(ConfigManager configs, ProxyServer proxy, AdminServer admin, StructuredLogger log, MetricsRegistry registry, Duration grace) {
        this.configs = configs;
        this.proxy = proxy;
        this.admin = admin;
        this.log = log;
        this.registry = registry;
        this.grace = grace;
    }

    public static RouteplaneApp start(Path configFile) throws IOException {
        return start(configFile, System::getenv, System.out);
    }

    /**
     * Starts the proxy and admin listeners.
     *
     * @throws dev.routeplane.config.ConfigException if the configuration is invalid (nothing has been started then)
     */
    public static RouteplaneApp start(Path configFile, Function<String, String> env, OutputStream logSink) throws IOException {
        PolicyRegistry policies = PolicyRegistry.load();
        RouteplaneConfig cfg = new ConfigLoader(policies).loadFile(configFile, env);
        if (cfg.server().hostHeader() == RouteplaneConfig.HostHeaderMode.PRESERVE && !JdkTuning.hostHeaderAllowed()) {
            throw new IllegalStateException("server.hostHeader: preserve requires the JVM option -Djdk.httpclient.allowRestrictedHeaders=host "
                    + "(the JDK HTTP client refuses to set Host otherwise); the routeplane start script sets it");
        }
        TimeSource time = TimeSource.SYSTEM;
        StructuredLogger log = new StructuredLogger(cfg.logging().level(), logSink, cfg.logging().queueCapacity(), time);
        for (String w : JdkIssues.warnings()) {
            log.warn("jdk_known_issue", "java", System.getProperty("java.version"), "detail", w);
        }
        MetricsRegistry registry = new MetricsRegistry(cfg.metrics().maxSeriesPerFamily());
        ProxyMetrics metrics = new ProxyMetrics(registry);
        TcpStats tcp = new TcpStats();
        String viaToken = "routeplane-" + HexFormat.of().formatHex(new byte[] {(byte) ThreadLocalRandom.current().nextInt(), (byte) ThreadLocalRandom.current().nextInt(),
                (byte) ThreadLocalRandom.current().nextInt()});
        ConfigManager configs = ConfigManager.create(configFile, policies, env, metrics, log, time);
        ProxyServer proxy = null;
        AdminServer admin = null;
        try {
            JdkTuning.apply(cfg.server(), log);
            RuntimeState initial = configs.loadInitial();
            JvmMetrics.register(registry, BuildInfo.version());
            ProxyServer[] holder = new ProxyServer[1];
            StateMetrics.register(registry, configs::current, tcp, () -> holder[0] == null ? 0 : holder[0].port());
            proxy = ProxyServer.start(cfg.server(), cfg.logging(), configs::current, metrics, time, log, viaToken);
            holder[0] = proxy;
            ProxyServer p = proxy;
            if (cfg.admin().enabled()) {
                admin = AdminServer.start(cfg.admin(), configs, registry, log, p::isDraining);
            }
            if (cfg.server().watchConfig()) {
                configs.startWatching(cfg.server().watchInterval());
            }
            log.info("started", "version", BuildInfo.version(), "java", System.getProperty("java.version"), "config", configFile.toString(),
                    "config_version", initial.version(), "routes", cfg.routes().size(),
                    "proxy", cfg.server().host() + ":" + proxy.port(),
                    "admin", admin == null ? "disabled" : cfg.admin().host() + ":" + admin.port() + (admin.tls() ? " (tls)" : ""));
            return new RouteplaneApp(configs, proxy, admin, log, registry, cfg.server().shutdownGracePeriod());
        } catch (IOException | RuntimeException e) {
            if (proxy != null) {
                proxy.close();
            }
            if (admin != null) {
                admin.close();
            }
            configs.close();
            log.close();
            throw e;
        }
    }

    public int proxyPort() {
        return proxy.port();
    }

    /** The admin listener's port, or -1 when the admin listener is disabled. */
    public int adminPort() {
        return admin == null ? -1 : admin.port();
    }

    public ConfigManager configs() {
        return configs;
    }

    public MetricsRegistry metrics() {
        return registry;
    }

    public ProxyServer proxy() {
        return proxy;
    }

    /** Drains, waits up to {@code grace} for in-flight requests, then stops listeners and releases all resources. */
    public synchronized boolean shutdown(Duration gracePeriod) {
        if (closed) {
            return true;
        }
        closed = true;
        log.info("shutting_down", "in_flight", proxy.inFlight(), "grace_ms", gracePeriod.toMillis());
        boolean clean = proxy.shutdown(gracePeriod);
        if (admin != null) {
            admin.close();
        }
        configs.close();
        log.info("stopped", "clean", clean);
        log.close();
        return clean;
    }

    @Override
    public void close() {
        shutdown(grace);
    }
}
