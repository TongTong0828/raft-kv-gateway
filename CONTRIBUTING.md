# Contributing

Use a focused branch and explain the behavior being changed. Reproduce defects with a test before fixing them, and preserve the documented consistency and resource contracts.

```bash
./scripts/mvn.sh verify
python3 -m unittest discover -s benchmarks -p 'test_*.py'
python3 -m unittest discover -s benchmarks/k6 -p 'test_*.py'
node --test benchmarks/test_*.mjs
./scripts/dev.sh build
./scripts/dev.sh up
./scripts/dev.sh smoke
./scripts/dev.sh down
```

For consensus, persistence, configuration-stream, or resource-lifecycle changes, also run `python3 scripts/faults.py --output work/fault-results.json`. For deployment changes, run `./scripts/kind-smoke.sh --output work/kind-results.json`. Both own isolated resources and clean them up. Never run destructive cleanup against another project's containers, networks, volumes, or Kubernetes context.

Java requires JDK 21 if building directly; the wrapper uses a pinned Maven/JDK container and a named dependency cache. It prevents overlapping Maven builds in the same checkout. Generated protobuf classes belong in `target/`, not Git. Keep dependency versions explicit and review updated license resources when changing them.

The local benchmark generator requires Python 3.12+, Git and Docker. Build and verify it with `./scripts/k6.sh build` and `./scripts/k6.sh provenance`. The build uses the Docker server's native arm64 or amd64 platform. Preserve its source pin, patch and binary checksums, license, and rebuild instructions when changing it. The runner must execute the verified immutable image ID. Upstream k6 and its local Go patch are a separate GNU AGPLv3 program; original Java application source remains Apache-2.0. See [generator source and provenance](benchmarks/k6/README.md) and [distribution notices](distribution/README.md).

Preserve service FIFO ordering, healthy available-slot dispatch, and endpoint reservations through native return completion. Waiting requests retain admission permits and the original total deadline; expiry in the queue is 504. Do not add an upstream retry or release a reservation merely because asynchronous channel return has started. The 100 ms native acquisition guard and the default 16 connections per endpoint remain independent safeguards. A larger service admission profile must be explicit: `--profile burst` selects `examples/orders-burst.json` with service inflight 256; the default is 100 and the global limit stays 256. Use the dispatch queued/reserved gauges alongside native pool gauges when checking drain and recovery.

Document the workload profile and generator budget with every performance claim. The four-CPU generator defaults to 1,536 MiB; `--generator-memory-mib` accepts 256–4096 MiB. Reproducing the recorded 64-VU standard cohort requires an explicit 768 MiB budget; the 256-VU burst cohort uses 1,536 MiB. Retain failed/invalid trials and complete three-repeat series; a later pass does not replace a missed gate. See [burst verification and evidence boundaries](docs/burst-handling.md).

Performance claims need the measured commit, environment, offered load, all failures and dropped iterations, and repeated raw exports. Hardware-dependent throughput is not a CI gate.
