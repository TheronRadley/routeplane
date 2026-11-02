# Configuration reference

Routeplane reads one YAML file (default `config/routeplane.yaml`). The file is **validated completely before it is
activated**: unknown keys, wrong types, out-of-range values and inconsistent combinations are all reported together with
file, line and column, and nothing starts (or, on reload, nothing changes) until the file is valid.

```
$ ./gradlew run --args="validate --config config/routeplane.yaml"
OK: config/routeplane.yaml is valid (1 route, 2 upstreams)

$ ./gradlew run --args="validate --config broken.yaml"
broken.yaml is invalid: 2 configuration problems found:
  - broken.yaml:6:7: routes[0].match: unknown key 'pathPrefx' (did you mean 'pathPrefix'?); allowed keys here: [path, pathPrefix, methods, headers, query]
  - broken.yaml:10:20: routes[0].upstreams[0].url: https upstreams are not supported in this release ...
```

## Syntax rules

* Exactly one YAML document, at most 1 MiB, UTF-8. Duplicate keys, YAML merge keys (`<<`), custom tags and non-string
  mapping keys are rejected. Aliases are limited (20) and nesting is limited (40).
* **Booleans are `true`/`false` only** (not `yes`/`no`/`on`/`off`). **Integers are plain decimal** (no `0x`, no `_`).
* **Durations** are an integer plus a unit: `500ms`, `10s`, `5m`, `1h`, `1d`. `10` and `1.5s` are errors.
* **Environment substitution** applies to scalar *values* after parsing (never to keys, never to raw YAML text, so an
  environment variable cannot inject structure):
  * `${NAME}` - the variable; an error if it is unset **or empty**.
  * `${NAME:-default}` - the variable, or `default` if unset or empty (`${NAME:-}` is the empty string).
  * `$$` - a literal `$`. A lone `$` not followed by `{` or `$` is literal.
  * Names match `[A-Za-z_][A-Za-z0-9_]*`. Substituted values are not expanded again.
  * In YAML *flow* style (`{a: b}`) a value containing `${...}` must be quoted (`port: "${PORT}"`) because `}` is a flow
    indicator.
* Secrets (`admin.auth.bearerToken`, `admin.tls.keyStorePassword`) should be supplied through the environment. They are
  never printed: `toString()` masks them, `/admin/config` redacts any field whose name contains `token`, `password` or
  `secret`, and log statements only carry fields chosen by the code.

## Schema

Unless stated otherwise every key is optional and the value shown is the default.

### `version`

`version: 1` - schema version. Only `1` is accepted.

### `server` - the proxy listener

| key | default | meaning |
|---|---|---|
| `host` | `127.0.0.1` | bind address. Loopback by default; use `0.0.0.0` to accept remote clients. |
| `port` | `8080` | 0-65535; `0` picks an ephemeral port (tests). |
| `backlog` | `1024` | TCP accept queue. |
| `maxConnections` | `10000` | cap on concurrent client connections (`jdk.httpserver.maxConnections`); excess connections are closed immediately. |
| `maxConcurrentRequests` | `5000` | global in-flight cap; above it requests get `503 overload` + `Retry-After: 1`. |
| `maxHeaderBytes` | `16384` | request line + headers; larger gets `431`. |
| `maxRequestBodyBytes` | `10485760` | declared or streamed bodies above it get `413` (and the connection is closed). |
| `maxBufferedBodyBytes` | `262144` | bodies up to this size are read into memory **only when** retries or shadowing need to replay them; larger bodies are streamed and are therefore never retried or mirrored. |
| `requestHeaderTimeout` | `10s` | time allowed to receive the request line and headers (`sun.net.httpserver.maxReqTime`, whole seconds). |
| `idleTimeout` | `30s` | idle keep-alive connections are closed (`sun.net.httpserver.idleInterval`). |
| `maxResponseTime` | `10m` | hard backstop on a single response (`sun.net.httpserver.maxRspTime`); per-route `timeouts.request` is normally what ends a response. |
| `shutdownGracePeriod` | `30s` | how long a graceful shutdown waits for in-flight requests. |
| `requestIdHeader` | `X-Request-Id` | correlation header: an inbound value matching `[A-Za-z0-9._:-]{1,128}` is kept, anything else is replaced by a random 128-bit id; the id is sent upstream, returned to the client and logged. |
| `forwarding.mode` | `overwrite` | `overwrite`: drop client `Forwarded`/`X-Forwarded-*`/`X-Real-IP`, set them from the TCP peer. `trusted-append`: if the peer is inside `trustedProxies`, extend the incoming `X-Forwarded-For` chain (validated) and keep valid `X-Forwarded-Proto`/`Host`; otherwise behave like `overwrite`. `off`: strip and add nothing. |
| `forwarding.trustedProxies` | `[]` | CIDR blocks or IP literals (no host names). Required (non-empty) for `trusted-append`. |
| `forwarding.proto` | `http` | value of `X-Forwarded-Proto` (`http` or `https`; the listener itself is plain HTTP). |
| `forwarding.emitForwardedHeader` | `false` | also send RFC 7239 `Forwarded: for=...;proto=...;host=...`. |
| `hostHeader` | `upstream` | `upstream`: send the upstream's own authority as `Host` (the original goes in `X-Forwarded-Host`). `preserve`: send the validated client `Host` (needs `-Djdk.httpclient.allowRestrictedHeaders=host`, which the start script sets). |
| `allowEncodedSlashes` | `false` | allow `%2F`/`%5C` in paths (they stay encoded). Off by default: many back ends decode them into path separators. |
| `watchConfig` | `false` | poll the file and reload on change. |
| `watchInterval` | `2s` | polling period (500ms-1h). |

The JDK-level limits (`maxConnections`, `maxHeaderBytes`, `requestHeaderTimeout`, `idleTimeout`, `maxResponseTime`) are
JVM-wide properties fixed when the first server is created. An operator's explicit `-D` always wins. `maxHeaderBytes`
is enforced at the application level with a clear `431`; the JDK's own (hard) limit is set to twice that value because
at the JDK limit the connection is dropped without any response. **These settings need a restart** - see *Reload*.

### `admin` - the administrative listener

| key | default | meaning |
|---|---|---|
| `enabled` | `true` | |
| `host` | `127.0.0.1` | **A non-loopback bind is rejected unless both `auth.bearerToken` and `tls` are configured.** Loopback means `localhost` or a loopback IP literal. |
| `port` | `9090` | must differ from `server.port`. |
| `auth.bearerToken` | none | at least 16 characters, no whitespace. When set, `/admin/*` and `/metrics` require `Authorization: Bearer <token>` (constant-time comparison). `/health/*` stays open for probes. |
| `tls.keyStore`, `tls.keyStorePassword`, `tls.keyStoreType` | none, none, `PKCS12` | serve HTTPS (TLS 1.2/1.3). `keyStoreType` is `PKCS12` or `JKS`; the file must exist. |
| `allowedHosts` | `[]` | allowed `Host` header values (without port). Empty means `localhost`, `127.0.0.1`, `[::1]` and the bind address; the check is enforced for loopback binds and whenever the list is non-empty (DNS-rebinding defence). |

### `logging` and `metrics`

| key | default | meaning |
|---|---|---|
| `logging.level` | `info` | `debug`, `info`, `warn`, `error`. Logs are JSON lines on stdout. |
| `logging.accessLog.enabled` | `true` | one `request` event per request. |
| `logging.accessLog.sampleRate` | `1.0` | 0.0-1.0. |
| `logging.accessLog.includeQuery` | `false` | query strings often carry secrets. |
| `logging.accessLog.includeClientIp` | `true` | |
| `logging.queueCapacity` | `8192` | the log queue is bounded; overflow is dropped and counted, request threads never block on logging. |
| `metrics.latencyWindow` | `60s` | window of the sliding percentile gauges. |
| `metrics.maxSeriesPerFamily` | `2000` | cardinality backstop (labels come only from configuration and small closed sets). |

### `routes[]`

Routes are evaluated by `priority` (higher first), then by specificity, then by file order. See [routing.md](routing.md).

| key | default | meaning |
|---|---|---|
| `id` | required | `[A-Za-z0-9][A-Za-z0-9._-]{0,63}`, unique; used as a metric label. |
| `priority` | `0` | -1000..1000. |
| `match` | required (may be `{}`) | `path` (exact) **or** `pathPrefix`; `methods` (upper case, from GET HEAD POST PUT PATCH DELETE OPTIONS); `headers` and `query`: lists of `{name, equals | prefix | present: true}` (exactly one condition each). All conditions must hold. |
| `stripPrefix` | `false` | remove the matched `pathPrefix` before forwarding (needs `pathPrefix`). |
| `upstreams[]` | required, at least one | `id` (unique within the route), `url` (see below), `weight` (1-1000, default 1), `maxConcurrent` (1-1000000, default 1000). |
| `policy` | `weighted-round-robin` | a string, or `{type: <id>, options: {...}}`. Built in: `weighted-round-robin`, `weighted-random` (`seed`), `round-robin`, `least-active`, `hash` (`key`: `header:<n>`, `cookie:<n>`, `query:<n>`, `path`, `client-ip`, `request-id`). Custom policies are discovered through `ServiceLoader`. |
| `fallback` | none | `upstreams[]` (used when no primary is available) and/or `response: {status 200-599, body, contentType}` (a static answer of last resort). At least one of the two. |
| `timeouts.connect` | `1s` | 10ms-60s. |
| `timeouts.response` | `10s` | time to response headers per attempt, 10ms-10m. |
| `timeouts.request` | `30s` | overall deadline for the exchange including retries, backoff and body streaming, 10ms-1h. `connect` and `response` must not exceed it. |
| `retry` | disabled | see below. |
| `circuitBreaker` | none | see below. |
| `health` | none | see below. |
| `shadow` | none | see below. |

**Upstream `url`**: `http://host[:port][/base/path]`. HTTPS upstreams are rejected (TLS towards upstreams is out of scope for
this release); credentials, query strings and fragments are rejected; unspecified (`0.0.0.0`), multicast and link-local
IP literals (such as `169.254.169.254`) are rejected. A base path is prepended to the request path.

**`retry`**

| key | default |
|---|---|
| `maxAttempts` | `1` (1-10; **retries are off unless this is > 1**) |
| `methods` | `[GET, HEAD, OPTIONS]` - anything else must be listed explicitly |
| `retryStatuses` | `[502, 503, 504]` |
| `retryOnTimeout` | `true` |
| `backoff` | `initial: 25ms`, `max: 1s`, `multiplier: 2.0`, `jitter: full` (`full`, `equal`, `none`) |
| `budget` | `ratio: 0.2`, `minRetriesPerSecond: 5`, `window: 10s` |

**`circuitBreaker`** (per upstream)

| key | default |
|---|---|
| `failureRateThreshold` | `50` (percent, 1-100) |
| `minimumRequests` | `20` |
| `slidingWindow` | `{type: count, size: 100}` or `{type: time, duration: 10s}` (whole seconds); a count window must be at least `minimumRequests` |
| `openDuration` | `10s` |
| `halfOpenMaxProbes` | `3` |
| `recoverySuccessThreshold` | `3` |
| `failureStatuses` | `["500-599"]` (numbers or ranges) |
| `countTimeoutsAsFailures` | `true` |

**`health`**: `active: {path (required), interval 5s, timeout 1s (<= interval), unhealthyThreshold 3, healthyThreshold 2, expectStatus "200-399"}`
and/or `passive: {consecutiveFailures 5, ejectionTime 30s}`.

**`shadow`**: `targets[]` (`id`, `url`, **`samplePercent` required**, `sampling` `random|deterministic`, `sampleKey` default
`request-id`, `connectTimeout 1s`, `timeout 5s`, `maxConcurrent 16`, `queueCapacity 256`, `queueMaxWait` default = `timeout`),
`methods` (default `[GET, HEAD, OPTIONS]`), `allowUnsafeMethods` (must be `true` to list anything else), `maxBodyBytes`
(64 KiB, must not exceed `server.maxBufferedBodyBytes`), `maxResponseBytes` (1 MiB), `headers: {strip, allow, set}`,
`compare: {enabled, body, headers}`. A shadow target may not have the same URL as a primary upstream of its route. See
[shadowing.md](shadowing.md).

## Reload

`POST /admin/reload` (or the file watcher) re-reads the same file, validates it, builds the complete runtime and only then
swaps one reference: a reload either applies entirely or not at all, and requests that are already running finish on the
configuration they started with. Results: `200` applied or unchanged, `422` rejected (the error list is returned, the
old configuration keeps serving), `409` `restart_required` (the file changes `server`, `admin`, `logging` or `metrics`,
which are fixed at start-up), `500` build failure.

Upstream runtimes whose settings are unchanged (URL, `maxConcurrent`, connect timeout, circuit breaker and health
settings) are **carried over**: an open breaker stays open and pooled connections stay pooled. Changing any of those
settings gives that upstream a fresh runtime. Weights, policies, matching, retry settings and routes can change freely.
Resources dropped by a reload are closed after the longest request timeout plus 5 s.

The version counter, file SHA-256, load time and last reload result are visible in `GET /admin/config` and as
`routeplane_config_version`.
