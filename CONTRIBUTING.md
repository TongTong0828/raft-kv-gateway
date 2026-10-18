# Contributing

Use a focused branch and explain the behavior being changed. Reproduce defects with a test before fixing them, and preserve the documented consistency and resource contracts.

```bash
./scripts/mvn.sh verify
python3 -m unittest discover -s benchmarks -p 'test_*.py'
./scripts/dev.sh build
./scripts/dev.sh up
./scripts/dev.sh smoke
./scripts/dev.sh down
```

For consensus, persistence, configuration-stream, or resource-lifecycle changes, also run `python3 scripts/faults.py --output work/fault-results.json`. For deployment changes, run `./scripts/kind-smoke.sh --output work/kind-results.json`. Both own isolated resources and clean them up. Never run destructive cleanup against another project's containers, networks, volumes, or Kubernetes context.

Java requires JDK 21 if building directly; the wrapper uses a pinned Maven/JDK container and a named dependency cache. It prevents overlapping Maven builds in the same checkout. Generated protobuf classes belong in `target/`, not Git. Keep dependency versions explicit and review updated license resources when changing them.

Performance claims need the measured commit, environment, offered load, all failures and dropped iterations, and repeated raw exports. Hardware-dependent throughput is not a CI gate.
