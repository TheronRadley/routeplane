# Resilience

Everything here is bounded: every queue, retry, wait and in-flight count has a configured limit, and every limit has a
defined overload behaviour. Defaults are conservative: **retries are off, circuit breakers are off, health checks are off**
until a route enables them.

## Failure taxonomy

Each request that does not get a normal upstream answer ends in exactly one `FailureKind`. It maps to a status, a stable
code (`X-Routeplane-Error` header on proxy-generated responses, `kind` label in `routeplane_errors_total`, `failure` in the
access log) and an attribution:

| code | status | meaning | caused by |
|---|---|---|---|
| `connect_failed` | 502 | connection refused, unreachable, or connect timeout | upstream |
| `upstream_reset` | 502 | closed/reset before headers, or invalid response | upstream |
| `upstream_timeout` | 504 | no response headers within `timeouts.response` | upstream |
| `deadline_exceeded` | 504 | `timeouts.request` ran out (before headers: 504; after headers: the connection is aborted) | upstream/client |
| `breaker_open` | 503 + `Retry-After: 1` | every candidate's circuit breaker refused the call | protective |
| `no_upstream` | 503 + `Retry-After` | no healthy upstream (and no fallback) | protective |
| `concurrency_limit` | 503 + `Retry-After` | every candidate is at `maxConcurrent` | protective |
| `overload` | 503 + `Retry-After` | `server.maxConcurrentRequests` exceeded | protective |
| `draining` | 503, `Connection: close` | the proxy is shutting down | protective |
| `no_route` | 404 | nothing matched | client |
| `invalid_request`, `body_too_large` | 400/405/413/414/431/501/505/508 | request validation | client |
| `client_abort` | none (logged as 499) | the client left | client |

An error status produced by an **upstream** is passed through untouched and is *not* marked: `X-Routeplane-Error` can
only come from the proxy (it is stripped from requests and from upstream responses). That is how reports separate
upstream-originated, proxy-generated-upstream-caused and protective failures.

After response headers have been sent nothing can be retried and nothing can be re-written: an upstream failure (or a
deadline) mid-body **aborts the connection without the terminating chunk**, so a truncated response can never look
complete. This is covered by tests that read raw bytes.

## Deadlines

* `timeouts.connect` - per attempt, enforced by the HTTP client.
* `timeouts.response` - per attempt, time to response headers.
* `timeouts.request` - the **overall** deadline from the arrival of the request. It covers receiving the request body,
  every attempt, every backoff pause, and streaming the response. It is enforced by interrupting the handling virtual
  thread, which unblocks socket reads and writes and cancels the upstream call. Retries are only attempted if the
  remaining time exceeds the backoff delay.

## Retries

A retry happens only if **all** of the following hold:

1. `retry.maxAttempts` > 1 and attempts remain;
2. the method is in `retry.methods` (default GET, HEAD, OPTIONS; anything else needs an explicit decision);
3. the body is replayable: none, or fully buffered (`maxBufferedBodyBytes`). Streamed bodies are **never** retried;
4. the failure is retryable: connect failure (always safe - nothing was sent), reset before headers, timeout
   (`retryOnTimeout`), or a status in `retryStatuses`;
5. the remaining deadline exceeds the backoff delay;
6. the **retry budget** allows it.

*Backoff*: `min(max, initial * multiplier^(n-1))`, then jittered (`full` = uniform in `[0, d]`, `equal` = `d/2 + uniform[0, d/2]`,
`none`). *Budget*: over a sliding window a retry is allowed while `retries < max(minRetriesPerSecond * windowSeconds,
ratio * firstAttempts)` - a ratio of 0.2 bounds retry amplification to roughly 1.2x under sustained failure.
A retry prefers an upstream that has not been tried yet.

Observability: `routeplane_retries_total{reason}`, `routeplane_retry_skipped_total{reason}` (attempts_exhausted,
method_not_retryable, body_not_replayable, deadline, budget_exhausted), `routeplane_retry_budget_exhausted_total`, and
`routeplane_upstream_requests_total{attempt="first|retry"}`. **Retry amplification = sum of upstream attempts / sum of
client requests**; benchmark reports compute it per run.

One caveat of the JDK client: it may itself retry an idempotent request **once** when a pooled connection turns out to be
stale. That attempt is invisible to the proxy's counters (see ADR-0001).

## Circuit breaker (per upstream)

```
            failure rate >= threshold               openDuration elapsed
   CLOSED ------------------------------> OPEN ----------------------------> HALF_OPEN
      ^        (and >= minimumRequests)     ^                                   |  |
      |                                     |        any probe fails            |  |
      |                                     +-----------------------------------+  |
      +------------------------------------------------------------------------+
                 recoverySuccessThreshold probes succeed
```

* **CLOSED**: calls are admitted and their outcomes recorded in a sliding window (the last N calls, or the last T seconds).
* **OPEN**: calls are rejected without touching the upstream. Other upstreams of the route keep serving.
* **HALF_OPEN**: at most `halfOpenMaxProbes` concurrent probe calls are admitted; `recoverySuccessThreshold` successes close
  the breaker, one failure re-opens it with a fresh open period.
* Outcomes: transport failures and `failureStatuses` (default 5xx) are failures; timeouts count unless
  `countTimeoutsAsFailures: false`; **client aborts are ignored** (the upstream is not blamed).
* Transitions are atomic (one lock, no blocking inside); an outcome that arrives after the breaker has already moved on is
  discarded (epoch check) instead of corrupting the new state; the clock is injectable (tests use a manual clock).
* Metrics: `routeplane_circuit_breaker_state` (0 closed, 1 open, 2 half-open), `..._transitions_total{from,to}`,
  `..._rejections_total`. `GET /admin/circuits` lists each breaker with its window and recent transitions and reasons
  (`failure_rate_exceeded`, `open_duration_elapsed`, `probe_failed`, `recovered`).

Breakers are scoped to a route's upstream: the same host used in two routes has two independent breakers.

## Health

* **Passive** (`health.passive`): `consecutiveFailures` failed real requests eject an upstream for `ejectionTime`; afterwards it
  is on probation - one more failure ejects it again.
* **Active** (`health.active`): a probe every `interval`; `unhealthyThreshold` consecutive failures mark it down,
  `healthyThreshold` consecutive successes bring it back. New upstreams start up (optimistic).

Upstreams that are down or ejected are excluded from selection (`routeplane_upstream_healthy`, `routeplane_upstream_ejected`).

## Concurrency limits and overload

* Per upstream: `maxConcurrent` in-flight requests, tracked from selection until the response has been relayed. Excess is
  **rejected immediately** (never queued) with `503 concurrency_limit`; if other upstreams have room the request goes there.
  `routeplane_upstream_pool_utilization_ratio` = in-flight / limit.
* Global: `server.maxConcurrentRequests` -> `503 overload`. Connections: `server.maxConnections`.
* The JDK client's own connection pool is unbounded; the per-upstream limit is what bounds it.

## Fallback

When selection finds nothing eligible: (1) `fallback.upstreams` are tried, (2) otherwise `fallback.response` is returned
(marked `X-Routeplane-Fallback: static`), (3) otherwise the request fails with `breaker_open`, `concurrency_limit` or
`no_upstream`. `routeplane_fallback_total{kind="upstream|static"}` counts both.
