# Traffic shadowing

Shadowing copies a **sample** of eligible requests to a separate target and discards the answer, so a new version can be
observed under real traffic. It is **opt-in per route** and cannot be enabled by accident: a route without a `shadow:` section
never mirrors anything, and `samplePercent` has no default.

```yaml
routes:
  - id: api
    match: {pathPrefix: /api}
    upstreams: [{id: v1, url: "http://10.0.0.5:8080"}]
    shadow:
      targets:
        - {id: v2, url: "http://10.0.0.9:8080", samplePercent: 10, timeout: 2s, maxConcurrent: 8, queueCapacity: 128}
      headers: {set: {X-Shadow-Env: staging}}
      compare: {enabled: true, body: true, headers: [content-type]}
```

## Guarantees

1. **The primary response never changes.** The primary path only *offers* work to the shadow dispatcher; offering is
   non-blocking and catches everything. Shadow I/O runs on dedicated worker threads with their own HTTP client and connection
   pool. Tests mirror to targets that refuse connections, reset, truncate, answer 500, or take 2 s, and assert the primary
   status and body are identical and its latency unaffected.
2. **Resources are bounded.** Per target: `maxConcurrent` workers, a queue of `queueCapacity`, a per-request `timeout`
   (including the body), a response cap (`maxResponseBytes`), and `queueMaxWait` after which a stale queued task is discarded.
   **Overflow behaviour: the new task is dropped and counted** (`routeplane_shadow_dropped_total{reason="queue_full"}`); the
   primary is never delayed and nothing grows.
3. **Safe by default.** Only `GET`, `HEAD`, `OPTIONS` are mirrored. Anything else must be listed in `shadow.methods` *and*
   acknowledged with `allowUnsafeMethods: true` - do that only when every target is a sandbox, because mirrored writes repeat
   their side effects. A body is mirrored only if it was buffered completely and is at most `maxBodyBytes`; streamed and oversized
   bodies are skipped (`routeplane_shadow_skipped_total{reason="body_too_large|body_not_replayable|method"}`).
4. **Headers are sanitised.** Hop-by-hop headers and `Host` are dropped; every header whose name contains `auth`, `token`,
   `secret`, `password`, `api-key`, `session`, `cookie` or `credential` is removed, plus anything in `headers.strip`. With
   `headers.allow` the policy flips to an allow-list (explicit entries win). `headers.set` adds fixed headers, and every shadow
   request carries `X-Routeplane-Shadow: 1` and the original correlation id.

## Sampling

* `sampling: random` (default): each request is sampled independently with probability `samplePercent`.
* `sampling: deterministic`: `hash(key) -> [0,100)` is compared with `samplePercent`, so the same key is always or never
  sampled (useful to follow whole sessions). `sampleKey`: `request-id` (default), `header:<n>`, `cookie:<n>`, `query:<n>`,
  `path`, `client-ip`. Requests that lack the key are skipped (`no_sample_key`), not guessed.

## Comparison (optional)

With `compare.enabled` the dispatcher keeps a bounded copy (64 KiB) of the primary response while it streams to the client and
compares status, the listed headers and the body with the shadow response: `routeplane_shadow_comparisons_total{result}` is
`match`, `status_mismatch`, `header_mismatch`, `body_mismatch`, `body_not_compared` (equal but too large to compare),
`primary_unavailable` or `timeout`. Mismatches are logged with ids, statuses and sizes - never with payload bytes. A shadow
worker waits for the primary to finish (bounded by the shadow timeout); that wait occupies a shadow worker, never the primary.

## Operating it

Watch `routeplane_shadow_queue_depth` / `_in_flight` (bounded by capacity and workers), `routeplane_shadow_dropped_total`
(sizing signal), `routeplane_shadow_failures_total{kind}` (shadow health) and the comparison counters (behavioural diff).
Shadow targets have no circuit breaker of their own: a dead target costs at most its workers' timeouts and then drops.
