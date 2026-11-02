package dev.routeplane.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.routeplane.bench.RunSummary.EventMark;
import dev.routeplane.bench.RunSummary.Latency;
import dev.routeplane.bench.RunSummary.SecondPoint;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.xml.sax.InputSource;

/**
 * Report generation is tested on SYNTHETIC run files built here. They exist only inside the test's temporary directory;
 * nothing synthetic is ever written to experiments/results.
 */
class ReportGeneratorTest {

    private static RunSummary run(String scenario, String variant, String kind, int tier, int run, double p50, double p99, double achieved, List<String> flags) {
        RunSummary r = new RunSummary();
        r.scenario = scenario;
        r.variant = variant;
        r.kind = kind;
        r.tierRps = tier;
        r.run = run;
        r.windowSeconds = 10;
        r.offered = tier * 10L;
        r.completed = (long) (achieved * 10);
        r.ok = r.completed;
        r.direct = scenario.equals("direct");
        r.offeredRps = tier;
        r.achievedRps = achieved;
        r.okRps = achieved;
        r.successRatePct = 100.0 * r.ok / r.offered;
        r.latencyAll = lat(p50, p99);
        r.latencyOk = lat(p50, p99);
        r.proxyCpuPctOfMachine = 12.5;
        r.proxyCpuMsPerRequest = 0.4;
        r.flags = new ArrayList<>(flags);
        r.description = "synthetic fixture " + scenario;
        for (int s = 0; s < 10; s++) {
            SecondPoint p = new SecondPoint();
            p.t = s;
            p.offered = tier;
            p.completed = tier;
            p.ok = tier;
            p.p50ms = p50;
            p.p99ms = p99;
            r.series.add(p);
        }
        return r;
    }

    private static Latency lat(double p50, double p99) {
        Latency l = new Latency();
        l.samples = 5000;
        l.p50ms = p50;
        l.p90ms = p50 * 2;
        l.p95ms = p50 * 3;
        l.p99ms = p99;
        l.maxMs = p99 * 2;
        return l;
    }

    private static void write(Path dir, RunSummary r) throws Exception {
        Files.createDirectories(dir.resolve("runs"));
        Files.writeString(dir.resolve("runs").resolve(r.scenario + "_" + r.variant + "__" + r.tierRps + "__" + r.run + ".json"), ExperimentRunner.GSON.toJson(r));
    }

    @Test
    void producesJsonCsvChartsMarkdownAndHtmlFromRuns(@TempDir Path dir) throws Exception {
        for (int run = 1; run <= 3; run++) {
            for (int tier : new int[] {100, 500}) {
                write(dir, run("direct", "default", "steady", tier, run, 8 + run, 12 + run, tier, List.of()));
                write(dir, run("basic", "default", "steady", tier, run, 10 + run, 20 + run, tier, List.of()));
            }
            write(dir, run("basic", "default", "steady", 5000, run, 400, 3000, 2900, List.of("SATURATED: achieved 2900 of 5000 rps offered")));
        }
        RunSummary f = run("fail-x", "resilient", "failure", 500, 1, 10, 50, 500, List.of());
        for (int s = 8; s < 24; s++) {
            f.series.get(Math.min(s, 9)).ok = 100; // keep within bounds: only the shape matters
        }
        EventMark down = new EventMark();
        down.atSeconds = 3;
        down.label = "a down";
        down.detail = "a: {refuse=true}";
        EventMark up = new EventMark();
        up.atSeconds = 7;
        up.label = "a restored";
        f.events = List.of(down, up);
        write(dir, f);
        Files.writeString(dir.resolve("campaign.json"), "{\"environment\":{\"os\":\"test-os\",\"availableProcessors\":2},\"command\":\"--synthetic\"}");

        ReportGenerator.generate(dir, dir);

        for (String name : new String[] {"results.json", "results.csv", "report.md", "report.html", "charts/latency-p50.svg", "charts/latency-p99.svg", "charts/throughput.svg",
                "charts/success.svg", "charts/proxy-cpu.svg", "charts/fail-x-success.svg", "charts/fail-x-p99.svg"}) {
            assertTrue(Files.size(dir.resolve(name)) > 100, name + " should exist and not be empty");
        }
        String md = Files.readString(dir.resolve("report.md"));
        assertTrue(md.contains("## Environment") && md.contains("test-os"));
        assertTrue(md.contains("## Steady-state experiments") && md.contains("### basic"));
        assertTrue(md.contains("SATURATED"), "saturation is surfaced in the report");
        assertTrue(md.contains("Overhead relative to the direct baseline"), "overhead table needs the direct baseline");
        assertTrue(md.contains("## Failure scenarios") && md.contains("before fault") && md.contains("a down"));
        assertTrue(md.contains("![latency-p99](charts/latency-p99.svg)"));
        assertTrue(md.contains("## Limitations"));
        String html = Files.readString(dir.resolve("report.html"));
        assertTrue(html.startsWith("<!doctype html>") && html.contains("<svg") && html.contains("<table>"));
        for (String svg : new String[] {"charts/latency-p99.svg", "charts/fail-x-success.svg"}) {
            var doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new InputSource(new StringReader(Files.readString(dir.resolve(svg)))));
            assertEquals("svg", doc.getDocumentElement().getNodeName());
        }
        List<String> csv = Files.readAllLines(dir.resolve("results.csv"));
        assertEquals(1 + 3 * 5 + 1, csv.size(), "header plus one row per recorded run");
        assertTrue(csv.get(0).startsWith("scenario,variant,tier_rps,run,"));
    }

    @Test
    void aggregatesUseMediansAndKeepMinMax(@TempDir Path dir) throws Exception {
        for (int run = 1; run <= 3; run++) {
            write(dir, run("basic", "default", "steady", 500, run, 10, new double[] {20, 80, 30}[run - 1], 500, List.of()));
        }
        var groups = ReportGenerator.aggregate(List.of(
                run("basic", "default", "steady", 500, 1, 10, 20, 500, List.of()), run("basic", "default", "steady", 500, 2, 10, 80, 500, List.of()),
                run("basic", "default", "steady", 500, 3, 10, 30, 500, List.of())));
        var agg = groups.get("basic").get(0);
        assertEquals(30.0, agg.median(r -> r.latencyAll.p99ms));
        assertEquals(20.0, agg.min(r -> r.latencyAll.p99ms));
        assertEquals(80.0, agg.max(r -> r.latencyAll.p99ms));
        assertNull(agg.median(r -> null), "no values, no median");
        assertNotNull(agg.first());
    }

    @Test
    void aSecondBelongsToThePhaseThatContainsItsMidpoint() {
        RunSummary r = new RunSummary();
        for (int t = 0; t < 10; t++) {
            SecondPoint p = new SecondPoint();
            p.t = t;
            p.offered = 100;
            p.ok = t < 3 ? 100 : t < 7 ? 20 : 100; // the fault is applied at 3.03 s and lifted at 7.03 s
            p.p99ms = t < 3 ? 10.0 : t < 7 ? 900.0 : 12.0;
            r.series.add(p);
        }
        // Bucket [3, 4) holds the fault start (3.03 s): it is a fault second, not a "before" second.
        assertEquals(100.0, ReportGenerator.phase(r, 0, 3.03, p -> p.ok), "no failures before the fault");
        assertEquals(20.0, ReportGenerator.phase(r, 3.03, 7.03, p -> p.ok), "the whole fault window is degraded");
        assertEquals(100.0, ReportGenerator.phase(r, 7.03, 1e9, p -> p.ok), "bucket [7, 8) holds the recovery event and counts as after");
        assertEquals(10.0, ReportGenerator.phaseP99(r, 0, 3.03));
        assertEquals(900.0, ReportGenerator.phaseP99(r, 3.03, 7.03));
        assertNull(ReportGenerator.phase(r, 20, 30, p -> p.ok), "an empty phase has no value");
    }

    @Test
    void recoveryTimeIsMeasuredFromTheRecoveryEventToThreeGoodSeconds() {
        Scenario s = ScenarioLoader.parse("""
                id: f
                kind: failure
                direct: true
                upstreams: [{id: a}]
                load: {rate: 100, duration: 20s}
                timeline:
                  - {at: 3s, upstream: a, set: {refuse: true}, label: down}
                  - {at: 10s, upstream: a, set: {refuse: false}, label: up, recovery: true}
                """, "f");
        RunSummary r = new RunSummary();
        EventMark up = new EventMark();
        up.atSeconds = 10.0;
        up.label = "up";
        r.events = List.of(up);
        for (int t = 0; t < 20; t++) {
            SecondPoint p = new SecondPoint();
            p.t = t;
            p.offered = 100;
            p.ok = t < 3 ? 100 : t < 14 ? 40 : 100; // failing until second 13, healthy again from second 14
            r.series.add(p);
        }
        ExperimentRunner.computeRecovery(s, r);
        assertEquals(4.0, r.recoverySeconds, "second 14 is the first of three consecutive good seconds, 4 s after the event at 10 s");
        // never recovers inside the window
        r.recoverySeconds = null;
        r.series.forEach(p -> p.ok = 10);
        ExperimentRunner.computeRecovery(s, r);
        assertNull(r.recoverySeconds);
        assertTrue(r.recoveryNote.contains("did not return"));
    }
}
