package dev.routeplane.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.xml.sax.InputSource;

class SamplerAndSvgTest {

    @Test
    void parsesPrometheusText() {
        Map<String, Double> m = SystemSampler.parsePrometheus("""
                # HELP a x
                # TYPE a counter
                routeplane_requests_total{route="api",status_class="2xx"} 12
                routeplane_up 1
                weird_line
                histo_bucket{le="+Inf"} +Inf
                value_with_exp 1.5E-4
                """);
        assertEquals(12.0, m.get("routeplane_requests_total{route=\"api\",status_class=\"2xx\"}"));
        assertEquals(1.0, m.get("routeplane_up"));
        assertEquals(Double.POSITIVE_INFINITY, m.get("histo_bucket{le=\"+Inf\"}"));
        assertEquals(1.5e-4, m.get("value_with_exp"));
        assertEquals(4, m.size());
    }

    @Test
    void readsProcFilesOfTheCurrentProcess() {
        Assumptions.assumeTrue(Files.exists(Path.of("/proc/self/stat")), "needs Linux /proc");
        long pid = ProcessHandle.current().pid();
        long burn = 0;
        for (long i = 0; i < 300_000_000L; i++) {
            burn += i ^ (i >>> 3);
        }
        assertTrue(burn != 42);
        assertTrue(SystemSampler.cpuSeconds(pid) > 0);
        assertTrue(SystemSampler.rss(pid) > 10_000_000);
        long[] sys = SystemSampler.readSystemCpu();
        assertTrue(sys != null && sys[1] > sys[0] && sys[0] > 0);
    }

    @Test
    void linearAxesUseRoundTicks() {
        assertEquals(List.of(0.0, 10.0, 20.0, 30.0, 40.0), Svg.linearTicks(0, 40, 7));
        assertEquals(List.of(0.0, 5.0, 10.0, 15.0, 20.0), Svg.linearTicks(0, 20, 7));
        assertEquals(List.of(0.0, 20.0, 40.0, 60.0, 80.0, 100.0), Svg.linearTicks(0, 100, 5));
        assertEquals(List.of(0.0, 0.5, 1.0), Svg.linearTicks(0, 1, 2));
    }

    @Test
    void logAxesUseOneTwoFiveTicks() {
        assertEquals(List.of(1.0, 2.0, 5.0, 10.0, 20.0, 50.0, 100.0), Svg.logTicks(1, 100));
        assertEquals(List.of(100.0, 200.0, 500.0, 1000.0, 2000.0, 5000.0, 10000.0), Svg.logTicks(100, 10000));
        assertEquals(List.of(0.1, 0.2, 0.5, 1.0, 2.0, 5.0, 10.0), Svg.logTicks(0.1, 10).stream().map(v -> Math.round(v * 1000) / 1000.0).toList());
        assertEquals("0.5", Svg.tick(0.5));
        assertEquals("2.5", Svg.tick(2.5));
        assertEquals("10", Svg.tick(10));
        assertEquals("5k", Svg.tick(5000));
    }

    @Test
    void chartsAreWellFormedXml() throws Exception {
        List<Svg.Series> s = List.of(
                new Svg.Series("a & <b>", List.of(new Svg.Point(100, 5), new Svg.Point(1000, 50), new Svg.Point(10000, 900))),
                new Svg.Series("with gaps", List.of(new Svg.Point(100, 3), new Svg.Point(1000, Double.NaN), new Svg.Point(10000, 40)), true));
        for (boolean logX : new boolean[] {true, false}) {
            for (boolean logY : new boolean[] {true, false}) {
                String svg = Svg.lineChart("title \"q\"", "x", "y", s, logX, logY, List.of(new Svg.Marker(5000, "event")), null, null);
                var doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new InputSource(new StringReader(svg)));
                assertEquals("svg", doc.getDocumentElement().getNodeName());
                assertTrue(svg.contains("polyline"));
            }
        }
        String empty = Svg.lineChart("none", "x", "y", List.of(), false, false, null, null, null);
        assertTrue(empty.contains("no data"));
    }
}
