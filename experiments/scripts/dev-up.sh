#!/usr/bin/env bash
# Local multi-upstream environment, no cloud needed:
#   three fault-injection upstreams on 9001-9003 (control APIs on 19001-19003) and the proxy from config/routeplane.yaml
#   (proxy http://127.0.0.1:8080, admin http://127.0.0.1:9090).
# Override the launchers with ROUTEPLANE_BIN / FAULT_UPSTREAM_BIN if you built them elsewhere; stop with dev-down.sh.
set -euo pipefail
cd "$(dirname "$0")/../.."
if [ -z "${ROUTEPLANE_BIN:-}" ] || [ -z "${FAULT_UPSTREAM_BIN:-}" ]; then
  ./gradlew -q :app:installDist :fault-upstream:installDist
fi
PROXY=${ROUTEPLANE_BIN:-app/build/install/routeplane/bin/routeplane}
UPSTREAM=${FAULT_UPSTREAM_BIN:-fault-upstream/build/install/fault-upstream/bin/fault-upstream}
CONFIG=${CONFIG:-config/routeplane.yaml}
RUN=experiments/work/dev
mkdir -p "$RUN"
[ -f "$RUN/proxy.pid" ] && kill -0 "$(cat "$RUN/proxy.pid")" 2>/dev/null && { echo "already running (see $RUN); run dev-down.sh first"; exit 1; }
for spec in "a 9001 19001" "b 9002 19002" "c 9003 19003"; do
  set -- $spec
  nohup "$UPSTREAM" --id "$1" --port "$2" --control-port "$3" --delay-ms 5 --payload-bytes 512 > "$RUN/upstream-$1.log" 2>&1 &
  echo $! > "$RUN/upstream-$1.pid"
done
nohup "$PROXY" serve --config "$CONFIG" > "$RUN/proxy.log" 2>&1 &
echo $! > "$RUN/proxy.pid"
for _ in $(seq 1 120); do
  if curl -fsS http://127.0.0.1:9090/health/ready > /dev/null 2>&1; then
    echo "up: proxy http://127.0.0.1:8080  admin http://127.0.0.1:9090  upstreams a/b/c on 9001-9003 (logs in $RUN)"
    exit 0
  fi
  sleep 0.5
done
echo "the proxy did not become ready; see $RUN/proxy.log" >&2
exit 1
