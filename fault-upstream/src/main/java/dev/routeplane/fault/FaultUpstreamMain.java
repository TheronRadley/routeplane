package dev.routeplane.fault;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Command line entry point: {@code fault-upstream --id a --port 9001 --control-port 19001 [profile flags]}.
 *
 * <p>Profile flags map onto {@link FaultProfile}: {@code --delay-ms --jitter-ms --error-status --error-rate
 * --reset-rate --close-rate --partial-rate --slow-bps --payload-bytes --max-concurrency --queue-wait-ms --seed}.
 * Once started, behaviour is changed through the control API on {@code --control-port} (loopback by default):
 * {@code GET /control/stats}, {@code PUT /control/profile} (JSON patch), {@code POST /control/recover?durationMs=N},
 * {@code POST /control/reset}, {@code GET /control/requests}.
 */
public final class FaultUpstreamMain {
    private FaultUpstreamMain() {
    }

    public static void main(String[] args) throws Exception {
        String id = "upstream";
        String host = "127.0.0.1";
        int port = 9001;
        int controlPort = -1;
        Map<String, Object> patch = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals("--help") || a.equals("-h")) {
                System.out.println("usage: fault-upstream --id ID --port N [--host H] [--control-port N] [--delay-ms N] [--jitter-ms N]\n"
                        + "       [--error-status N] [--error-rate P] [--reset-rate P] [--close-rate P] [--partial-rate P]\n"
                        + "       [--slow-bps N] [--payload-bytes N] [--max-concurrency N] [--queue-wait-ms N] [--seed N]");
                return;
            }
            if (!a.startsWith("--") || i + 1 >= args.length) {
                throw new IllegalArgumentException("bad argument: " + a);
            }
            String v = args[++i];
            switch (a) {
                case "--id" -> id = v;
                case "--host" -> host = v;
                case "--port" -> port = Integer.parseInt(v);
                case "--control-port" -> controlPort = Integer.parseInt(v);
                case "--delay-ms" -> patch.put("delayMs", Long.parseLong(v));
                case "--jitter-ms" -> patch.put("jitterMs", Long.parseLong(v));
                case "--error-status" -> patch.put("errorStatus", Integer.parseInt(v));
                case "--error-rate" -> patch.put("errorRate", Double.parseDouble(v));
                case "--reset-rate" -> patch.put("resetRate", Double.parseDouble(v));
                case "--close-rate" -> patch.put("closeRate", Double.parseDouble(v));
                case "--partial-rate" -> patch.put("partialRate", Double.parseDouble(v));
                case "--slow-bps" -> patch.put("slowBytesPerSecond", Long.parseLong(v));
                case "--payload-bytes" -> {
                    patch.put("payloadMin", Integer.parseInt(v));
                    patch.put("payloadMax", Integer.parseInt(v));
                }
                case "--max-concurrency" -> patch.put("maxConcurrency", Integer.parseInt(v));
                case "--queue-wait-ms" -> patch.put("queueWaitMs", Long.parseLong(v));
                case "--seed" -> patch.put("seed", Long.parseLong(v));
                default -> throw new IllegalArgumentException("unknown option " + a);
            }
        }
        FaultProfile profile = FaultProfile.merge(FaultProfile.DEFAULT, patch);
        FaultUpstream up = FaultUpstream.start(new FaultUpstream.Options(id, host, port, controlPort, profile, 1000));
        Runtime.getRuntime().addShutdownHook(new Thread(up::close));
        System.out.println("FAULT_UPSTREAM_READY id=" + id + " port=" + up.port() + " control=" + up.controlPort());
        System.out.flush();
        Thread.currentThread().join();
    }
}
