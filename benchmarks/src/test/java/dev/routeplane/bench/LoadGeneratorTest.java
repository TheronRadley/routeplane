package dev.routeplane.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.routeplane.fault.FaultProfile;
import dev.routeplane.fault.FaultUpstream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(120)
class LoadGeneratorTest {
    private static final LoadGenerator.Hooks NOOP = new LoadGenerator.Hooks() {
        @Override
        public void windowStarted(long nanoTime) {
        }

        @Override
        public void windowEnded(long nanoTime) {
        }
    };

    private static LoadGenerator.Config cfg(FaultUpstream up, double rate, int warmupMs, int durationMs, int maxInFlight) {
        return new LoadGenerator.Config(URI.create("http://127.0.0.1:" + up.port() + "/x"), "GET", 0, rate, Duration.ofMillis(warmupMs), Duration.ofMillis(durationMs), Duration.ofSeconds(5), maxInFlight);
    }

    @Test
    void offersExactlyTheScheduledNumberOfRequestsAndMeasuresServiceTime() throws Exception {
        try (FaultUpstream up = FaultUpstream.start(FaultUpstream.Options.of("a", 0).withProfile(FaultProfile.DEFAULT.with("delayMs", 40)))) {
            LoadGenerator.Result r = new LoadGenerator().run(cfg(up, 200, 500, 3000, 1000), NOOP);
            assertEquals(600, r.offered, "200 rps over 3 s");
            assertEquals(600, r.completed);
            assertEquals(600, r.ok);
            assertEquals(0, r.dropped);
            assertNotNull(r.latencyAll.p50ms);
            assertTrue(r.latencyAll.p50ms >= 40 && r.latencyAll.p50ms < 80, "p50 " + r.latencyAll.p50ms);
            assertEquals(3, r.series.size());
            assertTrue(r.series.stream().allMatch(p -> p.offered == 200), "every second carries its share of the schedule");
            assertNull(r.latencyAll.p999ms, "600 samples are too few for p99.9");
            assertNull(r.latencyAll.p99ms, "and too few for p99 (needs 1000)");
        }
    }

    @Test
    void stallsInTheSystemAreNotHiddenByCoordinatedOmission() throws Exception {
        try (FaultUpstream up = FaultUpstream.start(FaultUpstream.Options.of("a", 0).withProfile(FaultProfile.DEFAULT.with("delayMs", 5)))) {
            Thread stall = Thread.ofPlatform().start(() -> {
                try {
                    Thread.sleep(1500); // 1 s into the 3 s window (0.5 s warm-up)
                    up.setProfile(up.profile().with("delayMs", 1000));
                    Thread.sleep(1000);
                    up.setProfile(up.profile().with("delayMs", 5));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            LoadGenerator.Result r = new LoadGenerator().run(cfg(up, 200, 500, 3000, 5000), NOOP);
            stall.join();
            assertEquals(600, r.offered);
            assertEquals(600, r.completed, "requests sent during the stall are still counted, not skipped");
            // About a third of the schedule fell into the stalled second. A closed-loop client with a few connections would have
            // recorded only a handful of slow requests; the open-loop schedule records all of them.
            assertNotNull(r.latencyAll.p90ms);
            assertTrue(r.latencyAll.p90ms > 500, "p90 must reflect the stall: " + r.latencyAll.p90ms);
            assertTrue(r.latencyAll.maxMs >= 900);
        }
    }

    @Test
    void whenTooManyRequestsAreInFlightTheDropsAreCountedNotHidden() throws Exception {
        try (FaultUpstream up = FaultUpstream.start(FaultUpstream.Options.of("a", 0).withProfile(FaultProfile.DEFAULT.with("delayMs", 400)))) {
            LoadGenerator.Result r = new LoadGenerator().run(cfg(up, 300, 200, 2000, 20), NOOP);
            assertTrue(r.dropped > 100, "dropped " + r.dropped);
            assertEquals(r.offered, r.completed + r.dropped, "every offered request is either completed or counted as dropped by the generator");
            assertEquals(r.dropped, r.outcomes.get("dropped_by_generator"));
        }
    }

    @Test
    void windowHooksFireOnceEachInOrder() throws Exception {
        try (FaultUpstream up = FaultUpstream.start(FaultUpstream.Options.of("a", 0))) {
            List<String> calls = new java.util.concurrent.CopyOnWriteArrayList<>();
            new LoadGenerator().run(cfg(up, 100, 300, 1000, 100), new LoadGenerator.Hooks() {
                @Override
                public void windowStarted(long nanoTime) {
                    calls.add("start");
                }

                @Override
                public void windowEnded(long nanoTime) {
                    calls.add("end");
                }
            });
            assertEquals(List.of("start", "end"), calls);
        }
    }

    @Test
    void minimumSampleRulesMatchTheDocumentedOnes() {
        assertEquals(20, LoadGenerator.minSamples(0.5));
        assertEquals(100, LoadGenerator.minSamples(0.9));
        assertEquals(200, LoadGenerator.minSamples(0.95));
        assertEquals(1000, LoadGenerator.minSamples(0.99));
        assertEquals(10000, LoadGenerator.minSamples(0.999));
    }

    // ------------------------------------------------------------------------------------ classification

    private record Stub(int status, Map<String, List<String>> hdrs) implements HttpResponse<Void> {
        @Override
        public int statusCode() {
            return status;
        }

        @Override
        public HttpRequest request() {
            return null;
        }

        @Override
        public Optional<HttpResponse<Void>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(hdrs, (k, v) -> true);
        }

        @Override
        public Void body() {
            return null;
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return URI.create("http://x/");
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }

    @Test
    void outcomesAreClassifiedByOrigin() {
        assertEquals("ok", LoadGenerator.classify(new Stub(200, Map.of()), null));
        assertEquals("ok", LoadGenerator.classify(new Stub(302, Map.of()), null));
        assertEquals("http_4xx", LoadGenerator.classify(new Stub(404, Map.of()), null));
        assertEquals("upstream_5xx", LoadGenerator.classify(new Stub(503, Map.of("x-fault", List.of("error"))), null), "an upstream 503 is not proxy-generated");
        assertEquals("proxy:breaker_open", LoadGenerator.classify(new Stub(503, Map.of("x-routeplane-error", List.of("breaker_open"))), null));
        assertEquals("proxy:upstream_timeout", LoadGenerator.classify(new Stub(504, Map.of("X-Routeplane-Error", List.of("upstream_timeout"))), null));
        assertEquals("timeout", LoadGenerator.classify(null, new CompletionException(new HttpTimeoutException("t"))));
        assertEquals("timeout", LoadGenerator.classify(null, new TimeoutException()));
        assertEquals("connect_error", LoadGenerator.classify(null, new java.net.ConnectException("refused")));
        assertEquals("io_error", LoadGenerator.classify(null, new IOException("reset")));
        assertTrue(LoadGenerator.classify(null, new IllegalStateException("x")).startsWith("error:"));
    }
}
