# Routing

A request is routed in three steps: **match a route**, **choose an upstream** with the route's policy, then **forward**
(retries, timeouts and the circuit breaker are covered in [resilience.md](resilience.md)). Clients never choose where a
request goes: upstream URLs exist only in the configuration file (see [security.md](security.md)).

## 1. Matching

Before matching, the request path is canonicalised (`PathNormalizer`) so that **what is matched is exactly what is
forwarded**:

* escapes of unreserved characters are decoded (`%2e` -> `.`, `%61` -> `a`), other escapes keep their hex digits upper-cased;
* NUL, control characters, backslashes, non-ASCII characters and malformed `%` escapes are rejected (`400`);
* `%2F` / `%5C` are rejected unless `server.allowEncodedSlashes: true` (then they stay encoded);
* repeated `/` are merged and dot segments are removed without ever climbing above the root.

So `/public/%2e%2e/admin` is matched (and forwarded) as `/admin`: it can never reach a back end through a route that
only covers `/public`.

A route matches when **all** of its conditions hold:

| condition | semantics |
|---|---|
| `path: /health` | exact match on the normalised path (`/health/` and `/healthz` do not match) |
| `pathPrefix: /api` | segment-aware prefix: matches `/api`, `/api/`, `/api/x`, **not** `/apix`; `/` matches everything |
| `methods: [GET, POST]` | the request method is in the set |
| `headers: [{name: X-Env, equals: canary}]` | case-insensitive header name, case-sensitive value; `prefix:` and `present: true` are the other forms; the first value of a repeated header is used |
| `query: [{name: v, prefix: "2"}]` | same, on the first decoded value of the query parameter |

**Order of evaluation:** `priority` descending (default 0); among equal priorities the more specific route first - an exact
`path` beats a longer `pathPrefix` beats a shorter one, and every header/query/method condition adds specificity; among
equal specificity, file order. The first matching route wins; no match is `404` with `X-Routeplane-Error: no_route`.

`stripPrefix: true` removes the matched `pathPrefix` before forwarding (`/svc/users` -> `/users`); an upstream URL with a
base path (`http://host:8080/internal/v2`) prepends it.

## 2. Choosing an upstream

The candidates handed to the policy are the route's upstreams that are **currently eligible**: not marked down by active
health checks, not passively ejected, circuit breaker willing to admit a call, and below their concurrency limit. If a
route has `fallback.upstreams` they are used only when no primary is eligible. A request that retries avoids upstreams it
already tried when an alternative exists.

| policy | behaviour |
|---|---|
| `weighted-round-robin` (default) | smooth weighted round-robin (the nginx algorithm): deterministic and evenly interleaved; with weights 5/1/1 the sequence is `a a b a c a a`, exact proportions every `sum(weights)` requests. |
| `weighted-random` | proportional random choice; option `seed` makes the sequence reproducible. |
| `round-robin` | cycles in order, ignoring weights. |
| `least-active` | lowest `activeRequests / weight`; ties rotate so load spreads. |
| `hash` | weighted rendezvous hashing over `key`: a key always maps to the same upstream while the candidate set is unchanged, and when an upstream leaves only *its* keys move. A request without the key is spread round-robin. |

If a custom policy throws or returns something that was not offered, the first candidate is used, the problem is logged and
counted in `routeplane_policy_errors_total`: a faulty policy degrades balancing, never availability.

## 3. Writing a custom policy

The policy API (`dev.routeplane.routing`) has **no dependency on the networking layer**; a policy can be unit-tested with plain
objects:

```java
public final class StickyByTenantFactory implements RoutingPolicyFactory {
    @Override public String id() { return "tenant-sticky"; }

    @Override public List<String> validate(Map<String, String> options) {       // checked before a config is activated
        return options.containsKey("header") ? List.of() : List.of("option 'header' is required");
    }

    @Override public RoutingPolicy create(Map<String, String> options) {
        String header = options.get("header");
        return (request, candidates) -> {
            String tenant = request.header(header);                              // RoutingRequest: method, path, query, headers, cookies, peer address, request id
            int i = tenant == null ? 0 : Math.floorMod(tenant.hashCode(), candidates.size());
            return candidates.get(i);                                            // must be one of the candidates; Candidate: id, weight, activeRequests
        };
    }
}
```

Register it in `META-INF/services/dev.routeplane.routing.RoutingPolicyFactory` (one class name per line), put the jar on the
class path, and select it with `policy: {type: tenant-sticky, options: {header: X-Tenant}}`. Policies must be thread-safe,
fast and non-blocking; they run on the request path. Only classes registered through `ServiceLoader` can be selected - the
configuration cannot name arbitrary classes.
