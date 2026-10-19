# Release review and regressions

A fresh whole-branch review examined the implementation through commit `94f3c11`. It identified six release-blocking findings. The author reproduced each before correcting it; the final fixes were verified with tests rather than a second independent review.

| Finding | Regression and correction |
|---|---|
| An application timeout could complete a Ratis-owned future and lose its request-slot release callback | A real three-node test submitted 512 short-deadline writes, exceeding all 128 client slots, then required a normal write to complete. It timed out before the fix. Application deadlines now apply to a copied future. |
| Rejecting an occupied fault project still ran destructive teardown | Real Docker fixtures proved the old runner deleted an existing test volume. The runner now checks running/stopped containers, networks, preserved volumes, and name collisions; claims an atomic ownership lease; and tears down only after successful ownership. A second run cannot claim the same lease. |
| Asynchronous probes repeatedly selected the first 32 endpoints | A delayed-future test with 70 endpoints reached only 32 before the fix. A bounded rotating scheduler now reaches every endpoint. |
| An old permit could delete a reinstated service counter | A deterministic concurrent test held the map operation, reinstated the service, then released the retired permit. Retirement now atomically rechecks counter identity, active count, and removal state. |
| Unknown writes were ordered as completed at the caller's timeout | Delayed-commit and later-CAS histories failed before the fix. Unknown writes now remain pending after timeout, while their invocation boundary is respected; candidate revisions include later CAS observations. |
| A rebuilt local image tag could conceal stale running containers | Metadata fixtures failed before the fix. Every measurement now inspects all six actual running containers and checks their image IDs/revision labels, including after backend recreation. |

The container smoke driver also needed an explicit HTTP/1.1 JDK client; its default HTTP/2 upgrade was correctly rejected by the gateway. The real smoke command reproduced that failure and passed after correction.

The reviewer raised no deferred minor findings. Live EKS behavior, production security features, and dynamic membership were outside the approved v1 scope. Throughput measurements and public GitHub/CI completion occurred after the source review and are supported by their own recorded evidence.


## Strict measurement review

A separate fresh whole-branch review of `9d9fb24..8d9c875` identified four Important findings before formal measurement. All were fixed in `3c1f5f6` after targeted tests failed, then passed; no second independent review was requested. The final regression suites pass 19 Python tests and 11 Node tests.

- Wall-clock correction could move both apparent clocks and count a drain response inside the window. Actual-iteration replay tests cover backward/forward wall corrections and reversed progress; the pinned executor's monotonic progress now determines both boundaries and call latency.
- Duplicate normalized rates could overwrite raw exports. CLI preflight rejects them before external tools run.
- Dirty benchmark source could run against a committed image label. Actual temporary Git repository tests cover tracked/untracked modifications and changed HEAD; strict runs require clean source before Docker and around each measurement.
- Published latency comparisons could be missing or non-finite. Every published full-call and transport statistic is now required and validated when successes exist.

The review scoped formal numeric results, English tables, checksums, remote publication and CI to the author's subsequent evidence verification. No minor findings were deferred. Higher-concurrency 503 diagnostics remain an explicit [measurement limitation](results/strict-calibration-v1/README.md); workload calibration is not presented as a runtime bug fix. AWS execution remains deferred by the owner.
