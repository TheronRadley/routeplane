# Architecture

```
                      +---------------------------- Routeplane process ----------------------------+
 client --HTTP/1.1--> |  ProxyServer (JDK HttpServer, one virtual thread per exchange)               |
                      |    RequestValidator -> PathNormalizer -> RouteTable.match                    |
                      |    ProxyHandler:  body (buffer | stream) -> select upstream -> attempt loop  |
                      |       select = health + breaker + limiter + RoutingPolicy (+ fallback)       |
                      |       attempt = UpstreamClient (JDK HttpClient per upstream) --------------> upstreams
                      |       retry (budget, backoff, deadline)  ->  relay response (or abort)       |
                      |    ShadowDispatcher.offer(...)  --queue--> workers --own HttpClient--------> shadow targets
                      |                                                                              |
 operator --HTTP----> |  AdminServer (separate listener): /health/* /metrics /admin/* POST /admin/reload
                      |  ConfigManager: validate -> build RuntimeState -> atomic swap -> retire old   |
                      +------------------------------------------------------------------------------+
```

Decision record for the HTTP stack (and the evidence behind it): [adr/0001-http-stack.md](adr/0001-http-stack.md).

## Packages

| package | responsibility |
|---|---|
| `dev.routeplane.config` | `ConfigLoader`/`ConfigNode` (strict, positioned YAML validation), `RouteplaneConfig` (immutable records), `EnvSubstitutor` |
| `dev.routeplane.routing` | matching (`RouteMatcher`, `RouteTable`, `PathNormalizer`, `QueryString`) and the **policy SPI** (`RoutingPolicy`, `Candidate`, `RoutingRequest`) with the built-in policies - no networking types |
| `dev.routeplane.resilience` | `CircuitBreaker`, `RetryBudget`, `Backoff`, `ConcurrencyLimiter`, `UpstreamHealth` - pure logic with an injectable `TimeSource` |
| `dev.routeplane.proxy` | the data plane: `ProxyServer`, `ProxyHandler`, `RequestValidator`, `HeaderPolicy`, `UpstreamClient`, `ShadowDispatcher`, runtime model (`RuntimeState`, `RouteRuntime`, `UpstreamRuntime`), `ConfigManager`, `AdminViews` |
| `dev.routeplane.admin` | `AdminServer` |
| `dev.routeplane.observability` | `MetricsRegistry` (Prometheus text), `LatencyWindow` (HdrHistogram), `StructuredLogger`, JVM/TCP metrics, `Json` |
| `dev.routeplane.cli` / `Main` | `serve`, `validate`, `version` |

Gradle modules: `app` (proxy + CLI), `fault-upstream` (test-only fault-injection upstream, also used by `app`'s integration tests),
`benchmarks` (open-loop load generator, experiment runner, report generator). Modules exist where there is a real dependency
boundary, not one per component.

## Request lifecycle

1. **Accept** (JDK server): `maxConnections`, header read limited by `requestHeaderTimeout`. A virtual thread handles the exchange.
2. **Admission**: draining? -> `503`. In-flight above `maxConcurrentRequests`? -> `503 overload`.
3. **Validation** (`RequestValidator`): version, method, origin-form target, path canonicalisation, query well-formedness,
   header names/values/size/count, `Host`, `Content-Length`/`Transfer-Encoding`, body size, `Via` loop count; assigns the correlation id.
4. **Routing**: `RouteTable.match` on the canonical path, method, headers, query. No match -> `404`.
5. **Deadline**: a watch is armed for `timeouts.request` (interrupts the thread when it fires).
6. **Body**: none; or buffered (only if retries/shadowing need a replayable body and it fits `maxBufferedBodyBytes`); or streamed
   through a guard that enforces `maxRequestBodyBytes`, counts bytes and remembers whether a failure was the client's.
7. **Shadow offer** (non-blocking).
8. **Attempt loop**: select an upstream (eligible = healthy, breaker `wouldPermit`, below its limit; the policy chooses; the limiter and the breaker
   permits are then taken, retrying the choice if either refuses); send with `min(response timeout, remaining)`; classify the outcome and
   report it to the breaker/health exactly once; retry or fall out of the loop.
9. **Relay**: copy status and filtered headers; stream the body with backpressure (the virtual thread blocks on the client's socket, which
   blocks the upstream read); flush per chunk for unknown-length bodies; on failure after headers abort the connection.
10. **Completion**: metrics, access log, bounded lingering close if the client's body was not fully read.

## Threading and backpressure

One virtual thread per exchange; blocking style on purpose (retry loops, deadlines and cleanup read top to bottom). Backpressure is
plain TCP flow control through blocking reads/writes: a slow client slows the upstream read; a slow upstream does not buffer in memory.
Deadlines are enforced by interrupting the virtual thread (`Deadlines.Watch`): the interrupt is issued under a lock that `close()`
takes, so none can arrive after the exchange finished.

## Observability

All metrics are `routeplane_*` (plus `jvm_*`/`process_*`), rendered by `GET /metrics` on the admin listener. Highlights:

| metric | labels | notes |
|---|---|---|
| `requests_total`, `request_duration_seconds` (histogram) | route, status_class | downstream view; `route="-"` for unmatched |
| `errors_total`, `timeouts_total` | route, kind | proxy-generated or aborted outcomes (see resilience.md) |
| `upstream_requests_total` | route, upstream, attempt | `first`/`retry`: amplification |
| `upstream_responses_total`, `upstream_duration_seconds` | route, upstream, ... | time to response headers |
| `upstream_in_flight`, `upstream_concurrency_limit`, `upstream_pool_utilization_ratio` | route, upstream | |
| `circuit_breaker_state`, `_transitions_total`, `_rejections_total` | route, upstream, ... | |
| `retries_total`, `retry_skipped_total`, `retry_budget_exhausted_total` | route, ... | |
| `shadow_*` (sampled, requests, failures, dropped, skipped, comparisons, queue depth, in flight, duration) | route, target, ... | |
| `request_duration_quantile_seconds`, `upstream_duration_quantile_seconds` | route, quantile | p50/p90/p95/p99/p99.9 over a sliding window, **omitted until the window holds `10/(1-q)` samples** |
| `tcp_connections` | role, route, upstream | kernel view from `/proc/net/tcp` (Linux), attributed by port |
| `upstream_healthy`, `upstream_ejected`, `health_checks_total`, `upstream_ejections_total` | | |
| `config_version`, `config_reloads_total` | | |
| `active_requests`, `draining`, `jvm_*`, `process_*` | | heap, GC, threads, CPU, RSS, open fds |

Label values come only from configuration (route and upstream ids, validated to a bounded alphabet) and small closed sets; a per-family
series cap is the backstop. Logs are JSON lines (`ts`, `level`, `event`, ...); an access line has `request_id`, `route`, `method`, `path`
(no query by default), `status`, `duration_ms`, `upstream`, `attempts`, `upstream_ttfb_ms`, byte counts, `failure`, `failure_detail`.

## Known gaps of the chosen stack

Documented with evidence in ADR-0001: no early-disconnect detection while waiting for an upstream (deadlines bound the waste); connection
counts only from the kernel; header-size ceiling drops the connection; JVM-wide server properties need a restart. Two JVM-level facts matter
operationally:

* **Nagle's algorithm.** The JDK server leaves `TCP_NODELAY` off, which adds about 40 ms to a response written in two segments
  (measured: 1000 sequential requests took ~40 s instead of ~1.7 s). `JdkTuning` sets `sun.net.httpserver.nodelay=true` before the first
  server exists, and the start script sets it as well.
* **JDK-8369227** (virtual threads stuck after timed parks) on JDK 25.0.0-25.0.2 / 26.0.0: see [security.md](security.md) and `JdkIssues`.
