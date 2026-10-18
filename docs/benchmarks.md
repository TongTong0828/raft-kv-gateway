# Benchmark method and results

The benchmark code measures this project's actual implementation. It does not adopt the earlier proposed resume claim of 8,000 successful requests/s at 15 ms P99. The offered-load sequence is an experiment, not a guaranteed throughput target.

## Reproduction

Requirements: the running Compose stack, Python 3.9+, and Docker. The runner uses pinned k6 **2.3.0** in a separate container, limited to four CPUs and 768 MiB. Each scenario preallocates 64 VUs and permits at most 256. Warmup and measurement are separate constant-arrival-rate scenarios; metrics prefixed `measured_` include only the measurement window.

```bash
./scripts/dev.sh build
./scripts/dev.sh up
python3 benchmarks/run.py --suite gateway --output work/benchmarks/gateway
python3 benchmarks/run.py --suite kv --output work/benchmarks/kv
python3 benchmarks/run.py --suite isolation --output work/benchmarks/isolation
```

The gateway defaults are 100, 500, 1,000, 2,000, 4,000, and 8,000 offered requests/s, with **10 seconds warmup + 30 seconds measurement**, repeated three times at each level. Direct KV reads run at 100 operations/s, and CAS writes at 25 operations/s, also with three repeats. Payloads are exactly 1,024 bytes. The backend ID is a response header, so the body remains fixed size.

The isolation suite adds `/slow` on backend A with a 500 ms delay and `maxInflight=2`, and `/fast` on backend B with its independent limit of 100. It first measures the overloaded slow service alone, then pairs each slow request with one fast request. Its main summary describes the fast service; `slow_*` metrics in each raw export describe the accompanying slow traffic. Both run at 100 offered arrivals/s for three repeats. The runner restores backend A's normal process configuration afterward. It creates benchmark keys and example services in the current development stack.

For a short correctness check, use `--rates 100 --repeats 1 --warmup 1 --seconds 2`. Such a run is calibration, not a capacity measurement. `--suite all` runs all scenarios in sequence. A full default run takes about twenty minutes.

## What is recorded

Each output directory contains:

- `environment.json`: source commit and dirty flag, actual running-container image IDs and revision labels, pinned k6 image, Docker CPU/memory/architecture, JVM/container settings, payload and measurement durations. The runner rejects stale running images even when the local image tag has been rebuilt, and checks again before every run, including the recreated slow backend.
- One unmodified k6 `handleSummary` JSON export per run, including counters and latency distributions. These are aggregated run exports, not per-request traces.
- Per-run Docker resource samples for this project's containers and the load generator.
- `summary.json`: successful throughput, attempted requests, failed requests, 503/502/504 and transport outcomes, generator-dropped iterations, and both all-completed-request and successful-request latency statistics.

Successful throughput is the successful measurement count divided by 30 seconds. The all-request latency distribution includes returned errors; the successful-request distribution requires the correct status and 1 KiB payload. Errors do not count as successful throughput. A canceled iteration may lack a completed latency sample; its attempt still appears in the failure accounting. `unaccounted` is nominal configured arrivals minus attempted and dropped counts; an occasional `-1` records the scheduler's window-boundary arrival rather than silently rounding it away.

KV tests use the original protobuf schema and direct gRPC calls to the elected leader. CAS write keys are per VU and run, with the returned revision carried forward. An uncertain mutation is read for reconciliation rather than blindly retried. No durability setting is weakened for the benchmark.

## Measured results

The release result table is populated from the full-run exports in `docs/results/benchmarks/`. The source commit and environment in those exports define the measured version. Consult the raw files for repeat variation, errors, and dropped arrivals rather than reading a single percentile as a universal service guarantee.

The local machine is an Apple M4 Pro with 24 GiB host memory. Docker exposes 12 CPUs and about 7.65 GiB; the six application containers are capped at 1,984 MiB in total. The gateway has a two-CPU limit and the generator competes on the same shared Docker VM. This is a local reproducibility experiment on a shared development machine, not a dedicated production capacity certification. Generator drops indicate that the offered schedule was not fully produced; they cannot be reported as server throughput.
