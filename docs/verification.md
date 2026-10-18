# Release verification

The release includes executable verification scripts and recorded observations. Runtime measurements use source commit `84079aefad7ab765ffc8efe81ac56397f662b16b`; subsequent release commits add documentation and evidence. Each exported run identifies its own source commit. The results describe this implementation on the recorded local environment.

| Check | Result | Evidence |
|---|---|---|
| Java reactor | 47 tests, zero failures/errors/skips | `./scripts/mvn.sh verify`; CI publishes JUnit XML |
| Benchmark accounting/image attribution | 5 tests passed | `python3 -m unittest discover -s benchmarks -p 'test_*.py'` |
| Fault-runner ownership | 4 real Docker fixture tests passed | `python3 -m unittest discover -s scripts -p 'test_*.py'` |
| Dependency notices | Audit passed for all five executable JARs | `python3 scripts/audit-release.py` |
| Clean-checkout quickstart | Build, two consecutive smoke runs, exact KV create/read passed | [Clean-checkout record](results/clean-checkout-v1.json) |
| Docker failures/recovery | All eight scenarios passed | [Recorded assertions](results/faults-v1.json) |
| Kubernetes on kind | Three voters, persisted KV/PVCs, gateway rollout passed | [Recorded validation](results/kind-v1.json) |
| EKS | Both overlays render; no AWS resources provisioned | [Deployment prerequisites](../deploy/kubernetes/README.md) |
| Actual load tests | 30 runs, 10 s warmup + 30 s measurement each | [Method, repeat ranges and raw exports](benchmarks.md) |
| Post-load cleanup | Zero inflight/body bytes; current processes have no OOM/restart flag | [Recorded process state](results/post-benchmark-v1.json) |

## Fault observations

The partition test detached only the elected leader's Raft bridge. Its application gRPC endpoint still returned the same node identity, its strong read failed, and its existing configuration Watch closed with `UNAVAILABLE`. The other two voters elected a leader and committed a witness write.

The snapshot test stopped a follower at applied index 16. The majority wrote 1,200 values, snapshotted at index 2,416, and advanced its log start to 2,049. The returning follower installed a snapshot (`snapshotInstalls: 0 → 1`) and reached applied/snapshot index 2,416. A full restart retained the exact value and modification revision 2,417.

The body-budget test held 66,600,000 bytes across 80 routes, rejected six further clients, and observed no OOM. After client cleanup, held body bytes and inflight requests both returned to zero. This is an observed sample, not a proof against every possible resource failure.

One earlier acceptance attempt timed out on the host-published management port immediately after a Docker network detach. A smaller networking probe did not reproduce it. The application network now has explicit gateway priority, and partition acceptance additionally requires positive application gRPC reachability. The published run passed these stronger assertions; the original timeout's precise cause remains unconfirmed.

The first Linux CI run also exposed a test sampling race: the host had finished its socket writes while the gateway had recorded only 27 active uploads and about 15 MiB of held data. The body-pressure assertion now uses a bounded observation window, retains the same 32–64 MiB acceptance range, and requires actual 503 responses rather than crediting socket write failures. Runtime gateway code and the measured benchmark implementation are unchanged.

## Review and boundaries

A fresh whole-branch review found six release blockers, all reproduced and corrected with regression tests. See [review and fixes](review.md). There are no deferred minor review findings.

The bounded single-key history checker is not an exhaustive consistency proof. The Kubernetes run uses a local, single-host kind cluster; the three-zone EKS configuration was rendered only. Benchmark percentiles are per-run observations on a shared Docker VM, not a production latency guarantee. Authentication, TLS, dynamic membership, sharding, and disaster recovery from permanent volume loss remain outside v1.
