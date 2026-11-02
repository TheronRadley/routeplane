# Security review and threat model

Status: reviewed against the implementation in this repository; every control listed below with a *Test* reference is
exercised by an automated test. This is a design review, not an external penetration test, and Routeplane has not been
run in production.

## Scope and assumptions

Routeplane is an HTTP/1.1 reverse proxy. It does **not** terminate TLS and does **not** speak TLS to upstreams in this release;
run it on a trusted network segment behind a TLS-terminating load balancer (or add TLS support first - see *Residual risks*).
HTTP/2, WebSocket and HTTP/3 are out of scope; `Upgrade` requests are not forwarded.

| trust boundary | trust level |
|---|---|
| clients -> proxy listener | **untrusted**: every byte is hostile until validated |
| proxy -> upstreams | operator-chosen, but may fail or misbehave (slow, truncated, forged headers) |
| operator -> admin listener | trusted but authenticated; loopback by default |
| configuration file and environment | trusted, operator-controlled |
| shadow targets | operator-chosen, possibly less hardened than production |

Assets: availability of upstreams, integrity of routing decisions, confidentiality of credentials that pass through
(`Authorization`, cookies, API keys), control of the admin plane, and what logs and metrics reveal.

## Threats and controls

| # | threat | control | test |
|---|---|---|---|
| 1 | **Request smuggling / desync** via `Content-Length` + `Transfer-Encoding`, duplicate or malformed `Content-Length`, `+3`, obfuscated TE | JDK parser rejects the classic cases; `RequestValidator` rejects the rest (`+3`, negative, non-numeric, multiple values, any TE other than a single `chunked`), responds with `Connection: close`, and the upstream request is always re-framed by the HTTP client (hop-by-hop headers are stripped, so a client `Transfer-Encoding` can never be forwarded). | `ProxyHardeningTest`, `JdkHttpStackContractTest` |
| 2 | **SSRF / open proxy**: absolute-form request targets (`GET http://169.254.169.254/ ...`), `CONNECT`, attacker-controlled `Host`/`X-Forwarded-*`/custom headers choosing a destination | only origin-form targets are accepted (`400` otherwise); routing sees path + query only; **upstream URLs exist only in the configuration**; no header, query or body field can select a destination; config rejects `https`, credentials, unspecified/multicast/link-local IP literals (e.g. cloud metadata) as upstream URLs; shadow targets must differ from primaries. | `ProxyHardeningTest`, `RoutingLiveTest.clientsCannotChooseUpstreams`, `ConfigLoaderTest.upstreamUrlsAreRestricted` |
| 3 | **Path traversal / routing bypass**: `/public/../admin`, `%2e%2e`, `%2f`, `\`, NUL, double slashes | canonicalisation before matching, and the *same* canonical path is forwarded; encoded slashes/backslashes/NUL/control characters rejected; prefix matching is segment-aware (`/api` does not match `/apix`). | `PathNormalizerTest`, `ProxyHardeningTest.dotSegments...` |
| 4 | **Header injection / response splitting** (CR/LF/NUL in header values) | control characters rejected in requests (`400`); the JDK `Headers`/client APIs reject CR/LF on the way out; error bodies are generated JSON, never reflecting request data except the sanitised request id. | `ProxyHardeningTest` |
| 5 | **Host header attacks** (cache poisoning, password-reset poisoning) | exactly one syntactically valid `Host` is required for HTTP/1.1; by default the upstream receives *its own* authority and the original goes only in `X-Forwarded-Host`; `preserve` is opt-in. | `ProxyHardeningTest`, `ProxyForwardingTest` |
| 6 | **Forged forwarding headers** (`X-Forwarded-For`, `Forwarded`, `X-Real-IP`) | never trusted: dropped and recomputed from the TCP peer; `trusted-append` only for peers inside configured CIDRs, with the incoming chain validated; `off` available. | `ProxyForwardingTest` |
| 7 | **Forged proxy markers / hop-by-hop abuse**: `X-Routeplane-*`, `Connection: X-Secret`, `Keep-Alive`, `TE`, `Upgrade`, `Proxy-Authorization` | all hop-by-hop headers and every header named in `Connection` are removed in both directions; `X-Routeplane-*` is stripped from requests and from upstream responses, so only the proxy can produce `X-Routeplane-Error`. | `ProxyForwardingTest` |
| 8 | **Forwarding loops** | `Via` carries a per-process token; two occurrences -> `508`. | `ProxyHardeningTest.forwardingLoopsAreDetected` |
| 9 | **Slowloris / slow POST / slow reader** | request-line+header time limit (`requestHeaderTimeout`); a per-route overall deadline enforced by interrupting the handling thread (covers slow uploads and clients that never read); a *bounded lingering close* instead of the JDK's unbounded drain of unread request bodies; `maxRspTime` backstop; connection cap. | `ProxyLifecycleTest` |
| 10 | **Resource exhaustion**: oversized headers/bodies, too many headers, connection floods, request floods, retry storms, unbounded queues | `maxHeaderBytes` (`431`), header count, `maxRequestBodyBytes` (`413`, enforced while streaming), `maxConnections`, `maxConcurrentRequests`, per-upstream `maxConcurrent`, bounded retry budget, bounded shadow queues/workers, bounded log queue, bounded metric cardinality; replayable bodies buffered only up to `maxBufferedBodyBytes`. | `ProxyHardeningTest`, `ProxyLifecycleTest`, `ResilienceLiveTest`, `ShadowLiveTest`, `ProxyStressTest` |
| 11 | **Admin plane exposure**: remote access, unauthenticated control, DNS rebinding, CSRF | separate listener, loopback by default; a non-loopback bind is *refused at config time* without bearer token **and** TLS; constant-time token comparison; `Host` allow-list; state-changing requests with an `Origin` header are refused; `POST /admin/reload` takes no parameters and can only re-read the configured file; config view redacts secrets. | `AdminAndConfigTest`, `ConfigLoaderTest` |
| 12 | **Configuration injection** via environment or YAML (arbitrary object construction, structure injection) | YAML is *composed* into a node tree, never into Java objects (no deserialisation gadgets); only registered `ServiceLoader` policies can be named; env substitution applies to scalar values only; strict schema with unknown-key rejection; size/alias/nesting limits. | `ConfigLoaderTest`, `EnvSubstitutorTest` |
| 13 | **Secrets in logs, metrics, errors, shadow traffic** | access logs record fields chosen by the code (never headers or bodies); the logger redacts values of sensitive-looking keys and truncates long values; query strings are off by default; metrics labels come only from configuration; shadow requests drop credentials by name pattern; secrets are masked in `toString()` and `/admin/config`. | `StructuredLoggerTest`, `ShadowLiveTest`, `AdminAndConfigTest` |
| 14 | **Retries causing duplicate side effects or overload** | retries off by default; idempotent methods only unless listed; never for streamed bodies; always inside the request deadline; budgeted; amplification is measured. | `ResilienceLiveTest` |
| 15 | **Shadow traffic harming production or leaking data** | opt-in per route; safe methods only unless acknowledged; sanitised headers; bounded queues/workers; separate client/pool; cannot alter the primary response. | `ShadowLiveTest` |
| 16 | **Half-applied or poisoned configuration** | validate fully, build fully, swap atomically; invalid file -> old config keeps serving; changes to start-up-only settings are refused explicitly. | `AdminAndConfigTest` |
| 17 | **Truncated responses treated as complete** | mid-stream failures abort the connection without the final chunk; truncated upstream bodies are detected by the JDK client. | `ProxyLifecycleTest`, `JdkHttpStackContractTest` |
| 18 | **Supply chain** | exact dependency versions in `gradle/libs.versions.toml` (no ranges); three small runtime dependencies (SnakeYAML, Gson, HdrHistogram) and JUnit for tests; Gradle wrapper pinned with a SHA-256 and validated in CI; Dependabot configured; dependency locking enabled (lockfiles must be generated and committed, see `docs/development.md`). | CI |
| 19 | **Fault-injection tooling reaching production** | the fault upstream lives in its own module, binds loopback by default, its control API is on a separate port, and **nothing resembling fault injection exists on the proxy listener**. | - |

## Residual risks and limitations

These are real and deliberately not hidden:

1. **No TLS termination and no TLS to upstreams.** Credentials cross the proxy in clear text; deploy only on a trusted
   network or behind a TLS terminator. The admin listener *does* support TLS.
2. **JDK HTTP server leniency.** It accepts a few things a strict server would refuse (`Content-Length: +3`, absolute-form,
   duplicate `Host`, NUL in headers, `HTTP/2.0`). `RequestValidator` closes each gap and `JdkHttpStackContractTest` /
   `ProxyHardeningTest` pin the behaviour, but a future JDK could introduce new differences.
3. **Header limit at the JDK ceiling drops the connection without a response** (no `431`); the proxy sets the JDK ceiling
   at twice the configured limit so ordinary oversized requests get a proper `431`.
4. **Early error responses to clients that are still uploading**: after the bounded lingering close the connection is cut,
   so such clients may see a connection reset instead of the error.
5. **No per-client rate limiting or authentication on the proxy listener.** Concurrency caps protect the proxy and the
   upstreams globally, not individual clients.
6. **Upstream host names are resolved by the JDK on every new connection.** IP literals are checked at load time; names are
   trusted because the configuration is. If configuration is generated from untrusted input, validate it first.
7. **JDK-8369227** (virtual threads can stay parked for seconds on JDK 25.0.0-25.0.2 and 26.0.0) can stall requests
   intermittently; the proxy warns at start-up. Use JDK 21, 24, 25.0.3+ or 26.0.1+.
8. **Admin authentication is a single shared bearer token** (minimum 16 characters) with no role separation and no
   throttling of failed attempts; keep the admin listener on loopback or a management network.
9. **Dependency checksums are not pinned in a verification file** and lockfiles are not committed (they need network access
   to generate); see `docs/development.md`.
10. **Shadowing writes** (when enabled with `allowUnsafeMethods`) repeat side effects on the shadow target.

## Deployment checklist

* Keep `admin.host` on loopback, or configure token + TLS and a management-network firewall; supply secrets via the environment.
* Set `server.forwarding.mode: trusted-append` with `trustedProxies` only if a trusted load balancer sits in front; otherwise leave the default.
* Size `maxConnections`, `maxConcurrentRequests`, per-upstream `maxConcurrent` and `timeouts.request` for your workload; they are what keeps overload bounded.
* Keep retries to idempotent methods; decide the budget deliberately.
* Do not enable shadowing of writes unless every target is a sandbox.
* Run on a supported JDK (see above), keep Dependabot updates flowing, and review `docs/adr/` when changing the HTTP stack.
