package dev.routeplane.cli;

import dev.routeplane.BuildInfo;
import dev.routeplane.RouteplaneApp;
import dev.routeplane.config.ConfigException;
import dev.routeplane.config.ConfigLoader;
import dev.routeplane.config.RouteplaneConfig;
import dev.routeplane.routing.PolicyRegistry;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

/**
 * Command line: {@code serve [--config FILE]}, {@code validate [--config FILE]}, {@code version}, {@code help}.
 * Exit codes: 0 success, 1 invalid configuration or runtime failure, 2 usage error.
 */
public final class Cli {
    public static final String DEFAULT_CONFIG = "config/routeplane.yaml";

    private Cli() {
    }

    static final String USAGE = """
            Routeplane - programmable HTTP/1.1 reverse proxy

            usage:
              routeplane serve    [--config FILE]   start the proxy and the admin listener (default: config/routeplane.yaml)
              routeplane validate [--config FILE]   check a configuration without starting anything
              routeplane version                    print version information
              routeplane help

            Configuration reference: docs/configuration.md
            """;

    public static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length == 0) {
            err.print(USAGE);
            return 2;
        }
        String cmd = args[0];
        switch (cmd) {
            case "help", "--help", "-h" -> {
                out.print(USAGE);
                return 0;
            }
            case "version", "--version" -> {
                out.println("routeplane " + BuildInfo.version());
                out.println("java " + System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")");
                out.println("os " + System.getProperty("os.name") + " " + System.getProperty("os.version") + " " + System.getProperty("os.arch"));
                for (String w : dev.routeplane.JdkIssues.warnings()) {
                    out.println("WARNING: " + w);
                }
                return 0;
            }
            case "validate" -> {
                return validate(args, out, err);
            }
            case "serve" -> {
                return serve(args, out, err);
            }
            default -> {
                err.println("unknown command '" + cmd + "'\n");
                err.print(USAGE);
                return 2;
            }
        }
    }

    private static Path configArg(String[] args, PrintStream err) {
        Path config = Path.of(DEFAULT_CONFIG);
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("--config") || args[i].equals("-c")) {
                if (i + 1 >= args.length) {
                    err.println("--config needs a file argument");
                    return null;
                }
                config = Path.of(args[++i]);
            } else {
                err.println("unknown argument '" + args[i] + "'");
                return null;
            }
        }
        return config;
    }

    private static int validate(String[] args, PrintStream out, PrintStream err) {
        Path config = configArg(args, err);
        if (config == null) {
            return 2;
        }
        try {
            RouteplaneConfig cfg = new ConfigLoader(PolicyRegistry.load()).loadFile(config, System::getenv);
            int upstreams = cfg.routes().stream().mapToInt(r -> r.upstreams().size() + (r.fallback() == null ? 0 : r.fallback().upstreams().size())).sum();
            out.println("OK: " + config + " is valid (" + cfg.routes().size() + " route" + (cfg.routes().size() == 1 ? "" : "s") + ", " + upstreams
                    + " upstream" + (upstreams == 1 ? "" : "s") + ")");
            if (cfg.routes().stream().anyMatch(r -> r.shadow() != null)) {
                out.println("note: shadowing is enabled on: " + cfg.routes().stream().filter(r -> r.shadow() != null).map(RouteplaneConfig.RouteConfig::id).toList());
            }
            return 0;
        } catch (ConfigException e) {
            err.println(config + " is invalid: " + e.getMessage());
            return 1;
        } catch (IOException e) {
            err.println("cannot read " + config + ": " + e.getMessage());
            return 1;
        }
    }

    private static int serve(String[] args, PrintStream out, PrintStream err) {
        Path config = configArg(args, err);
        if (config == null) {
            return 2;
        }
        if (!Files.isReadable(config)) {
            err.println("configuration file not found or unreadable: " + config.toAbsolutePath());
            return 1;
        }
        RouteplaneApp app;
        try {
            app = RouteplaneApp.start(config);
        } catch (ConfigException e) {
            err.println(config + " is invalid: " + e.getMessage());
            return 1;
        } catch (IOException | RuntimeException e) {
            err.println("failed to start: " + e.getMessage());
            return 1;
        }
        CountDownLatch stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            app.close(); // drains for server.shutdownGracePeriod
            stopped.countDown();
        }, "routeplane-shutdown"));
        err.println("routeplane " + BuildInfo.version() + " listening on port " + app.proxyPort()
                + (app.adminPort() >= 0 ? ", admin on port " + app.adminPort() : "") + " (Ctrl+C to stop)");
        try {
            stopped.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return 0;
    }
}
