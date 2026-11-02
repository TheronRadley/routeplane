package dev.routeplane.observability;

import dev.routeplane.util.TimeSource;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/**
 * Asynchronous JSON-lines logger. Producers never block: lines go into a bounded queue drained by one writer thread,
 * and when the queue is full the line is dropped and counted ({@link #dropped()}).
 *
 * <p>Secrets: log statements only carry fields chosen by the calling code (request id, route, status, timings), never
 * headers or bodies. As a second line of defence, values whose key looks sensitive are replaced with
 * {@code [REDACTED]} and long values are truncated.
 */
public final class StructuredLogger implements AutoCloseable {

    public enum Level {
        DEBUG, INFO, WARN, ERROR;

        public static Level parse(String s) {
            return valueOf(s.trim().toUpperCase(Locale.ROOT));
        }
    }

    private static final int MAX_VALUE_CHARS = 512;
    private static final String[] SENSITIVE = {"authorization", "token", "password", "secret", "cookie", "api-key", "apikey", "credential"};

    private final Level min;
    private final TimeSource time;
    private final BlockingQueue<String> queue;
    private final PrintStream out;
    private final LongAdder dropped = new LongAdder();
    private final Thread writer;
    private volatile boolean closed;

    public StructuredLogger(Level min, OutputStream sink, int queueCapacity, TimeSource time) {
        this.min = min;
        this.time = time;
        this.queue = new ArrayBlockingQueue<>(queueCapacity);
        this.out = new PrintStream(sink, false, StandardCharsets.UTF_8);
        this.writer = new Thread(this::drainLoop, "routeplane-log-writer");
        this.writer.setDaemon(true);
        this.writer.start();
    }

    public static StructuredLogger toStdout(Level min) {
        return new StructuredLogger(min, System.out, 8192, TimeSource.SYSTEM);
    }

    public boolean isEnabled(Level level) {
        return level.ordinal() >= min.ordinal();
    }

    public long dropped() {
        return dropped.sum();
    }

    public void debug(String event, Object... kv) {
        log(Level.DEBUG, event, null, kv);
    }

    public void info(String event, Object... kv) {
        log(Level.INFO, event, null, kv);
    }

    public void warn(String event, Object... kv) {
        log(Level.WARN, event, null, kv);
    }

    public void error(String event, Throwable error, Object... kv) {
        log(Level.ERROR, event, error, kv);
    }

    /** @param kv alternating field names and values */
    public void log(Level level, String event, Throwable error, Object... kv) {
        if (!isEnabled(level) || closed) {
            return;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ts", Instant.ofEpochMilli(time.currentTimeMillis()).toString());
        m.put("level", level.name().toLowerCase(Locale.ROOT));
        m.put("event", event);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            String key = String.valueOf(kv[i]);
            if (kv[i + 1] != null) {
                m.put(key, sanitize(key, kv[i + 1]));
            }
        }
        if (error != null) {
            m.put("error", error.getClass().getSimpleName());
            m.put("error_message", truncate(String.valueOf(error.getMessage())));
        }
        if (!queue.offer(Json.write(m))) {
            dropped.increment();
        }
    }

    static Object sanitize(String key, Object value) {
        String k = key.toLowerCase(Locale.ROOT);
        for (String s : SENSITIVE) {
            if (k.contains(s)) {
                return "[REDACTED]";
            }
        }
        if (value instanceof CharSequence cs) {
            return truncate(cs.toString());
        }
        return value;
    }

    private static String truncate(String s) {
        return s.length() <= MAX_VALUE_CHARS ? s : s.substring(0, MAX_VALUE_CHARS) + "...";
    }

    private void drainLoop() {
        try {
            while (!closed || !queue.isEmpty()) {
                String line = queue.poll(200, TimeUnit.MILLISECONDS);
                if (line == null) {
                    continue;
                }
                out.println(line);
                int batch = 0;
                while (batch++ < 256 && (line = queue.poll()) != null) {
                    out.println(line);
                }
                out.flush();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            out.flush();
        }
    }

    /** Flushes pending lines (bounded wait) and stops the writer thread. */
    @Override
    public void close() {
        closed = true;
        try {
            writer.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
