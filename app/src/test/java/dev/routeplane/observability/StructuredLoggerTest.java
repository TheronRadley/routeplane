package dev.routeplane.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.routeplane.observability.StructuredLogger.Level;
import dev.routeplane.testing.ManualClock;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;

class StructuredLoggerTest {

    @Test
    void writesOneJsonObjectPerLineWithTimestampLevelAndEvent() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        StructuredLogger log = new StructuredLogger(Level.INFO, out, 100, new ManualClock());
        log.info("hello", "n", 3, "ok", true, "route", "api");
        log.close();
        String line = out.toString(StandardCharsets.UTF_8).trim();
        Map<String, Object> m = Json.parseObject(line);
        assertEquals("hello", m.get("event"));
        assertEquals("info", m.get("level"));
        assertEquals(3L, m.get("n"));
        assertEquals(Boolean.TRUE, m.get("ok"));
        assertEquals("api", m.get("route"));
        assertTrue(((String) m.get("ts")).endsWith("Z"));
    }

    @Test
    void redactsSensitiveKeysAndOmitsNullsAndTruncatesLongValues() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        StructuredLogger log = new StructuredLogger(Level.DEBUG, out, 100, new ManualClock());
        log.info("e", "Authorization", "Bearer abc", "api_token", "t", "Cookie", "c", "password", "p", "client_secret", "s", "X-Api-Key", "k",
                "absent", null, "long", "x".repeat(5000), "safe", "value");
        log.close();
        String text = out.toString(StandardCharsets.UTF_8);
        for (String leaked : new String[] {"Bearer abc", "\"t\"", "\"c\"", "\"p\"", "\"s\"", "\"k\""}) {
            assertFalse(text.contains(leaked), "leaked " + leaked + " in " + text);
        }
        Map<String, Object> m = Json.parseObject(text.trim());
        assertEquals("[REDACTED]", m.get("Authorization"));
        assertEquals("[REDACTED]", m.get("X-Api-Key"));
        assertFalse(m.containsKey("absent"));
        assertTrue(((String) m.get("long")).length() < 600);
        assertEquals("value", m.get("safe"));
    }

    @Test
    void errorEventsCarryTheExceptionTypeAndAMessageButNoStackTrace() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        StructuredLogger log = new StructuredLogger(Level.INFO, out, 100, new ManualClock());
        log.error("failed", new IllegalStateException("bad state"), "route", "r");
        log.close();
        Map<String, Object> m = Json.parseObject(out.toString(StandardCharsets.UTF_8).trim());
        assertEquals("IllegalStateException", m.get("error"));
        assertEquals("bad state", m.get("error_message"));
        assertNull(m.get("stack"));
    }

    @Test
    void levelsFilter() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        StructuredLogger log = new StructuredLogger(Level.WARN, out, 100, new ManualClock());
        log.debug("d");
        log.info("i");
        log.warn("w");
        log.error("e", null);
        log.close();
        List<String> lines = out.toString(StandardCharsets.UTF_8).lines().toList();
        assertEquals(2, lines.size());
        assertFalse(log.isEnabled(Level.INFO));
    }

    @Test
    void aStalledSinkNeverBlocksCallersAndOverflowIsCounted() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        OutputStream stalled = new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        StructuredLogger log = new StructuredLogger(Level.INFO, stalled, 10, new ManualClock());
        long t0 = System.nanoTime();
        for (int i = 0; i < 1000; i++) {
            log.info("spam", "i", i);
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(ms < 1000, "1000 log calls against a stalled sink took " + ms + " ms: logging must never block request threads");
        assertTrue(log.dropped() >= 900, "overflow is dropped and counted: " + log.dropped());
        release.countDown();
        log.close();
    }
}
