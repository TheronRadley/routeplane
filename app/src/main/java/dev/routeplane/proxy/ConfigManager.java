package dev.routeplane.proxy;

import dev.routeplane.config.ConfigException;
import dev.routeplane.config.ConfigLoader;
import dev.routeplane.config.RouteplaneConfig;
import dev.routeplane.observability.StructuredLogger;
import dev.routeplane.routing.PolicyRegistry;
import dev.routeplane.util.TimeSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Function;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Owns the active configuration. {@link #reload} is all-or-nothing: the file is parsed and validated completely, the
 * runtime (clients, breakers, policies...) is built completely, and only then is one reference swapped. Any failure
 * leaves the previous configuration serving untouched (rollback by never having left it). Resources of the old version
 * that the new one does not reuse are closed after a grace period so in-flight requests can finish.
 *
 * <p>Settings that the JVM freezes at start-up ({@code server}, {@code admin}, {@code logging}, {@code metrics}) cannot be
 * applied by a reload; a reload that changes them is rejected with an explicit "restart required" message instead of
 * being silently half-applied.
 */
public final class ConfigManager implements AutoCloseable {

    public record ReloadResult(boolean success, boolean changed, long version, String outcome, List<String> errors, long atMillis) {
    }

    private final Path file;
    private final ConfigLoader loader;
    private final RuntimeFactory builder;
    private final Function<String, String> env;
    private final ProxyMetrics metrics;
    private final StructuredLogger log;
    private final TimeSource time;
    private final AtomicReference<RuntimeState> current = new AtomicReference<>();
    private final ReentrantLock lock = new ReentrantLock();
    private final ScheduledExecutorService background = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "routeplane-config");
        t.setDaemon(true);
        return t;
    });
    private volatile ReloadResult lastReload;
    private volatile long version;
    private volatile boolean closed;
    private volatile long watchedMtime = -1;
    private volatile long watchedSize = -1;

    private ConfigManager(Path file, ConfigLoader loader, RuntimeFactory builder, Function<String, String> env,
                          ProxyMetrics metrics, StructuredLogger log, TimeSource time) {
        this.file = file;
        this.loader = loader;
        this.builder = builder;
        this.env = env;
        this.metrics = metrics;
        this.log = log;
        this.time = time;
    }

    public static ConfigManager create(Path file, PolicyRegistry policies, Function<String, String> env, ProxyMetrics metrics,
                                       StructuredLogger log, TimeSource time) {
        return new ConfigManager(file, new ConfigLoader(policies), new RuntimeFactory(policies, metrics, time, log), env, metrics, log, time);
    }

    public RuntimeState current() {
        return current.get();
    }

    public ReloadResult lastReload() {
        return lastReload;
    }

    public Path file() {
        return file;
    }

    /** Loads the first configuration. Failure is fatal for the caller (there is nothing to fall back to). */
    public RuntimeState loadInitial() throws IOException {
        lock.lock();
        try {
            byte[] bytes = Files.readAllBytes(file);
            RouteplaneConfig cfg = loader.loadFile(file, env);
            RuntimeState st = builder.build(cfg, ++version, sha256(bytes), time.currentTimeMillis(), null);
            current.set(st);
            rememberFileStamp();
            lastReload = new ReloadResult(true, true, st.version(), "initial load", List.of(), time.currentTimeMillis());
            metrics.registry().counter("routeplane_config_reloads_total", "Configuration load attempts by result", "result").inc("loaded");
            return st;
        } finally {
            lock.unlock();
        }
    }

    public ReloadResult reload(String trigger) {
        lock.lock();
        try {
            RuntimeState old = current.get();
            ReloadResult result;
            try {
                byte[] bytes = Files.readAllBytes(file);
                RouteplaneConfig cfg = loader.loadFile(file, env);
                List<String> frozen = frozenSectionsChanged(old.config(), cfg);
                if (!frozen.isEmpty()) {
                    result = fail("restart_required", List.of("these settings are applied at start-up only and differ from the running configuration: "
                            + frozen + "; restart the process to apply them"));
                } else if (cfg.equals(old.config())) {
                    result = new ReloadResult(true, false, old.version(), "no changes", List.of(), time.currentTimeMillis());
                    count("unchanged");
                } else {
                    RuntimeState next = builder.build(cfg, version + 1, sha256(bytes), time.currentTimeMillis(), old);
                    version++;
                    current.set(next);
                    scheduleRetirement(old, next);
                    result = new ReloadResult(true, true, next.version(), "applied", List.of(), time.currentTimeMillis());
                    count("applied");
                    log.info("config_reloaded", "trigger", trigger, "version", next.version(), "sha256", next.fileSha256().substring(0, 12),
                            "routes", cfg.routes().size());
                }
            } catch (ConfigException e) {
                List<String> errors = new ArrayList<>();
                e.issues().forEach(i -> errors.add(i.toString()));
                result = fail("rejected", errors);
                log.warn("config_reload_rejected", "trigger", trigger, "problems", errors.size(), "first", errors.isEmpty() ? "" : errors.get(0));
            } catch (IOException e) {
                result = fail("unreadable", List.of("cannot read " + file + ": " + e.getMessage()));
                log.warn("config_reload_failed", "trigger", trigger, "reason", e.getMessage());
            } catch (RuntimeException e) {
                result = fail("build_failed", List.of(e.getClass().getSimpleName() + ": " + e.getMessage()));
                log.error("config_reload_failed", e, "trigger", trigger);
            }
            lastReload = result;
            rememberFileStamp();
            return result;
        } finally {
            lock.unlock();
        }
    }

    private ReloadResult fail(String outcome, List<String> errors) {
        count("failed");
        return new ReloadResult(false, false, current.get().version(), outcome, errors, time.currentTimeMillis());
    }

    private void count(String result) {
        metrics.registry().counter("routeplane_config_reloads_total", "Configuration load attempts by result", "result").inc(result);
    }

    static List<String> frozenSectionsChanged(RouteplaneConfig a, RouteplaneConfig b) {
        List<String> changed = new ArrayList<>();
        if (!a.server().equals(b.server())) {
            changed.add("server");
        }
        if (!a.admin().equals(b.admin())) {
            changed.add("admin");
        }
        if (!a.logging().equals(b.logging())) {
            changed.add("logging");
        }
        if (!a.metrics().equals(b.metrics())) {
            changed.add("metrics");
        }
        return changed;
    }

    /** Closes what the new version dropped, after in-flight requests had time to finish (longest request timeout + margin). */
    private void scheduleRetirement(RuntimeState old, RuntimeState next) {
        List<AutoCloseable> retired = old.retiredBy(next);
        if (retired.isEmpty()) {
            return;
        }
        long graceMillis = old.config().routes().stream().mapToLong(r -> r.timeouts().request().toMillis()).max().orElse(30_000) + 5_000;
        background.schedule(() -> closeAll(retired), graceMillis, TimeUnit.MILLISECONDS);
    }

    private void closeAll(List<AutoCloseable> list) {
        for (AutoCloseable c : list) {
            try {
                c.close();
            } catch (Exception e) {
                log.warn("resource_close_failed", "error", e.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------------------------------- file watching

    public void startWatching(Duration interval) {
        background.scheduleWithFixedDelay(() -> {
            try {
                long m = Files.getLastModifiedTime(file).toMillis();
                long s = Files.size(file);
                if (watchedMtime >= 0 && (m != watchedMtime || s != watchedSize)) {
                    reload("file-watch");
                }
            } catch (IOException | RuntimeException e) {
                log.warn("config_watch_error", "error", String.valueOf(e.getMessage()));
            }
        }, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void rememberFileStamp() {
        try {
            watchedMtime = Files.getLastModifiedTime(file).toMillis();
            watchedSize = Files.size(file);
        } catch (IOException e) {
            watchedMtime = -1;
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() {
        closed = true;
        background.shutdownNow();
        RuntimeState st = current.get();
        if (st != null) {
            closeAll(st.allResources());
        }
    }

    boolean isClosed() {
        return closed;
    }
}
