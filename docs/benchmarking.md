# Benchmarking and experiments

The research suite produces *measurements*, not claims: every figure in a report is computed from the per-run JSON files that the
runner writes. Nothing is simulated or estimated. Design rationale: [adr/0002-experiment-design.md](adr/0002-experiment-design.md).

## Components

| component | what it does |
|---|---|
| `fault-upstream` | test-only upstream with delays, error statuses, resets, early closes, truncated and throttled bodies, capacity limits, refusal (it stops listening), gradual recovery and arbitrary payload sizes. Seeded: the same seed and arrival order give the same sequence of faults. Controlled at run time through a **separate, loopback-only control API** (`PUT /control/profile`, `POST /control/recover?durationMs=`, `POST /control/reset`, `GET /control/stats`, `GET /control/requests`). |
| `benchmarks` `LoadGenerator` | **open-loop** constant-rate generator (intended send times independent of responses, latency measured from the intended time, drops counted, generator lag and CPU reported). |
| `benchmarks` `ExperimentRunner` | per run: starts the upstreams and the proxy as **separate JVM processes**, warms up, runs a fixed window, applies the fault timeline, samples CPU/RSS (`/proc`) and the proxy's `/metrics` once per second, collects breaker history, writes one JSON file. |
| `benchmarks` `ReportGenerator` | aggregates runs into `results.json`, `results.csv`, `report.md`, `report.html` and `charts/*.svg`. |

## Running

```bash
./gradlew :benchmarks:run --args="list"                                      # scenarios
experiments/scripts/smoke.sh                                                 # 15 s check that everything works here
./gradlew :benchmarks:run --args="run --scenario experiments/scenarios/basic.yaml --out build/basic --runs 1 --rates 500"
./gradlew :benchmarks:run --args="matrix --out experiments/results/mine"     # everything (about an hour)
./gradlew :benchmarks:run --args="report --in experiments/results/mine"      # regenerate reports from recorded runs
```

`:benchmarks:run` first builds the proxy and the fault upstream with `installDist` and launches them from their start scripts. Override
the launch commands with `ROUTEPLANE_PROXY_CMD` / `ROUTEPLANE_UPSTREAM_CMD` (space-separated command lines), e.g. to benchmark a
different JVM or flags. Options after `run`/`matrix` override the scenario files (`--rates`, `--warmup`, `--duration`, `--runs`,
`--only id,id`), which is how short smoke runs are made. **Keep the machine otherwise idle**: the generator, the proxy and the
upstreams share its CPUs.

## Scenarios (`experiments/scenarios/*.yaml`)

Steady-state experiments run each offered rate of **100, 500, 1,000, 2,500, 5,000 and 10,000 requests/s** (warm-up 15 s, window 10 s,
3 runs per rate up to 2,500 and 1 run above):

| id | what it isolates |
|---|---|
| `direct` | baseline: the generator talks to one fault-injection upstream, no proxy |
| `basic` | proxy with one upstream and nothing else: the cost of the hop |
| `weighted` | three upstreams, smooth weighted round-robin 50/30/20 |
| `breaker` | weighted + circuit breaker + passive health on healthy upstreams: the cost of the machinery |
| `shadow` | `basic` + 25% shadowing |
| `breaker-shadow` | `breaker` + 25% shadowing |

Failure scenarios run at a fixed 500 requests/s (warm-up 8 s, window 40 s, 2 runs per variant) with a fault applied at 8 s and lifted at
24 s, comparing variants of the same topology:

| id | fault | variants |
|---|---|---|
| `fail-outage` | upstream a stops listening (connections refused) | baseline / resilient (retries + breaker + passive health) |
| `fail-503` | upstream a answers 503 to everything | baseline / resilient |
| `fail-slow` | upstream a becomes 500 ms slower (response timeout 300 ms) | baseline / resilient |
| `fail-capacity` | upstream a's capacity is cut to about half of its share | baseline / resilient |
| `fail-gradual-recovery` | a fails from 8 s, then recovers linearly over 15 s from 16 s | baseline / resilient |
| `fail-shadow-down` | the shadow target stops listening | no-shadow / shadow |
| `fail-shadow-saturated` | the shadow target takes 2 s per request, small queue | no-shadow / shadow |
| `fail-retry-amplification` | all upstreams fail 50% of requests with 503 | no-retry / retry-unbudgeted / retry-budgeted |

### Scenario file format

```yaml
id: my-experiment
description: ...
kind: steady | failure
direct: false                  # true: no proxy, the generator hits the first upstream
upstreams:                     # fault-injection upstreams to start; ${UP_<ID>} holds each one's port in the proxy configuration
  - {id: a, profile: {delayMs: 5, jitterMs: 5, payloadMin: 1024, payloadMax: 1024}}
proxy:
  jvmOptions: ["-Xms256m", "-Xmx512m"]          # applied through JAVA_OPTS to the start script
  config: |                                     # complete proxy configuration (uses ${PROXY_PORT}, ${ADMIN_PORT}, ${UP_A}...)
    ...
  # or, for failure scenarios, a shared base plus route-level additions per variant:
  # base: |   ...configuration up to the route's upstreams...
  # variants: {baseline: "timeouts: {...}", resilient: "timeouts: ...\nretry: ..."}
load: {path: /api/x, method: GET, bodyBytes: 0, rates: [100, 500], rate: 500, warmup: 15s, duration: 10s, timeout: 5s, maxInFlight: 3000}
runs: 3
highRateRuns: 1                # runs for tiers >= 5000 rps
timeline:                      # failure scenarios, relative to the start of the window
  - {at: 8s, upstream: a, set: {refuse: true}, label: "a down"}
  - {at: 24s, upstream: a, set: {refuse: false}, label: "a restored", recovery: true}
  - {at: 16s, upstream: a, recover: 15s, label: "gradual recovery"}      # alternative: a linear recovery ramp
```

Unknown keys are errors. Fault profile fields: `delayMs`, `jitterMs`, `errorStatus`, `errorRate`, `resetRate`, `closeRate`, `partialRate`,
`slowBytesPerSecond`, `payloadMin`, `payloadMax`, `maxConcurrency`, `queueWaitMs`, `refuse`, `seed`.

## What is measured

Per run (JSON field in parentheses): offered/achieved/successful rate; success and error rates split by **origin** - upstream-originated
(`upstreamOriginatedPct`), proxy-generated and upstream-caused (`proxyUpstreamCausedPct`: 502/504 for connect failures, resets, timeouts),
proxy-generated protective (`proxyProtectivePct`: breaker open, no upstream, limits), transport (`transportPct`: client-side timeouts and
connection errors); latency p50/p90/p95/p99/p99.9/max for all responses and for successes (`latencyAll`, `latencyOk`; `null` when the
sample count is too small - p99 needs 1,000 responses, p99.9 needs 10,000); proxy CPU seconds, CPU % of the machine and CPU ms per request,
RSS, heap, GC count and time, threads; **connections** as the kernel sees them (`tcpDownstreamMax`, `tcpUpstreamMax`, from `/proc/net/tcp`);
shadow queue depth and the shadow counters; **retry amplification** (upstream attempts per client request); breaker transitions with
timestamps; **recovery time** (from the recovery event until success stays >= 99% for 3 seconds); per-second series of everything; the
environment (CPU model, cores, memory, cgroup limits, Java versions, git commit) and the generator's own lag and CPU.

## How to read a report

* Compare experiments **within one campaign** (same machine, same minute); do not compare against other hardware.
* Look at the flags before the numbers: `SATURATED`, `GENERATOR-LAG`, `MACHINE-CPU-SATURATED`, `FEW-SAMPLES`, `HIGH-VARIANCE`.
  A saturated row says where the *system under test plus load generator* fell over, not what the proxy alone could do.
* The generator costs roughly 0.8 ms of CPU per request (JDK client). On 2 vCPUs, rates of a few thousand requests per second are limited
  by the generator, the proxy and the upstream processes competing for the same cores. This is stated in every report's *Limitations*.
* Tail latency on a shared 2-core machine is noisy: judge p99 by the min-max range across runs, not by one figure.

## Reproducing the committed results

The campaign in `experiments/results/` was produced with:

```bash
experiments/scripts/run-matrix.sh experiments/results/campaign-2026-11-02
```

on the machine described at the top of its `report.md` (the environment table is generated, not typed). Re-running on your hardware
produces a new campaign directory and a report that can be diffed against it; expect different absolute numbers.
