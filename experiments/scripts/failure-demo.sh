#!/usr/bin/env bash
# A small failure experiment you can watch: take one upstream away and see retries, the circuit breaker and recovery.
# Needs dev-up.sh to be running (config/routeplane.yaml routes /api to upstreams "a" and "b" with retries and a breaker).
set -euo pipefail
PROXY=http://127.0.0.1:8080
ADMIN=http://127.0.0.1:9090
CTRL_A=http://127.0.0.1:19001

serve() {  # serve <n>: send n requests, print "<status> <upstream>" counts
  for _ in $(seq 1 "$1"); do
    curl -s -o /dev/null -D - -w 'STATUS %{http_code}\n' "$PROXY/api/x" | tr -d '\r' | awk 'tolower($1)=="x-upstream-id:"{u=$2} /^STATUS/{s=$2} END{print s, (u==""?"(proxy)":u)}'
  done | sort | uniq -c
}
metric() { curl -s "$ADMIN/metrics" | grep -E "^$1" || echo "  (no samples)"; }

echo "== 1. healthy: 100 requests, expect roughly 70/30 between a and b (weighted-round-robin 70:30)"
serve 100
echo
echo "== 2. make upstream a answer every request with 503"
curl -s -X PUT "$CTRL_A/control/profile" -d '{"errorRate":1.0,"errorStatus":503}' > /dev/null
echo "   100 requests: the proxy retries GETs on the other upstream, so clients mostly see 200 from b (and some 503 before the breaker opens)"
serve 100
echo
echo "== 3. what the proxy recorded"
echo "   circuit breaker state (0 closed, 1 open, 2 half-open):"; metric 'routeplane_circuit_breaker_state'
echo "   retries:"; metric 'routeplane_retries_total'
echo "   breaker rejections:"; metric 'routeplane_circuit_breaker_rejections_total'
echo "   breaker history (admin API):"; curl -s "$ADMIN/admin/circuits" | grep -E '"(upstream|state|reason|from|to)"' | head -20
echo
echo "== 4. recovery: lift the fault and wait for the breaker's openDuration (10 s) so half-open probes can close it"
curl -s -X POST "$CTRL_A/control/reset" > /dev/null
sleep 12
serve 100
echo "   breaker state now:"; metric 'routeplane_circuit_breaker_state'
echo
echo "Done. Stop the environment with experiments/scripts/dev-down.sh"
