#!/usr/bin/env bash
# Stops everything started by dev-up.sh.
cd "$(dirname "$0")/../.."
RUN=experiments/work/dev
for f in "$RUN"/*.pid; do
  [ -f "$f" ] || continue
  kill "$(cat "$f")" 2>/dev/null || true
  rm -f "$f"
done
echo "stopped"
