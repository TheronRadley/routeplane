package dev.routeplane;

import dev.routeplane.cli.Cli;

/** Entry point: {@code routeplane serve|validate|version}. */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        // Needed only for server.hostHeader: preserve; must be set before the JDK HTTP client class is initialised.
        if (System.getProperty("jdk.httpclient.allowRestrictedHeaders") == null) {
            System.setProperty("jdk.httpclient.allowRestrictedHeaders", "host");
        }
        int code = Cli.run(args, System.out, System.err);
        System.out.flush();
        System.exit(code);
    }
}
