# Release verification

Verification records retain their own source revisions. Historical runtime (`84079ae`), 32-VU sustained (`3c1f5f6`) and 503-diagnostic (`afde91c`, observer correction `beeece6`) results remain unchanged. The FIFO/timing implementation is measured at `2c2f4fe`; `e3ae41c` changes only generator memory configuration and tests, with identical application production source trees. See [current burst verification](burst-handling.md) for provenance, lifecycle regressions, complete repeat decisions and the retained failed 256-VU/768 MiB series. Results describe the recorded local environment; AWS execution remains deferred.

| Check | Result | Evidence |
|---|---|---|
| Java reactor | 66 tests, zero failures/errors/skips | `./scripts/mvn.sh verify`; CI publishes JUnit XML |
| Benchmark accounting/source/image attribution | 25 tests passed | `python3 -m unittest discover -s benchmarks -p 'test_*.py'` |
| Generator provenance/build validation | 10 tests passed | `python3 -m unittest discover -s benchmarks/k6 -p 'test_*.py'`; [generator source](../benchmarks/k6/README.md) |
| Patched generator httpext | 277 tests/subtests passed; focused race run 219 passed | [Retained timing validation](burst-handling.md#timing-repair) |
| Measurement clock/window | 13 Node tests passed, including wall-clock correction replay | `node --test benchmarks/test_*.mjs` |
| Fault-runner ownership | 4 real Docker fixture tests passed | `python3 -m unittest discover -s scripts -p 'test_*.py'` |
| Dependency notices | Audit passed for all five executable JARs | `python3 scripts/audit-release.py` |
| Clean-checkout quickstart | Build, two consecutive smoke runs, exact KV create/read passed | [Clean-checkout record](results/clean-checkout-v1.json) |
| Current Docker failures/recovery | Eight behavior checks plus cleanup passed at clean `e3ae41c` | [Fresh fault assertions](results/burst-local-v1/verification/burst-faults.json) |
| Current Kubernetes on kind | Three Raft voters on one Kubernetes node, three PVCs, pod replacement and gateway rollout passed at clean `e3ae41c` | [Fresh kind validation](results/burst-local-v1/verification/burst-kind.json) |
| EKS | Both overlays render; no AWS account or paid-resource authorization; no AWS resources provisioned | [Deployment prerequisites](../deploy/kubernetes/README.md) |
| Historical load tests | 30 runs, 10 s warmup + 30 s measurement each | [Method, repeat ranges and raw exports](benchmarks.md) |
| Historical 32-VU strict sustained load | Three passes, 30 s warmup + 300 s measurement each; 8,386.03–8,392.89 successful/s, full-call P99 0.64–0.88 ms, zero eligible failures | [Raw exports and all ten-second buckets](results/strict-local-v1/summary.json) |
| Current 64-VU standard-profile acceptance | Three strict passes; service 100, generator 768 MiB; 8,390.65–8,395.59 successful/s, full-call P99 0.82–1.05 ms | [Per-repeat results](burst-handling.md) |
| Current 256-VU burst-profile acceptance | Three strict passes; service 256, generator 1,536 MiB; 8,363.66–8,381.35 successful/s, full-call P99 3.81–6.71 ms | [All attempts and resource limits](burst-handling.md) |
| Historical 64/256-VU 503 diagnosis | Configured admission/queue saturation attributed; no pool-accounting defect reproduced; failed/invalid runs retained | [Report and fresh reruns](backpressure.md) |
| 32-VU diagnostic replays | Two runs with zero eligible request failures; native transport timing invalid, so neither adds a new accepted performance pass | [Invalid records retained](results/backpressure-v1/README.md) |
| Pool-drain observer regression | Test-only consistent event-loop snapshot; native pending included; 50 repetitions and final 53-test reactor passed | [Red/green evidence](results/backpressure-v1/verification/snapshot-fix/) |
| Current burst post-load cleanup | All eight request/body/dispatch/native-pool gauges zero after each selected run; six processes without OOM/restarts | [Complete evidence index](results/burst-local-v1/evidence.json) |
| Diagnostics post-load cleanup | Native acquired/borrowed/waiting/releasing, inflight and body bytes all zero after each rerun; six processes without OOM/restarts | [Per-case records](results/backpressure-v1/README.md) |
| Strict post-load cleanup | Zero held bytes/inflight; six current processes running without OOM/restarts | [Current process state](results/strict-local-v1/post-load.json) |
| Historical post-load cleanup | Zero inflight/body bytes; current processes have no OOM/restart flag | [Recorded process state](results/post-benchmark-v1.json) |

## Fault observations

The [fresh Docker suite](results/burst-local-v1/verification/burst-faults.json) and [kind suite](results/burst-local-v1/verification/burst-kind.json) passed at clean source `e3ae41c`. The owned temporary Compose project and kind cluster were removed afterward. Historical [Docker](results/faults-v1.json) and [kind](results/kind-v1.json) records remain available at their original revisions.

The partition test detached only the elected leader's Raft bridge. Its application gRPC endpoint still returned the same node identity, its strong read failed, and its existing configuration Watch closed with `UNAVAILABLE`. The other two voters elected a leader and committed a witness write.

The snapshot test stopped a follower at applied index 16. The majority wrote 1,200 values, snapshotted at index 2,416, and advanced its log start to 2,049. The returning follower installed a snapshot (`snapshotInstalls: 0 → 1`) and reached applied/snapshot index 2,416. A full restart retained the exact value and modification revision 2,417.

The fresh body-budget test held 66,588,613 bytes across 80 routes, observed two explicit rejection responses with zero send failures, and recorded no OOM. After client cleanup, held body bytes and inflight requests both returned to zero. This is an observed sample, not a proof against every possible resource failure.

One earlier acceptance attempt timed out on the host-published management port immediately after a Docker network detach. A smaller networking probe did not reproduce it. The application network now has explicit gateway priority, and partition acceptance additionally requires positive application gRPC reachability. The published run passed these stronger assertions; the original timeout's precise cause remains unconfirmed.

The first Linux CI run also exposed a test sampling race: the host had finished its socket writes while the gateway had recorded only 27 active uploads and about 15 MiB of held data. The body-pressure assertion now uses a bounded observation window, retains the same 32–64 MiB acceptance range, and requires actual 503 responses rather than crediting socket write failures. That historical test-only sampling correction did not alter its then-current runtime or benchmark implementation.

## Review and boundaries

A fresh whole-branch review found six release blockers, all reproduced and corrected with regression tests. See [review and fixes](review.md). That initial review has no deferred minor findings. The next review found four strict-measurement issues (clock independence, duplicate filenames, dirty source, and incomplete latency validation); all were reproduced and fixed with regressions before the formal runs. That measurement change left runtime Java code unchanged. The later independent review of the 503 diagnostics found no Critical or Important code issue; a minor retirement-race history coverage gap is retained and explicitly documented in the [diagnostic contract](backpressure.md#response-header-and-metric-contract).

Independent review of the new dispatcher reproduced a retirement-success ownership race. The wrapper now publishes the retirement failure only after its late native lease returns. That gated real-network RED/GREEN case is separate from historical 503-cause attribution.

The first follow-up Linux/amd64 CI run at `e8e060c` exposed a test synchronization race: the non-head retirement test treated the aggregate queued gauge as actual FIFO membership, although that gauge includes work still in transit to the coordinator. A gated replay reproduced the exact `no_healthy_endpoint` versus `endpoint retired` mismatch. The test now proves actual queued/pending state on the coordinator before endpoint removal. The health-recovery and head-expiry tests also establish their intended waiting state before the event they verify. The corrected retirement case passed 50 repetitions, and the two liveness cases passed 20 each; exact error/endpoint assertions remain. Final full reactor verification then passed 66 tests with zero failures, errors or skips. See the [original failed CI artifacts and correction evidence](results/burst-ci-v1/README.md). This correction changes only tests and documentation; all five application production source trees remain identical to the measured implementation.

The bounded single-key history checker is not an exhaustive consistency proof. The Kubernetes run uses a local, single-host kind cluster; the three-zone EKS configuration was rendered only. Benchmark percentiles are per-run observations on a shared Docker VM, not a production latency guarantee. Authentication, TLS, dynamic membership, sharding, and disaster recovery from permanent volume loss remain outside v1.
