# Development guide

## Prerequisites

* **JDK 21 or newer** (21 is the minimum and the reference). Avoid 25.0.0-25.0.2 and 26.0.0 (JDK-8369227, see
  [adr/0001-http-stack.md](adr/0001-http-stack.md)); `./gradlew build` prints a warning on those builds.
* Nothing else: the Gradle wrapper (9.8.2, SHA-256 pinned in `gradle/wrapper/gradle-wrapper.properties`) downloads Gradle.
* Linux is the primary platform (resource sampling and connection counts read `/proc`; they are skipped elsewhere).

## Everyday commands

```bash
./gradlew build                                               # compile + all tests of all modules
./gradlew test                                                # tests only
./gradlew :app:test --tests '*ProxyForwardingTest'            # one class
./gradlew run --args="serve --config config/routeplane.yaml"  # start the proxy (working directory = repository root)
./gradlew run --args="validate --config config/routeplane.yaml"
./gradlew run --args="version"
./gradlew :app:installDist :fault-upstream:installDist        # start scripts under */build/install/*/bin
experiments/scripts/dev-up.sh                                 # local environment: 3 fault upstreams + proxy
experiments/scripts/failure-demo.sh                           # a small failure experiment you can watch
experiments/scripts/smoke.sh                                  # 15-second real experiment
```

## Layout

```
app/                  proxy, CLI, admin (src/main), unit + integration + stress tests (src/test)
fault-upstream/       test-only fault-injection upstream (also a library for app's tests)
benchmarks/           open-loop load generator, experiment runner, report generator
config/               routeplane.yaml (default) and examples/
experiments/          scenarios/ (YAML), scripts/, results/ (committed, real measurements)
docs/                 architecture, configuration, routing, resilience, shadowing, security, benchmarking, adr/
gradle/               version catalog (libs.versions.toml) and wrapper
```

## Tests

JUnit 5 only (no mocking framework: fakes and an injectable `TimeSource`). The suites:

* unit: config, routing, policies, resilience primitives (including a 16-thread breaker stress), metrics, logging;
* integration (real sockets, real proxy, real fault upstream): forwarding and header policy, malformed/hostile requests, timeouts,
  truncation, disconnects, drain, routing and resilience under live traffic, shadowing, admin protection and reload;
* contract: `JdkHttpStackContractTest` pins the JDK behaviours the design depends on;
* stress: `ProxyStressTest` (mixed load, misbehaving upstreams, invariants after quiescing);
* benchmarks: scenario parsing, open-loop behaviour against a stalling server, report generation, and a real-process runner smoke test.

Rules of thumb: never `sleep` to wait for a condition (use `ProxyHarness.eventually`), never assert on exact timing beyond generous
bounds, bind everything to port 0, and keep tests independent (each starts its own proxy and upstreams).

## Dependencies and locking

Versions are exact in `gradle/libs.versions.toml`: SnakeYAML 2.7, Gson 2.13.2, HdrHistogram 2.2.2, JUnit 5.14.4.
Dependency locking is enabled for every configuration. **Lockfiles are not committed yet** because generating them needs network
access to Maven Central, which the authoring environment did not have. To create them once:

```bash
./gradlew resolveAndLockAll --write-locks
git add '**/gradle.lockfile' && git commit -m "Add dependency lockfiles"
```

After that, a resolution that differs from the lockfile fails the build. Optionally also generate dependency verification metadata
(`./gradlew --write-verification-metadata sha256 build`) to pin checksums.

## Verification status of the authoring environment

Be aware of what was and was not executed when this repository was written, so you know where to look first if something differs:

* **Not executed: Gradle itself** (neither `services.gradle.org` nor Maven Central were reachable). The Kotlin DSL files are written
  conservatively and were reviewed against Gradle's documented API, but `./gradlew build` has not been run. Most likely places for a
  first-run surprise: the `JavaCompile` lint flags, the `tasks.named<JavaExec>("run")` working-directory setup, the version-catalog
  accessors, and `testRuntimeOnly(project(":app"))` in `benchmarks`.
* **Executed:** all Java sources were compiled with ECJ 3.45 (Eclipse compiler, `-21`, against the Temurin 21.0.8 class library) and all
  tests were run with JUnit Platform 1.14.4 / Jupiter 5.14.4 (built from the upstream source tags) on Temurin 21.0.8 and, for
  compatibility, 25.0.2. ECJ is not javac: if javac reports a diagnostic that ECJ accepted, fix it there.
* The experiment results in `experiments/results/` are real measurements taken in that environment (2 vCPU, shared), described in
  [benchmarking.md](benchmarking.md).

## Adding things

* **A routing policy:** implement `RoutingPolicyFactory` (see [routing.md](routing.md)), register it in `META-INF/services`, add a
  deterministic unit test and a live test.
* **A configuration key:** read it in `ConfigLoader` through a `ConfigNode` accessor (so it is range-checked, positioned and
  unknown-key-checked), add it to `RouteplaneConfig`, document it in `configuration.md`, and decide whether a reload may change it
  (`ConfigManager.frozenSectionsChanged`).
* **A metric:** register it in `ProxyMetrics`, pre-bind children in `RouteRuntime`/`UpstreamRuntime` (no label lookups on the hot path),
  keep label values bounded, and add it to the catalogue in `architecture.md`.

## Debugging

* `-Dfaultupstream.debug=true` prints why the fault upstream ended a connection.
* `-Djdk.httpclient.HttpClient.log=requests,headers,errors` logs the proxy's upstream client (this is how JDK-8369227 was diagnosed).
* `jcmd <pid> Thread.dump_to_file -format=text dump.txt` shows virtual threads.
* The access log's `failure` and `failure_detail` fields name the classified failure and the JDK's own description.
