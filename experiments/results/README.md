# Experiment results

Each subdirectory is one **campaign**: a complete run of the scenarios in `../scenarios` produced by
`experiments/scripts/run-matrix.sh` (see [docs/benchmarking.md](../../docs/benchmarking.md)).

```
campaign-YYYY-MM-DD/
  campaign.json    environment (CPU, cores, memory, cgroup limits, Java), command line, git commit, start/finish time
  runs/*.json      one file per run: every measurement, the per-second series, fault events, breaker transitions
  results.csv      one row per run (all headline metrics), for spreadsheets and plotting tools
  results.json     all runs plus the campaign metadata in a single file
  report.md        tables and charts, generated from the runs
  report.html      the same report as a single self-contained page (charts inline)
  charts/*.svg     the charts used by report.md
```

Everything in here is **real output of the runner on the machine recorded in `campaign.json`**: nothing is hand-edited, extrapolated or
simulated. The reports are regenerated from `runs/` with

```bash
./gradlew :benchmarks:run --args="report --in experiments/results/campaign-YYYY-MM-DD"
```

Read the *How to read this report* and *Limitations* sections of each report before drawing conclusions: the load generator, the proxy and the
upstreams shared two virtual CPUs, so absolute numbers describe that setup only and anything above roughly 1,000 requests per second is flagged as
saturated.
