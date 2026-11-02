# ADR-0001: HTTP stack — `jdk.httpserver` + `java.net.http` on virtual threads

* Status: accepted (2026-11-02)
* Deciders: project author
* Scope: the data-plane listener, the upstream client, the admin listener. Not: the load generator and the
  fault-injection upstream (see "Test tooling" below).

## Context

Routeplane is an HTTP/1.1 reverse proxy that must:

* stream request and response bodies (no unbounded buffering) with correct `Content-Length` / chunked framing,
* propagate status codes and cancel upstream work when a request is abandoned or exceeds its deadline,
* keep upstream connections alive, with bounded concurrency per upstream,
* never contain hand-written HTTP parsing,
* stay small: few dependencies, easy to build with Gradle, Java 21+.

The brief says to prefer a mature asynchronous library *if* the JDK server cannot meet streaming, cancellation,
backpressure and connection-management requirements. So the question is not "which library is nicest" but "does the
JDK stack actually meet them, and what does it cost us if not".

## Options considered

| Option | Pros | Cons |
|---|---|---|
| **A. JDK `com.sun.net.httpserver` + `java.net.http.HttpClient`, handlers on virtual threads** | zero runtime dependencies; blocking style makes retry loops, deadlines and cleanup straightforward to write *and to test*; virtual threads give cheap concurrency; both sides are maintained with the JDK | server has few knobs (JVM-wide system properties); no connection-level hooks; client hides its pool; some lenient parsing (see spike) |
| B. Netty (`codec-http`, `transport`) | full control of both sides, early disconnect detection, pool introspection, best raw performance | ~2 000+ lines of asynchronous state machine for the data plane alone (partial writes, auto-read toggling, pool races, release discipline); several jars; far more ways to leak buffers or hang a connection |
| C. Jetty 12 (server + client) | good proxy primitives, strict parser, disconnect callbacks | large transitive footprint, servlet/core API churn between majors, still needs the same retry/deadline logic on top |
| D. Vert.x / Undertow / Helidon / Armeria | productive frameworks | each brings its own threading and lifecycle model; more framework than this project needs |
| E. Spring Boot / WebFlux | ecosystem | explicitly not wanted: no concrete justification for a framework here |

## Decision

Use **option A**: `HttpServer` (proxy and admin listeners) and one `HttpClient` per upstream, with every exchange
handled on its own virtual thread. The data plane is written in blocking style; deadlines are enforced by interrupting
the handling thread; all limits that the JDK exposes only as JVM-wide properties are applied at start-up.

All HTTP-stack specifics are isolated in `dev.routeplane.proxy` (`ProxyServer`, `UpstreamClient`) so that a Netty
implementation could replace them without touching routing, resilience, shadowing, configuration or metrics.

## Evidence (spike, JDK 21.0.8 and 25.0.2, Linux)

The probes below were run with raw sockets against a handler that echoes what it sees. The ones that Routeplane
relies on are now executable regression tests (`JdkHttpStackContractTest`) so a future JDK that changes behaviour
fails the build instead of silently weakening the proxy.

**Server (what the handler sees / what the client gets)**

| Probe | Result | Consequence |
|---|---|---|
| `Content-Length` + `Transfer-Encoding` | 400, connection closed | OK |
| duplicate / non-numeric / negative `Content-Length` | 400, connection closed | OK |
| `Content-Length: +3` | **accepted**, body framed with 3 bytes | validator rejects (`400`, `Connection: close`) |
| `Transfer-Encoding: chunked, identity` / `xchunked` | 501, closed | OK |
| absolute-form request target (`GET http://169.254.169.254/ ...`) | **accepted**; `getRequestURI()` carries the foreign authority | validator rejects absolute-form; routing only ever sees path + query |
| `CONNECT` | connection dropped | also rejected by validator (405) |
| HTTP version `HTTP/2.0` | **accepted** | validator rejects with 505 |
| missing `Host` (HTTP/1.1), duplicate `Host` | **accepted** (`Host=[a, b]`) | validator rejects with 400 |
| NUL byte in header value | **accepted** | validator rejects (control characters) |
| lowercase method (`get`) | **accepted** | validator rejects (method must be an upper-case token from the allowed set) |
| obs-fold header continuation | unfolded and accepted | tolerated (RFC 9112 allows a proxy to unfold) |
| dot segments, `%2e%2e`, `%2f` in path | passed through verbatim | `PathNormalizer`: decode unreserved, remove dot segments, reject encoded slash/backslash/NUL |
| 20 KB single header, default settings | accepted (default limit is very large) | `sun.net.httpserver.maxReqHeaderSize` is set at start-up |
| `maxReqHeaderSize=8192`, 9 KB header | connection reset **without a response** | no 431 possible at the JDK limit → hard limit is set above the configured soft limit so the app can answer 431 for the common case |
| 300 request headers (default `maxReqHeaders=200`) | connection dropped | acceptable, documented |
| `jdk.httpserver.maxConnections=4`, 6 idle connections | new connections closed immediately | connection cap works |
| `Expect: 100-continue` | `100 Continue` is sent **before** the handler runs | cannot reject on `Content-Length` before the interim response; documented |
| client leaves while handler is waiting | **not detected until the handler writes** (`Broken pipe` on write) | documented limitation; deadlines bound the wasted work |
| client stops reading a streamed response | handler blocks in `write`; `exchange.close()` from another thread **also blocks** (it tries to write the final chunk) | do not use `close()` as a watchdog |
| `Thread.interrupt()` on that blocked virtual thread | unblocks in ~6 ms with `ClosedByInterruptException`, channel closed | **deadline mechanism**: a scheduled interrupt of the handling thread |
| `sun.net.httpserver.maxRspTime=3` | stuck response aborted after ~3.9 s | global backstop for slow readers |
| `server.stop(2)` with an idle keep-alive connection | returned after ~200 ms | graceful drain is feasible |

**Client**

| Probe | Result | Consequence |
|---|---|---|
| restricted headers | `Host`, `Connection`, `Content-Length`, `Expect`, `Upgrade` rejected by the builder; `Transfer-Encoding`, `Keep-Alive`, `TE`, `Trailer` *allowed* | hop-by-hop headers are always stripped before building the request (a forwarded `Transfer-Encoding` would otherwise create a framing ambiguity) |
| default headers | `User-Agent: Java-http-client/<ver>` injected when absent | the client's own `User-Agent` is forwarded; when absent an empty one is sent (documented) |
| body framing | known length via `fromPublisher(p, n)` → `Content-Length`; unknown → chunked | replayable bodies get an exact length |
| truncated response (`Content-Length` > bytes sent, or chunked without terminator) | `IOException` on read | truncation is never mistaken for completion |
| server closes / resets before headers | `IOException: HTTP/1.1 header parser received no bytes` | classified as connection reset |
| `HttpRequest.timeout` | fires accurately (402 ms for 400 ms) but **only until headers**; a stalled body blocks indefinitely | body phase is covered by the overall deadline |
| closing the body stream mid-response | upstream sees the connection closed within ~2 ms | cancellation works |
| pooling | sequential requests reuse one connection; 20 parallel requests open 19 more; no upper bound | per-upstream concurrency is limited in the proxy |
| stale pooled connection | the JDK retries idempotent requests **once** internally | invisible to proxy counters; documented as a (rare) source of unmeasured upstream attempts |

## Consequences

Positive: small dependency surface (SnakeYAML, Gson, HdrHistogram only), data-plane code that reads top to bottom,
retries/deadlines/cancellation testable with plain JUnit.

Negative / accepted:

1. **No early-disconnect detection while waiting on an upstream.** A client that gives up still occupies an upstream
   slot until the upstream answers or the route deadline fires. Mitigated by deadlines and per-upstream limits.
2. **Limits are JVM-wide**, read once when the first server is created. Changing `maxHeaderBytes`, `maxConnections`,
   `idleTimeout` or the request-time limits requires a restart (reload rejects such changes explicitly).
3. **Connection counts are not observable from inside the JDK.** The proxy reports `routeplane_tcp_connections`
   from `/proc/net/tcp` on Linux; the benchmark runner records the same from outside.
4. **Header fidelity:** header-name case is normalised by the JDK, header order is not preserved, `Host` forwarding
   needs the (documented) `jdk.httpclient.allowRestrictedHeaders=host` property, no trailers, no HTTP/1.1 `Upgrade`.
5. **Aborting a chunked response mid-stream** (upstream died after the headers were sent) cannot be expressed through
   the API; the handler interrupts its own thread so the channel is closed *without* the terminating chunk. This is
   covered by a test that reads raw bytes.

## When to revisit

Move to Netty (behind the same seams) if any of these becomes a requirement: early cancellation on client
disconnect, per-listener connection limits/timeouts that must change at runtime, exact header-order/case
preservation, trailers, connection-pool introspection, HTTP/2 or TLS termination, or sustained load beyond what one
dispatcher thread can feed (the JDK server accepts and dispatches on a single selector thread).

## Test tooling

The fault-injection upstream and the open-loop load generator need things no conforming HTTP library will do on
purpose (RST mid-body, truncated bodies, byte-at-a-time streaming, pacing independent of responses). They use plain
sockets with the smallest possible request reader. That code is test tooling bound to loopback by default and is never
part of the proxy.

## Addendum: findings from implementing and testing on this stack

These are consequences of the decision that only showed up once real traffic ran through it. Each has a regression test.

1. **Nagle's algorithm costs ~40 ms per response.** The JDK server leaves `TCP_NODELAY` off; a response written as headers then body
   interacts with the client's delayed ACK. 1000 sequential requests through the proxy took ~40 s; with
   `-Dsun.net.httpserver.nodelay=true` they took ~1.7 s. `JdkTuning` sets the property before the first server exists (it is read once per
   JVM) and the start script sets it too. Tests and benchmarks run with it set.
2. **The JDK drains an unread request body on exchange completion, with an unbounded blocking read.** A client that sends
   `Content-Length: 1000`, then 10 bytes, then stalls, held a thread and a connection after the proxy had already answered (the
   deadline fired, the 504 went out, and `close()` blocked). Worse, when the JDK client's publisher thread is the one blocked reading the
   client's socket, the close-time drain waits on the channel's *uninterruptible* read lock. Fix: a bounded lingering close, and
   remembering which thread reads the client body so that it can be interrupted (which closes the channel). See ADR-0003.
3. **Readiness must follow draining, not completion of shutdown** (`isDraining()` originally reported only the final state).
4. **JDK-8369227 "Virtual thread stuck in PARKED state"** (JDK 25.0.0-25.0.2, 26.0.0): after a timed park, an untimed park of a virtual
   thread can be stranded by the earlier park's stale timeout task. Observed during this project as upstream responses that arrived only
   after the 10 s response timeout: 4 of 30 cold starts (13%) on Temurin 25.0.2 and 0 of 20 on 21.0.8; a thread dump taken during a stall showed a
   handler still in `Thread.sleep(250)` seconds later. A pure-JDK reproduction of the proxy shape did not reproduce it (0/30), so it is
   timing-dependent; upstream reports (the OpenJDK bug tracker and the loom-dev list) describe the same symptom, fixed in 25.0.3, 26.0.1 and 27.
   `JdkIssues` warns at start-up and in `version`, the Gradle build warns, and CI uses a 25.x that includes the fix.
5. **The JDK client retries an idempotent request once on a stale pooled connection**, invisible to proxy counters.

## Supported JDKs

* **JDK 21** (minimum, the build uses `--release 21`): fully supported; the reference runtime for all measurements in this repository
  (Temurin 21.0.8).
* **JDK 24, 25.0.3 or newer, 26.0.1 or newer, 27**: supported.
* **JDK 25.0.0-25.0.2 and 26.0.0: not recommended** (JDK-8369227). The proxy runs but may stall requests intermittently.
