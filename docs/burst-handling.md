# Monotonic timing and 64/256-VU burst verification

Both specified local cohorts passed all three 300-second repeats: 64 VUs with the standard service limit of 100, and 256 VUs with the explicit burst service limit of 256. The new 256-VU series achieved 8,363.6567–8,381.3467 actual successful completions/s with successful full-call P99 of 3.8084–6.7095 ms. Every 10-second bucket in each accepted repeat exceeded 8,000 successful/s; the lowest was 8,115.3/s. All six accepted repeats had zero eligible failures, late failures and unaccounted outcomes.

The earlier 256-VU series at a 768 MiB generator limit passed two repeats and failed one at 7,576.6 successful/s in bucket 25. That failure remains in the published record. A separate diagnostic directly observed generator memory-cap pressure and swap, motivating the separately identified 1,536 MiB series with all native thresholds and final accounting gates retained. This is acceptance under an explicit resource envelope, not a claim that the original failure's exact cause has been proven.

64 VUs, standard service limit 100, 768 MiB generator:

| Repeat | Gate | Window successes | Successes/s | Lowest 10 s bucket/s | Full-call P99 ms | Eligible failures | Late successes | Whole-experiment drops |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| 1 | PASS | 2,517,832 | 8,392.773 | 8,358.3 | 1.0547 | 0 | 9 | 3,905 |
| 2 | PASS | 2,517,196 | 8,390.653 | 8,324.7 | 0.9349 | 0 | 4 | 2,841 |
| 3 | PASS | 2,518,677 | 8,395.590 | 8,369.0 | 0.8210 | 0 | 3 | 2,021 |

256 VUs, explicit burst service limit 256, 1,536 MiB generator:

| Repeat | Gate | Window successes | Successes/s | Lowest 10 s bucket/s | Full-call P99 ms | Eligible failures | Late successes | Whole-experiment drops |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| 1 | PASS | 2,509,097 | 8,363.6567 | 8,115.3 | 6.1510 | 0 | 3 | 10,916 |
| 2 | PASS | 2,513,943 | 8,379.8100 | 8,202.4 | 6.7095 | 0 | 2 | 6,114 |
| 3 | PASS | 2,514,404 | 8,381.3467 | 8,239.2 | 3.8084 | 0 | 1 | 5,698 |

Whole-experiment dropped arrivals remain visible even when the success-rate gate passes. These are separate resource/profile cohorts; the new 256-VU series is not presented as a latency improvement over the old series.

These are local measurements on the shared Docker VM. They establish no AWS/EKS deployment or cloud performance result. The 64-VU cohort uses the standard service admission limit of 100; the 256-VU cohort uses the explicit burst limit of 256. Their generator memory limits are 768 MiB and 1,536 MiB respectively; both retain four generator CPUs. The 256-VU outcome therefore cannot be attributed solely to the dispatch algorithm.

## Measurement and retained evidence

The accepted 64-VU series uses clean source `2c2f4fe2d002fa569a9382a2b30a6c56e6b7f2c6`; the new 256-VU series uses `e3ae41cd03e60d11a883c7970ef688a7448f129e`, which changes only the benchmark runner memory option and its tests. The five application modules retain identical production source trees across those two commits; `e3ae41c` is a benchmark resource-option change, not a new dispatcher implementation. Each series uses its recorded immutable application image. The runner checks all six running image identities before each run and the exact committed service configuration/revision before and after measurement. The retained post-load helper separately checks the images/revisions and process state afterward. Each cohort's `environment.json` also records the verified generator image ID, binary/source/patch hashes, actual platform and resource limits. The [evidence index](results/burst-local-v1/evidence.json) records every case and its acceptance decision. The accepted 256-VU [repeat 1](results/burst-local-v1/final-256-r1/summary.json), [repeat 2](results/burst-local-v1/final-256-r2/summary.json) and [repeat 3](results/burst-local-v1/final-256-r3/summary.json) retain individual summaries, exports, environment and post-load records; the same structure holds for the three `formal-64` cases.

Each formal repeat offers 8,400 arrivals/s, with 30 seconds warmup, 300 seconds measurement, 10-second completion buckets and five seconds graceful drain. Preallocated and maximum VUs are equal. A success must start and complete inside [30s,330s), return HTTP 200, the exact 1,024-byte body and an expected backend identity. The unchanged gate requires at least 8,000 successful completions/s overall and in every bucket, successful full-call P99 <=15 ms, zero eligible failures including drain, and complete outcome/sample accounting. Late successes do not increase throughput or enter the percentile. Redirects and application retries are disabled.

Full-call latency still uses the pinned executor's monotonic progress clock immediately around the synchronous HTTP call. The native HTTP duration is published separately. Generator drops describe the entire experiment, including warmup; they are excluded from successes. No percentile is averaged across repeats. All raw exports, failed calibrations, dropped arrivals, resource samples and post-load observations remain available. The observer polls management metrics about once per second; Docker sampling and that light observer remain active during load, while builds and project tests do not.

## Timing repair

Historical exports contain negative native HTTP durations: the 256-VU baseline minimum was -0.036264 ms, and two later 32-VU replays had minima -0.51343 and -0.001181 ms. Their invalid summaries remain unchanged. [Upstream k6 tracing](https://github.com/grafana/k6/blob/e0887846143ab176d4b5483c9d52cf3b3e009f1a/lib/netext/httpext/tracer.go) stored Unix wall timestamps and reconstructed times without monotonic data; [Go subtraction rules](https://pkg.go.dev/time#hdr-Monotonic_Clocks) then permit wall-clock changes to affect elapsed values. This identifies a defect in the clock domain, without proving the precise host event behind each historical sample.

The local k6 v2.3.0 patch stores offsets from one process monotonic epoch (+1 preserves the unset sentinel), and snapshots callback timestamps before capturing its end point. This also closes the deterministic finalization race in which a late callback could otherwise be included after an already-captured end time. Wall `EndTime`, units, component calculations, connection reuse and HTTP/2 overlap behavior are retained. The patch adds no new clamping or filtering; existing normalization and overlap handling are unchanged. The acceptance parser and gates are unchanged.

Hand-derived wall-jump and callback-order cases were RED against the old behavior and GREEN after the patch. Full `httpext` validation passed 277 tests/subtests; a focused tracer/cancellation race run passed 219; provenance validation passed 10 tests. An initial network-isolated suite failed upstream's external DNS test; that failure is retained separately from the later full passing run. A repeated cached ARM64 build reproduced the same binary SHA256. A native AMD64 build path is provided; local AMD64 execution is not claimed. See [generator source, pins and rebuild instructions](../benchmarks/k6/README.md). k6 and its patch are a separate AGPLv3 program; original Java application source remains Apache-2.0.

## Burst scheduling and admission

The previous flow chose an endpoint before acquiring capacity. One native pool could reject while another healthy pool had room. The new service FIFO waits under the original header-started total deadline, then selects a healthy endpoint with a free reservation slot (least reserved, round-robin ties). The request is sent once. A reservation spans native acquisition, the borrowed exchange and actual asynchronous native return. An outer cancellation does not prematurely free an in-flight native acquisition; a late lease is closed. Queue expiry returns 504. The native 100 ms acquisition guard and 32-pending-acquire bound remain safeguards after dispatch.

Physical connection limits remain 16 per service/endpoint pool (32 across the two orders pools in this cohort), with global budgets of 256 exchanges, 512 business connections and 64 MiB held body bytes. The standard service limit remains 100. `--profile burst` explicitly selects `examples/orders-burst.json` with service limit 256; waiting requests still hold service/global permits. Accepting a larger per-service burst does not increase physical connection limits or expand the process-wide budget.

| Calibration (60 s measurement) | Gate | Successful/s | Lowest bucket/s | Full-call P99 ms | Eligible failures | Whole-experiment drops |
|---|---|---:|---:|---:|---:|---:|
| 64 VUs / standard 100 | PASS | 8,366.783 | 8,304.2 | 1.2315 | 0 | 9,190 |
| 256 VUs / standard 100 | FAIL | 8,381.350 | 8,304.0 | 1.7535 | 897 | 249 |
| 256 VUs / burst 256 | PASS | 8,362.683 | 8,298.6 | 14.8773 | 0 | 2,587 |

These calibrations are not formal 300-second repeats. The 256-VU standard-profile calibration failed with 897 classified admission-limit rejections; its zero-error requirement was not excused because throughput remained high. The explicit burst profile removes that particular configured admission bottleneck by admitting up to 256 service exchanges, while preserving the global limit and physical pool sizes. Its passing calibration does not make the later 768 MiB formal series pass.

Real-network regressions cover a 64-request burst behind one gated slot, a slow healthy peer while another has capacity, queued POST cancellation/expiry without forwarding, service isolation and admission, retirement, health recovery and head-expiry wakeups. Independent review additionally found a successful native acquisition racing retirement could publish failure before its late lease returned. A gated native-handler regression reproduced that ordering; failure now waits for actual return, retaining the reservation. This new ownership fix is not evidence that every historical 503 was a leak. Java verification passed 66 tests; benchmark Python 25, Node 13, ownership 4, and five executable-JAR notice audits also passed.

A later Linux/amd64 CI run exposed a test precondition race: the aggregate dispatch-queued gauge included tickets still in transit, so it did not prove that a retirement-test tail had joined the FIFO. The [failed CI and test-only synchronization correction](results/burst-ci-v1/README.md) retain the deterministic replay, actual coordinator-membership assertions, 50 retirement repetitions and 20 repetitions of each liveness case. The application production source is unchanged; these records supplement the measured-source evidence above.

`gateway_dispatch_queued` counts in-transit/waiting tickets; `gateway_dispatch_reserved` includes native acquire/borrow/return and retired pools until cleanup. They supplement registered-pool gauges, which exclude removed pools. Post-load checks require all eight inflight/body/dispatch/native-acquire/borrow/wait/return gauges to reach zero and inspect all six actual application processes for OOM/restart flags. These observations are scoped samples, not a proof of all interleavings.

## Retained failed series and generator resource investigation

The original 256-VU burst cohort used the same dispatcher and patched generator but limited the generator to 768 MiB:

| Repeat | Gate | Window successes | Successes/s | Lowest 10 s bucket/s | Full-call P99 ms | Eligible failures | Late successes | Whole-experiment drops |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| 1 | PASS | 2,519,621 | 8,398.737 | 8,374.8 | 1.0629 | 0 | 0 | 396 |
| 2 | PASS | 2,519,953 | 8,399.843 | 8,393.8 | 0.9525 | 0 | 1 | 497 |
| 3 | FAIL | 2,510,127 | 8,367.090 | 7,576.6 | 1.5188 | 0 | 0 | 9,948 |

Repeat 3 failed the unchanged every-bucket throughput gate at zero-based bucket 25 (measurement 250–260 s; approximately experiment 280–290 s). Its 8,367.090/s average and 1.5188 ms P99 do not override the 7,576.6/s bucket. It had no eligible failures or unaccounted outcomes, but dropped 9,948 arrivals across the whole experiment. Its [failed summary](results/burst-local-v1/formal-256-r3/summary.json), [raw export](results/burst-local-v1/formal-256-r3/acceptance-8400-r1.json), Docker samples, management events and post-load checks remain retained under `formal-256-r3`. No historical failed run is relabeled or replaced by the later diagnostic.

The separately marked `diagnostic-256-gc-profile` run retained 256 VUs, 8,400 offered/s, 30+300 seconds, four generator CPUs and the 768 MiB generator limit. Extra runtime overrides enabled Go GC logging, profiling and cgroup observation; the gateway also had a separately recorded GC-log override. In that run, cgroup `memory.events.max` first increased at launch +246.25 s and finished at 1,613, recorded memory.peak reached 805,310,464 bytes against an 805,306,368-byte cap, and sampled swap usage reached 288,444,416 bytes. OOM counters and CPU quota throttling remained zero in that diagnostic. This directly establishes memory-cap pressure and swap during the diagnostic. It does not retrospectively prove that pressure caused the earlier failed bucket, and the diagnostic itself passed its workload gates.

The retained 70-second CPU profile attributes 2.11 of 61.36 sampled CPU-seconds (3.44%) to threshold/Trend sorting. Sorting was real but was not the dominant profiled CPU consumer. Generator GC logs recorded a maximum individual stop-the-world pause of 11 ms; the gateway's maximum completed Serial-GC pause during the generator subprocess lifetime was 51.158 ms. These observations do not isolate a single cause of the historical throughput dip. The [diagnostic analysis](results/burst-local-v1/diagnostic-256-gc-profile/analysis-summary.json), [runtime overrides](results/burst-local-v1/diagnostic-256-gc-profile/runtime-overrides.json), [CPU profile](results/burst-local-v1/diagnostic-256-gc-profile/generator-cpu-240s-70s.pprof) and GC logs retain those observations. The bounded follow-up changed generator memory headroom rather than disabling thresholds, filtering samples, relaxing the parser or weakening the final gates.

The new 256-VU series explicitly sets 1,536 MiB and retains four generator CPUs, the verified immutable generator, all native thresholds and the same strict Python gates. A real negative control under the new resource setting returned successful HTTP responses but used a 0.001 ms P99 target; it [correctly failed both the native threshold and final report](results/burst-local-v1/negative-p99-headroom/summary.json). Its deliberately impossible target is not a formal performance result.

For all three new repeats, the state-only observer selected the generator by its exact `/results` bind source and verified the immutable image, 1,610,612,736-byte memory limit, four CPUs and absence of Go runtime overrides. It retained successful one-second samples and observation errors, including container-removal/EOF errors, without changing the generator command or workload. The indexed cgroup observations are:

| New 256-VU repeat | Valid / error samples | Recorded memory.peak bytes | Maximum sampled swap.current bytes | Maximum memory.events max / oom / oom_kill | CPU throttled periods / microseconds |
|---|---:|---:|---:|---:|---:|
| 1 | 330 / 3 | 902,123,520 | 0 | 0 / 0 / 0 | 1 / 15,986 |
| 2 | 331 / 2 | 889,126,912 | 0 | 0 / 0 / 0 | 0 / 0 |
| 3 | 330 / 2 | 925,908,992 | 0 | 0 / 0 / 0 | 0 / 0 |

These records show no observed generator memory-cap event, swap usage or OOM in the new series. Repeat 1 did record one CPU-throttled period totaling 15,986 microseconds. The peaks exceeded the old 768 MiB cap, supporting the choice to make generator headroom explicit; the records do not prove an absence of stalls or explain the old failed bucket. New P99 values and all 10,916/6,114/5,698 dropped arrivals remain reported rather than portrayed as universal improvement. Raw observations, selected-container identity and observer validation are retained for [repeat 1](results/burst-local-v1/final-256-r1/observer.json), [repeat 2](results/burst-local-v1/final-256-r2/observer.json) and [repeat 3](results/burst-local-v1/final-256-r3/observer.json), alongside their compressed cgroup streams and decompressed-content checksums.

All six accepted repeats passed the recorded application image/configuration attribution and post-load checks. All eight ownership gauges reached zero; all six application processes were running with OOMKilled=false and restart count zero. The new cohort's [first](results/burst-local-v1/final-256-r1/post-load.json), [second](results/burst-local-v1/final-256-r2/post-load.json) and [third](results/burst-local-v1/final-256-r3/post-load.json) post-load records preserve those checks. These observations cover their recorded scope, without proving all interleavings.

## Fault and local Kubernetes verification

The [isolated Docker fault suite](results/burst-local-v1/verification/burst-faults.json) passed eight behavior checks plus cleanup on clean source `e3ae41c`, using the same application image as the new 256-VU series (`sha256:3ed95e21eab191e1fb229e4a61766fc3a18116a55ac765f7171f45d25c2ed0a9`). It checked initial routing, bounded concurrent history, atomic route update during traffic, cached forwarding during total control-plane outage, Raft-only partition, snapshot installation, full restart and body-budget rejection. During the partition the old leader remained reachable at its application RPC port; its read returned `DEADLINE_EXCEEDED`, its Watch was fenced with `UNAVAILABLE`, and the majority committed a mutation. The returning follower installed snapshot index 2416 after its old applied index 16 had fallen behind the purged log start 2049. A full restart retained the value and modification revision 2417. The body-budget test observed 66,588,613 held bytes (within 64 MiB), two actual HTTP 503 rejections and no OOM; held bytes and inflight requests returned to zero.

The [local kind run](results/burst-local-v1/verification/burst-kind.json) passed on the same clean source/image, using pinned Kubernetes v1.37.0 and a private kubeconfig. One local Kubernetes node hosted three Raft voters: this is not a multi-host or multi-AZ experiment. The run checked three ready controls, a successful 1 KiB HTTP response, replacement of `cp-0` with a new Pod UID while all three PVCs and the committed value/version persisted, and gateway rollout followed by successful HTTP forwarding. The EKS overlay was rendered only (`deployed=false`). The [live image witness](results/burst-local-v1/verification/burst-deployment-image-witness.json) records the six fault-container identities, kind Pod image statuses and owned-resource cleanup. A [post-run immutable descriptor proof](results/burst-local-v1/verification/kind-image-proof/mapping.json) hashes the original kind image ID (`375f5ca…`) as an outer OCI index pointing at the measured Docker image index (`3ed95e…`), its ARM64 manifest and config/source label. Raw descriptor bytes and the initial probe parser correction are retained. Both temporary environments were removed; the primary Compose volumes were preserved. AWS/EKS execution remains deferred at the owner's request.

## Evidence and acceptance decision

The [evidence index](results/burst-local-v1/evidence.json) marks `64-standard` and `256-burst-headroom` as three recorded, passing repeats each. This establishes the stated 1 KiB local gateway target under the documented profiles, generator resources and 300-second windows: at least 8,000 actual successful completions/s overall and in every 10-second bucket, successful full-call P99 at most 15 ms, and zero eligible failures with reconciled accounting. It does not establish external-client or cloud latency, a different payload/workload, or unlimited sustained runtime.

The same index retains all nine formal trials, including the failed `formal-256-r3`, all three calibrations, the diagnostic and the intentionally failing P99 control. The [standard-profile 256-VU calibration failure](results/burst-local-v1/calibration-256-standard/summary.json) and prior invalid timing evidence are not substituted away by accepted repeats. Each case retains its source revision, actual application image, verified generator provenance, service configuration, resource envelope and observations. Verification logs, diagnostic wrappers and observer sources are listed in the [evidence package](results/burst-local-v1/README.md); generator source, patch and license/rebuild material remain available in the repository.

## Reproduce

Use Python 3.12+, Git and Docker, clean committed source, and a new output directory for each cohort:

```bash
./scripts/k6.sh build
./scripts/k6.sh provenance
./scripts/dev.sh build
./scripts/dev.sh up
./scripts/dev.sh smoke
python3 benchmarks/run.py --suite acceptance --profile standard --vus 64 --generator-memory-mib 768 --output work/strict-64-new
python3 benchmarks/run.py --suite acceptance --profile burst --vus 256 --generator-memory-mib 1536 --output work/strict-256-new
./scripts/dev.sh down
python3 scripts/faults.py --output work/faults-new.json
./scripts/kind-smoke.sh --output work/kind-new.json
```

The formal defaults repeat each cohort three times. The runner verifies the local generator and executes its immutable image ID. Keep source, running application images and service profiles fixed until each command finishes. A failed gate exits nonzero and retains its raw export. AWS execution remains deferred at the owner's request.
