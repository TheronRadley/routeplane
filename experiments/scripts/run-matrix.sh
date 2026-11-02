#!/usr/bin/env bash
# Runs the full experiment matrix (all scenarios in experiments/scenarios) and writes JSON/CSV/Markdown/HTML reports.
# Takes about an hour on a small machine. Keep the machine otherwise idle: the load generator, the proxy and the
# upstreams share the CPUs and any other work distorts the measurements.
#   usage: experiments/scripts/run-matrix.sh [output-dir] [extra bench options, e.g. --only basic,shadow --runs 1]
set -euo pipefail
cd "$(dirname "$0")/../.."
OUT=${1:-experiments/results/campaign-$(date +%F)}
shift || true
./gradlew :benchmarks:run --args="matrix --out $OUT $*"
echo "report: $OUT/report.html  ($OUT/report.md, results.csv, results.json)"
