# ADR-0003: Deadlines by interruption, and the policy for unread request bodies

* Status: accepted (2026-11-02)

## Context

The data plane is written in blocking style on virtual threads. It needs (a) an overall deadline that also covers blocked socket reads
and writes (slow clients, stalled uploads, stuck upstream bodies), and (b) a way to end an exchange without letting the JDK server
perform an unbounded blocking drain of a request body the client has not finished sending.

## Decisions

1. **Deadline = scheduled interrupt of the handling thread** (`Deadlines.Watch`). Verified in `JdkHttpStackContractTest`: interrupting a
   virtual thread blocked in a socket write unblocks it in milliseconds and closes the channel; `HttpClient.send` throws
   `InterruptedException` and cancels the exchange. The interrupt is issued under a lock that `close()` also takes, so no interrupt can
   arrive after the exchange finished; a flag that fired but was not consumed is cleared in `close()`. Alternatives rejected:
   `exchange.close()` from a watchdog thread (it blocks writing the final chunk to a client that is not reading), and per-socket timeouts
   (not reachable through the JDK server API).
2. **Aborting a response mid-stream** (headers already sent): the thread is interrupted and then the exchange is closed, which makes the
   channel write inside `close()` fail so the terminating chunk is never sent. The client sees a truncated chunked body, never a
   complete-looking one.
3. **Unread request bodies:** `ProxyRequest.bodyUnread()` tracks whether a announced body was fully consumed. When the exchange completes
   with an unread body the JDK would drain it with a blocking read. Instead the exchange is closed inside a *1 second lingering watch*:
   a cooperative client (still uploading) gets a clean FIN and reliably receives the response; a stalled client is cut after 1 s. The
   thread that is blocked reading the client's socket may be the JDK client's publisher thread (it holds the channel's uninterruptible
   read lock), so `GuardedInputStream` records its reader and the watch interrupts it as well, which closes the channel and releases
   everything.
4. **Client-side failures are never blamed on the upstream.** The guard distinguishes "the client's stream threw" and "the body limit was
   exceeded" from upstream errors, so aborted uploads do not trip breakers or ejection.

## Consequences

Every blocking operation of an exchange - including ones the JDK performs on our behalf at completion - has an upper bound. The price is
that a client still sending a large body when an early error response is produced may observe a reset instead of the response.
