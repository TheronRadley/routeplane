# Routeplane

A programmable reverse proxy for resilient routing, circuit breaking, traffic shadowing, and latency benchmarking under load.

Routeplane is an **HTTP/1.1 reverse proxy and experimental traffic-routing platform** written in Java 21 on the JDK's own HTTP
server and client (virtual threads, no framework). It is built to answer a research question with real measurements:

> How do routing policies, circuit breakers, retries and traffic shadowing affect latency, throughput and reliability under
> increasing load and controlled upstream failures?

> **Status: working, tested, not production-ready.** It has never carried production traffic and makes no such claim. Read
> [docs/security.md](docs/security.md) (threat model and residual risks) and [Verification status](#verification-status) first.

## What it does

* **Proxy** - GET/POST/PUT/PATCH/DELETE/HEAD/OPTIONS; streaming request and response bodies with backpressure; keep-alive and per-upstream
  connection pools; per-route connect/response/overall deadlines; correct status propagation; correlation ids; header/body limits;
  hop-by-hop stripping; a forwarding-header policy that never trusts client-supplied values; graceful drain; malformed requests,
  early disconnects and slow clients handled (and tested). No hand-written HTTP parsing.
* **Routing** - exact/prefix path, method, header and query matching evaluated by priority; weighted round-robin, weighted random,
  round-robin, least-active and deterministic (rendezvous) hash policies; fallback upstreams; a custom-policy SPI with no networking types.
* **Resilience** - per-upstream circuit breakers (CLOSED/OPEN/HALF_OPEN, sliding count or time window, bounded probes, atomic transitions);
  bounded retries with exponential backoff, jitter and a retry budget; active and passive health checks; per-upstream concurrency
  limits and overload rejection; retry-amplification metrics.
* **Shadowing** - opt-in per route; sampled (random or deterministic); bounded queues, workers and timeouts with explicit drop behaviour;
  sanitised headers; optional response comparison; the primary response can never change.
* **Operations** - strict YAML configuration with positioned errors, environment substitution, atomic reload with rollback, version reporting;
  a separate admin listener (loopback by default; token + TLS required for remote) with health, routes, upstreams, circuits, config, reload and
  Prometheus `/metrics`; JSON logs without secrets.
* **Research suite** - a test-only fault-injection upstream (delays, 5xx, resets, truncation, throttling, capacity limits, refusal,
  gradual recovery; seeded and deterministic) and an **open-loop** experiment runner that produces JSON, CSV, Markdown and HTML reports
  with charts from real runs: [docs/benchmarking.md](docs/benchmarking.md).

## Quick start

Requirements: **JDK 21+** (use 21, or 25.0.3 or newer - see [docs/development.md](docs/development.md) for a known JDK bug) and Linux/macOS.

```bash
git clone https://github.com/TheronRadley/routeplane.git && cd routeplane

./gradlew build                                                 # compile and run every test
./gradlew run --args="version"
./gradlew run --args="validate --config config/routeplane.yaml"

experiments/scripts/dev-up.sh                                   # 3 local fault-injection upstreams + the proxy (no cloud needed)
curl -i http://127.0.0.1:8080/api/hello                         # through the proxy, weighted 70/30 between two upstreams
curl http://127.0.0.1:9090/health/ready                         # admin listener (loopback only by default)
curl http://127.0.0.1:9090/admin/routes
curl http://127.0.0.1:9090/metrics | grep routeplane_requests

experiments/scripts/failure-demo.sh                             # take an upstream away: retries, circuit breaker, recovery
experiments/scripts/dev-down.sh

./gradlew run --args="serve --config config/routeplane.yaml"    # or run the proxy yourself against your own upstreams
```

The smallest useful configuration:

```yaml
routes:
  - id: web
    match: {pathPrefix: /}
    upstreams:
      - {id: app, url: "http://127.0.0.1:9001"}
```

More: [`config/routeplane.yaml`](config/routeplane.yaml) (annotated default), [`config/examples/`](config/examples) (weighted canary, sticky
hash, a fully resilient API route, read-only shadowing, remote admin over TLS, a custom policy). Every option is in
[docs/configuration.md](docs/configuration.md).

### Run a small failure experiment

```bash
experiments/scripts/smoke.sh                                    # 15 s, real processes, writes experiments/work/smoke/report.md
./gradlew :benchmarks:run --args="run --scenario experiments/scenarios/fail-outage.yaml --out build/outage --runs 1"
open build/outage/report.html                                   # success rate and p99 per second with the fault marked
```

## Documentation

| | |
|---|---|
| [docs/architecture.md](docs/architecture.md) | components, request lifecycle, threading, metrics catalogue |
| [docs/configuration.md](docs/configuration.md) | complete schema, defaults, validation rules, environment substitution, reload |
| [docs/routing.md](docs/routing.md) | matching, policies, writing a custom policy |
| [docs/resilience.md](docs/resilience.md) | failure taxonomy, deadlines, retries and budgets, circuit breaker, health, limits, fallback |
| [docs/shadowing.md](docs/shadowing.md) | semantics, safety guarantees, sampling, comparison |
| [docs/security.md](docs/security.md) | threat model, controls and the tests that prove them, residual risks |
| [docs/benchmarking.md](docs/benchmarking.md) | methodology, how to run, how to read the reports, the committed results |
| [docs/development.md](docs/development.md) | build, tests, dependency locking, verification status, debugging |
| [docs/adr/](docs/adr) | decisions: HTTP stack (with the spike evidence), experiment design, deadlines and unread bodies |

## Repository layout

```
app/                proxy, CLI and admin listener + unit, integration and stress tests
fault-upstream/     test-only fault-injection upstream
benchmarks/         open-loop load generator, experiment runner, report generator
config/             default configuration and examples
experiments/        scenarios/, scripts/, results/ (committed real measurements)
docs/               documentation and ADRs
```

## Verification status

* All Java code compiles and **all tests pass** (app, fault-upstream and benchmarks suites, 250+ tests) - run on Temurin 21.0.8, and on 25.0.2
  for compatibility (where the known virtual-thread bug JDK-8369227 can cause rare intermittent failures).
* **Gradle itself could not be executed** in the environment where this was written (its distribution host and Maven Central were
  unreachable), so the Gradle build files, wrapper download and dependency resolution are unverified, and dependency lockfiles are not
  yet generated. How the code *was* built and tested, and the first thing to do on your machine, are in
  [docs/development.md](docs/development.md#verification-status-of-the-authoring-environment).
* The measurements in `experiments/results/` are real, from a 2-vCPU shared machine, and must be read with the caveats stated in their report.

## Non-goals and limitations

HTTP/2, WebSocket, HTTP/3, TLS termination and TLS to upstreams are out of scope for this version. There is no per-client rate limiting,
no authentication on the proxy listener, and the admin API uses a single shared token. The JDK HTTP server has real limits (no early
disconnect detection while waiting for an upstream, JVM-wide server settings) that are documented with evidence in
[ADR-0001](docs/adr/0001-http-stack.md) along with the conditions under which a Netty-based data plane should replace it.

## License

MIT - see [LICENSE](LICENSE).
