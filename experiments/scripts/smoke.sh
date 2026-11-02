#!/usr/bin/env bash
# A 15-second real experiment to check that the whole toolchain works on this machine (not a benchmark).
set -euo pipefail
cd "$(dirname "$0")/../.."
OUT=${1:-experiments/work/smoke}
./gradlew :benchmarks:run --args="run --scenario experiments/scenarios/basic.yaml --out $OUT --runs 1 --rates 100 --warmup 5s --duration 5s"
echo "report: $OUT/report.md"
