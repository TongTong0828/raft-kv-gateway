# Backpressure diagnosis evidence — 2026-10-19

Read [the English root-cause report](../../backpressure.md) first. These are local shared-Docker measurements, not AWS evidence. Failed and invalid cases are intentionally retained.

- `baseline-64vu`, `baseline-256vu`: clean instrumented source/image `88194de6ac5fd2c4a91097a288a7fbf759b4652b`; the 256-VU comparison is invalid because its native transport minimum is negative. Raw status/reason counts are diagnostic observations only.
- `final-64vu`, `final-256vu`, `final-32vu`, `final-32vu-retry`: clean source/image `afde91c06369298398aae8f1aa6db1d396bbb242`; one repeat per directory with native occupancy/release gauges. The first 32-VU export is invalid because native transport timing has a negative minimum; the identical retry also has a negative minimum (-0.001181 ms) and is retained separately. Both had zero request failures but neither is a new accepted performance pass. Each contains raw k6 JSON, derived summary, environment/image metadata, resource samples, k6 output, metric boundaries and post-load state.
- `diagnosis.json`: derived cross-case attribution. Raw-count fields remain explicitly named as raw when performance validation fails. No failed case is relabeled as passing overload acceptance.
- `verification/snapshot-fix`: test-only correction `beeece6` for the new drain observer sampling race, with the original red log, 50 repeated passes, final 53-test JUnit XML/full verify log and follow-up review. Production and benchmark code remain identical to measured `afde91c`.
- `verification`: original local Java JUnit XML and verify log (53 tests), Python/Node logs (20/13 tests), distribution audit, smoke, independent code review and release-order characterization. The probe demonstrates asynchronous release semantics; it is not proof of a runtime defect.

`pool-events.jsonl.gz` is a lossless gzip of the host observer's complete JSONL output; its decompressed SHA-256 is in `pool-events.sha256`. It records gateway process counters and a bounded recent failure ring, not individual client requests. Process counters include warmup and prior process activity. A separate observer fetched admin metrics about once per second; no heavy project workload ran concurrently. Baseline native acquired-slot/releasing gauges were absent in source88194de; absence is not zero.

Reproduce from a clean committed checkout and freshly rebuilt/running image:

```bash
./scripts/dev.sh build
./scripts/dev.sh up
./scripts/dev.sh smoke
python3 benchmarks/run.py --suite acceptance --vus 64 --repeats 1 --output work/replay-64
python3 benchmarks/run.py --suite acceptance --vus 256 --repeats 1 --output work/replay-256
python3 benchmarks/run.py --suite acceptance --vus 32 --repeats 1 --output work/replay-32
```

The first two commands may exit nonzero because an eligible 503 fails strict acceptance. Keep the raw result; do not raise limits or discard failures to obtain a pass. Each output directory must be new/empty. These commands do not automatically start the extra observer: its exact captured source is `verification/watch-pool.py.txt`. To reproduce that optional diagnostic sampling, copy it to ignored `work/watch-pool.py`, invoke it with a new ignored JSONL path in a second terminal, and stop it by creating `<that-path>.stop`. Its output and timing overhead are part of this diagnostic experiment.

Verify the evidence from this directory:

```bash
shasum -a 256 -c SHA256SUMS
gzip -dc final-64vu/pool-events.jsonl.gz > /tmp/kv-pool-events.jsonl
# Compare the decompressed file with final-64vu/pool-events.sha256.
```

Historical exports remain byte-for-byte unchanged. New `*.k6.txt` files preserve the original k6 log bytes under a tracked extension. `SHA256SUMS` covers all files here except itself.
