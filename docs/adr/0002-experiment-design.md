# ADR-0002: Experiment design - open-loop load, separate processes, honest limits

* Status: accepted (2026-11-02)

## Context

The research question is how routing policies, circuit breakers, retries and shadowing affect latency, throughput and reliability under
increasing load and controlled failures. The numbers must be real, reproducible and honest about what they can and cannot show. The
development machine has 2 vCPUs and 4 GB of RAM.

## Decisions

1. **Open-loop load generation.** Request *i* has an intended send time `start + i/rate`, independent of any response; latency is measured
   from the intended time. A closed-loop tool slows down when the system does and hides stalls (coordinated omission). The generator is
   asynchronous (JDK `HttpClient.sendAsync`, virtual-thread executor), counts requests it had to drop at `maxInFlight` instead of
   skipping silently, never spins (parks until the next intended time), and reports its own lag (p99/max) and CPU. Setup work (control calls,
   sampler start) is kept off the pacing thread - an early version stalled it for ~100 ms at the start of the window and the open-loop
   accounting duly reported that as latency, which is how the bug was found.
   *Alternative considered:* wrk2/vegeta/k6 - not available offline in the sandbox, and an in-repo generator keeps the experiment
   reproducible with `./gradlew` alone. *Cost:* the JDK client costs roughly 0.8 ms CPU per request; on a 2-core machine the generator is
   the bottleneck above a few thousand requests per second, which the report flags.
2. **Separate processes.** The proxy and every fault upstream run as their own JVMs launched by the runner, so CPU (`/proc/<pid>/stat`),
   RSS, heap, GC and connection counts are attributable per process. The proxy gets a fixed heap (`-Xms256m -Xmx512m`).
3. **Fixed windows with warm-up.** Each run: warm-up at the same rate (15 s steady, 8 s failure scenarios) whose data are discarded, then
   a fixed measurement window (10 s steady, 40 s failure). Cold-start JIT effects are real and large on a small machine (a 2 s warm-up
   produced a 1.3 s median at 500 rps), hence the long warm-up.
4. **Repetitions.** 3 runs per point up to 2,500 rps (steady), 1 run at 5,000 and 10,000 rps (those are expected to saturate this machine;
   one run shows where it falls over), 2 runs per failure variant. Reports show the median with the min-max range and flag high variance.
5. **Percentile rule.** p99 is reported only with >= 1,000 samples and p99.9 only with >= 10,000 (ten tail observations); otherwise `n/a`.
   Sliding-window gauges inside the proxy follow the same rule.
6. **Saturation is flagged, not hidden:** achieved < 95% of offered, > 1% timeouts, generator drops, generator lag p99 > 20 ms, or the
   machine > 92% busy.
7. **Failure scenarios are driven through the upstream's control API at fixed times**, recorded with actual timestamps, and compared as
   *variants* of the same topology (e.g. no resilience vs retries + breaker). Faults are seeded and deterministic in their decisions.
8. **Error attribution** uses the proxy-only `X-Routeplane-Error` marker (stripped from upstream responses), splitting upstream-originated,
   proxy-generated-upstream-caused, proxy-generated-protective and transport failures.

## Consequences

Results describe this machine under these conditions; they are comparable *between experiments in the same campaign*, not across
hardware. Anything above ~2,500 rps on 2 vCPUs measures the load generator and the machine as much as the proxy.
