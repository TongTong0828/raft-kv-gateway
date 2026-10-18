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

Successful throughput is the successful measurement count divided by 30 seconds. The all-request latency distribution includes returned errors; the successful-request distribution requires the correct status and 1 KiB payload. Errors do not count as successful throughput. A canceled iteration may lack a completed latency sample; its attempt still appears in the failure accounting. `unaccounted` is nominal configured arrivals minus attempted and dropped counts; small positive or negative boundary discrepancies are retained rather than silently rounded away.

KV tests use the original protobuf schema and direct gRPC calls to the elected leader. CAS write keys are per VU and run, with the returned revision carried forward. An uncertain mutation is read for reconciliation rather than blindly retried. No durability setting is weakened for the benchmark.

## Measured results

The full matrix completed **30 runs** on clean source commit [`84079aefad7ab765ffc8efe81ac56397f662b16b`](https://github.com/TongTong0828/raft-kv-gateway/commit/84079aefad7ab765ffc8efe81ac56397f662b16b). The [environment](results/benchmarks/environment.json), [per-run summary](results/benchmarks/summary.json), and unmodified per-run exports with resource samples are checked in. Values below are **minimum–maximum across three repeats**, not percentiles computed by averaging percentiles. Failure/drop columns sum the three measurement windows (90 seconds).

### HTTP gateway, fixed 1 KiB body

| Scenario / offered rate | Successful requests/s | Successful P99 (ms) | Failed attempts, total | Generator drops, total |
|---|---:|---:|---:|---:|
| gateway / 100/s | 100.00–100.03 | 4.08–4.90 | 0 | 0 |
| gateway / 500/s | 500.00–500.03 | 2.16–2.83 | 0 | 0 |
| gateway / 1,000/s | 1,000.03 | 1.30–2.29 | 0 | 0 |
| gateway / 2,000/s | 1,999.87–2,000.10 | 0.94–2.39 | 0 | 8 |
| gateway / 4,000/s | 3,985.43–3,999.53 | 0.69–1.62 | 0 | 713 |
| gateway / 8,000/s | 7,996.17–7,998.47 | 0.70–0.76 | 0 | 262 |

All attempted gateway requests succeeded. The generator did not deliver every scheduled arrival at higher rates, so these observations do not establish server saturation or a guaranteed 8,000 requests/s capacity. The highest-rate row reports actual successes, not configured offered load.

### Direct leader gRPC KV

| Scenario / offered rate | Successful requests/s | Successful P99 (ms) | Failed attempts, total | Generator drops, total |
|---|---:|---:|---:|---:|
| kv-read / 100/s | 100.00–100.03 | 4.00–5.00 | 0 | 0 |
| kv-write / 25/s | 25.00–25.03 | 10.00–11.00 | 0 | 0 |

Direct gRPC elapsed time uses `Date.now()` and therefore has millisecond resolution. CAS writes retain the normal persistent-log and quorum settings. These deliberately moderate rates validate a measured workload; they are not KV capacity sweeps.

### Slow backend and service isolation

| Scenario / offered rate | Successful requests/s | Successful P99 (ms) | Failed attempts, total | Generator drops, total |
|---|---:|---:|---:|---:|
| slow-backend / 100/s | 3.93 | 507.17–509.95 | 8,649 | 0 |
| isolation / 100/s | 100.03 | 2.36–3.02 | 0 | 0 |

The slow-only row counts 503 overload responses as failures, as intended. In `isolation`, the main row describes only `/fast`: each 100/s paired iteration also sends one `/slow` request, for 200 offered HTTP requests/s in total. Isolation drops are dropped pairs. The slow backend remains delayed by 500 ms with only two allowed inflight exchanges.

The accompanying slow traffic in the isolation runs completed 3.93 successful requests/s and returned 8,649 overload 503 responses across the three windows. Its raw `slow_latency` trend mixes successes and rejections; it is not a successful-request-only percentile.

The local machine is an Apple M4 Pro with 24 GiB host memory. Docker exposes 12 CPUs and about 7.65 GiB; the six application containers are capped at 1,984 MiB in total. The gateway has a two-CPU limit and the generator competes on the same shared Docker VM. This is a local reproducibility experiment on a shared development machine, not a dedicated production capacity certification. Generator drops indicate that the offered schedule was not fully produced; they cannot be reported as server throughput.
