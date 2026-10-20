# Benchmark method and results

Local records cover several implementations and measurement versions. The [current burst report](burst-handling.md) separates the 64-VU standard-profile series (service limit 100, generator 768 MiB) from the 256-VU burst series (service limit 256, generator 1,536 MiB), retaining every repeat and failed trial. Historical 32-VU strict passes and the original 30-run matrix remain below at their recorded source revisions. AWS EKS/NLB acceptance remains deferred. Configured offered load never establishes successful throughput.

## Current burst verification

Three 300-second runs per cohort passed the unchanged gate: at least 8,000 successful completions/s overall and in every ten-second bucket, successful full-call P99 ≤15 ms, zero eligible failures including drain, and reconciled outcomes. The 64-VU standard series delivered 8,390.65–8,395.59 successful/s with P99 0.82–1.05 ms; the 256-VU burst series delivered 8,363.66–8,381.35/s with P99 3.81–6.71 ms. See [per-repeat results, provenance and resource observations](burst-handling.md). These cohorts use different service admission and generator memory limits; their results are not attributed solely to the dispatch algorithm.

The earlier 256-VU/768 MiB series remains PASS/PASS/FAIL: its last repeat missed the bucket gate at 7,576.6/s despite zero eligible failures. A later diagnostic observed generator memory pressure but passed the gate, so that pressure does not prove the failed bucket's cause. Providing generator headroom was an explicit measurement change, followed by a fresh complete three-repeat series. All earlier attempts remain retained.

The four-CPU generator defaults to 1,536 MiB. `--generator-memory-mib` accepts an integer from 256 through 4096 MiB; metadata and Docker use the same selected value. Record this budget separately from the applied service profile and application budgets. The option changes no acceptance gate or gateway admission, connection or body limit.

## Strict sustained local acceptance

The historical 32-VU strict acceptance experiment uses source commit `3c1f5f659512b08f44d4f418136ad6326268e2dd` and pinned k6 **2.3.0**. One constant-arrival-rate scenario offers **8,400 requests/s**, with **32 fixed VUs**, **30 seconds of warmup**, **300 seconds of measurement**, and a separate five-second graceful drain. The full experiment is repeated three times on the same recorded local Docker topology. Offered load is configuration; successful throughput is calculated from validated response counts.

A request is eligible only if it starts inside the half-open measurement window `[30s,330s)`. It contributes to successful throughput only if it also completes inside that window with HTTP 200, the exact 1,024-byte ASCII `x` body, and an expected backend identity header. Warmup-started requests are excluded even if they finish after warmup. Redirects and application retries are disabled. Successes are assigned by completion time to thirty fixed ten-second buckets. Each repeat must achieve at least **8,000 successful requests/s overall and in every bucket**, with successful full-call **P99 ≤15 ms**, no eligible failures including drain, and complete outcome/sample accounting. Empty or missing buckets cannot silently disappear.

The measurement clock is derived from the pinned executor implementation: `scenario.progress × 330,000 ms`. Its shared scenario origin and elapsed calculation use Go `time.Since` ([clock path, lines 216–239](https://github.com/grafana/k6/blob/v2.3.0/lib/executor/constant_arrival_rate.go#L216-L239)); progress is capped at the regular-duration deadline. Every JavaScript progress read calls the callback directly ([getter](https://github.com/grafana/k6/blob/v2.3.0/internal/js/modules/k6/execution/execution.go#L101-L104)). Full-call latency is the elapsed difference immediately around `http.get`, including connection/setup work and client-observed completion, rather than only k6's HTTP transport duration. These semantics are tied to this executor and version and must be rechecked before an upgrade.

Eligible requests completing at or after the deadline are recorded separately as `lateSuccess` or `lateFailed`; they never increase window throughput. Progress has saturated by then, so no truncated drain latency is published. P99 describes successful eligible completions inside the measurement window, not late drain or all attempted requests. All eligible starts must reconcile with window outcomes, late outcomes, and invalid completions; successful bucket and latency-sample counts must reconcile with window successes. Generator drops are reported for the **whole experiment, including warmup**, because this single scenario does not supply a separate measurement-only drop count. They are neither server errors nor successful requests.

The checked-in historical matrix remains separate: it used scenario-cohort counts and HTTP transport latency. Strict results must be published per repeat with the full-call and transport distributions, minimum bucket throughput, failures, late outcomes, whole-experiment drops, resource samples, and source/image provenance. This is local shared-Docker evidence. It establishes no AWS or EKS deployment or performance result.

### Recorded results

| Repeat | Window successes | Successful requests/s | Full-call P99 (ms) | Lowest 10s successful rate | Late successes | Failed / unaccounted | Whole-experiment drops |
|---|---:|---:|---:|---:|---:|---:|---:|
| 1 | 2,515,808 | 8,386.03 | 0.877288 | 8,315.30 | 2 | 0 / 0 | 4279 |
| 2 | 2,516,345 | 8,387.82 | 0.83244 | 8,308.50 | 4 | 0 / 0 | 3775 |
| 3 | 2,517,867 | 8,392.89 | 0.637667 | 8,356.10 | 0 | 0 / 0 | 3369 |

All three consecutive repeats passed. Every repeat has thirty completion buckets; every bucket exceeded 8,000 successful completions/s. The [environment](results/strict-local-v1/environment.json), [summary](results/strict-local-v1/summary.json), unmodified k6 exports, resource samples, and [checksums](results/strict-local-v1/SHA256SUMS) are checked in. No percentiles were averaged. Late successes never increase window throughput or enter the gated percentile.

| Repeat | Generator peak CPU (cores) | Generator peak memory usage | Valid generator samples | Sampling errors |
|---|---:|---|---:|---:|
| 1 | 1.06 / 4 | 700.9MiB / 768MiB | 97 | 2 |
| 2 | 1.66 / 4 | 714.4MiB / 768MiB | 97 | 2 |
| 3 | 0.92 / 4 | 701.9MiB / 768MiB | 97 | 2 |

CPU percentages use 100% per core. These are sampled peaks, not continuous maximums. The generator approached its memory limit in long runs; successful exit codes and actual successful completion counts establish these observations, not unlimited generator headroom. Sampling can miss brief peaks. Two sampler errors per run (container startup and a Docker stats EOF) are retained in the raw files. After load, [all six application processes](results/strict-local-v1/post-load.json) were running without OOM/restart flags, and held body bytes/inflight requests were zero.

The 32-VU setting was selected after 256/64-VU experiments produced eligible 503s, even though their average throughput/P99 looked favorable. Those [failed and interrupted records, diagnostics and interpretation limits](results/strict-calibration-v1/README.md) remain public. Their concrete exceptions were not captured, so those historical errors remain unclassified. Subsequent [instrumented 64/256-VU reruns](backpressure.md) captured exact queue-full exceptions and admission-limit reasons, with cancellation/reuse and post-load resource checks. The instrumented results do not retrospectively prove the historical causes or establish zero failures at higher concurrency. Those diagnostic releases did not change admission, routing or asynchronous release behavior. The later [shared FIFO and monotonic generator](burst-handling.md) are separate changes with separately recorded outcomes; they do not retrospectively classify historical uninstrumented failures or validate negative timing exports.

### Reproduce the formal workload

```bash
./scripts/k6.sh build
./scripts/k6.sh provenance
./scripts/dev.sh build
./scripts/dev.sh up
python3 benchmarks/run.py --suite acceptance --profile standard --vus 32 --generator-memory-mib 768 --output work/strict-local-new
```

This reruns the historical workload on current source and the verified local generator; it does not reproduce the historical binary. Use the [current 64/256-VU commands](burst-handling.md#reproduce) for the new cohorts. Start from clean committed source, use an empty ignored output directory, and leave source/images unchanged until all three runs finish. The command uses the formal defaults: 8,400 offered/s, 30-second warmup, 300-second measurement, ten-second buckets and three repeats. It exits nonzero on a missed gate while retaining completed raw evidence. No AWS account is needed for this local workload. Node 20+ is needed only for measurement regression tests: `node --test benchmarks/test_*.mjs`.

### Measurement review fixes

Before the formal experiment, the measurement implementation was corrected to use the executor's monotonic elapsed calculation. `Date.now()` and `exec.instance.currentTestRunDuration` are not independent clock checks in this k6 version: wall-clock correction can move both. Regression cases cover forward/backward wall-clock changes, reversed executor progress, and exact completion boundaries.

Evidence validation now rejects missing or non-finite published latency statistics, inconsistent bucket/sample totals, and unreconciled eligible outcomes. The runner rejects duplicate normalized rates that would overwrite run filenames, requires clean committed source, checks that the source commit stays unchanged around measurements, and verifies actual running image identities. New acceptance runs also require all fixed 503 reason counters and reconcile their sum with eligible HTTP 503s, including drain. Unrecognized or absent gateway reasons remain `unclassified`; an actual backend 503 is not assigned a fabricated local cause. Results are retained as unmodified raw exports with derived summaries; the presence of an export alone is not a passing acceptance result.


## Historical reproduction

The recorded historical matrix used pinned k6 **2.3.0**, four generator CPUs and 768 MiB. Each scenario preallocated 64 VUs and permitted at most 256. Warmup and measurement were separate constant-arrival-rate scenarios. Metrics prefixed `measured_` include their scenario's graceful-drain completions and are not strict fixed-window counters. The current runner uses the verified local generator and defaults to 1,536 MiB; explicitly select `--generator-memory-mib 768` to retain the historical memory budget. New exports do not replace the recorded matrix. Building the current generator requires Python 3.12+, Git and Docker.

```bash
./scripts/k6.sh build
./scripts/k6.sh provenance
./scripts/dev.sh build
./scripts/dev.sh up
python3 benchmarks/run.py --suite gateway --generator-memory-mib 768 --output work/benchmarks/gateway
python3 benchmarks/run.py --suite kv --generator-memory-mib 768 --output work/benchmarks/kv
python3 benchmarks/run.py --suite isolation --generator-memory-mib 768 --output work/benchmarks/isolation
```

The gateway defaults are 100, 500, 1,000, 2,000, 4,000, and 8,000 offered requests/s, with **10 seconds warmup + 30 seconds measurement**, repeated three times at each level. Direct KV reads run at 100 operations/s, and CAS writes at 25 operations/s, also with three repeats. Payloads are exactly 1,024 bytes. The backend ID is a response header, so the body remains fixed size.

The isolation suite adds `/slow` on backend A with a 500 ms delay and `maxInflight=2`, and `/fast` on backend B with its independent limit of 100. It first measures the overloaded slow service alone, then pairs each slow request with one fast request. Its main summary describes the fast service; `slow_*` metrics in each raw export describe the accompanying slow traffic. Both run at 100 offered arrivals/s for three repeats. The runner restores backend A's normal process configuration afterward. It creates benchmark keys and example services in the current development stack.

For a short correctness check, use `--rates 100 --repeats 1 --warmup 1 --seconds 2`. Such a run is calibration, not a capacity measurement. `--suite all` runs all scenarios in sequence. A full default run takes about twenty minutes.

## What is recorded

Each output directory contains:

- `environment.json`: source commit and dirty flag, actual running-container image IDs and revision labels, pinned k6 image, Docker CPU/memory/architecture, JVM/container settings, payload and measurement durations. The runner rejects stale running images even when the local image tag has been rebuilt, and checks again before every run, including the recreated slow backend. The selected generator CPU/memory limits and applied service profile/revision are recorded separately from application budgets. Generator binary/source/patch checksums and immutable image identity identify the local timing implementation.
- One unmodified k6 `handleSummary` JSON export per run, including counters and latency distributions. These are aggregated run exports, not per-request traces.
- Per-run Docker resource samples for this project's containers and the load generator.
- `summary.json`: successful throughput, attempted requests, failed requests, 503/502/504 and transport outcomes, generator-dropped iterations, and both all-completed-request and successful-request latency statistics.

The historical `successfulRps` field is the successful measurement-scenario count divided by its configured 30 seconds, including successful completion during graceful drain. It describes that request cohort rather than strict response completion inside a fixed wall-clock window. The new acceptance gate separately requires fixed-window completion accounting. Historical HTTP latency uses k6 `response.timings.duration` (sending, waiting and receiving); it excludes blocked/DNS/connection setup time. The all-request latency distribution includes returned errors; the successful-request distribution requires the correct status and 1 KiB payload. Errors do not count as successful throughput. A canceled iteration may lack a completed latency sample; its attempt still appears in the failure accounting. `unaccounted` is nominal configured arrivals minus attempted and dropped counts; small positive or negative boundary discrepancies are retained rather than silently rounded away.

KV tests use the original protobuf schema and direct gRPC calls to the elected leader. CAS write keys are per VU and run, with the returned revision carried forward. An uncertain mutation is read for reconciliation rather than blindly retried. No durability setting is weakened for the benchmark.

## Historical measured results

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
