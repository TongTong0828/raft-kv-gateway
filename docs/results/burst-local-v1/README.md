# Local burst and timing evidence

Recorded on 2026-10-19–20 UTC on an Apple M4 Pro / shared Docker Desktop VM (12 virtual CPUs, about 8 GiB). AWS was not used. See [methodology, fixes and limitations](../../burst-handling.md).

`evidence.json` records per-trial provenance, actual service profiles, generator budgets, every timing-metric aggregate, resource observations, and complete three-repeat acceptance sets. Both selected sets pass; the earlier failed 256-VU trial remains failed. All 14 executed trials are retained below.

| Case | Decision | VUs / service limit | Generator MiB | Successful/s | Full-call P99 ms | Minimum 10 s bucket/s |
|---|---|---:|---:|---:|---:|---:|
| [calibration-256-burst](calibration-256-burst/summary.json) | PASS | 256 / 256 | 768 | 8,362.683 | 14.8773 | 8,298.6 |
| [calibration-256-standard](calibration-256-standard/summary.json) | FAIL | 256 / 100 | 768 | 8,381.350 | 1.7535 | 8,304.0 |
| [calibration-64-standard](calibration-64-standard/summary.json) | PASS | 64 / 100 | 768 | 8,366.783 | 1.2315 | 8,304.2 |
| [diagnostic-256-gc-profile](diagnostic-256-gc-profile/summary.json) | PASS | 256 / 256 | 768 | 8,392.843 | 1.5644 | 8,324.9 |
| [final-256-r1](final-256-r1/summary.json) | PASS | 256 / 256 | 1536 | 8,363.657 | 6.1510 | 8,115.3 |
| [final-256-r2](final-256-r2/summary.json) | PASS | 256 / 256 | 1536 | 8,379.810 | 6.7095 | 8,202.4 |
| [final-256-r3](final-256-r3/summary.json) | PASS | 256 / 256 | 1536 | 8,381.347 | 3.8084 | 8,239.2 |
| [formal-256-r1](formal-256-r1/summary.json) | PASS | 256 / 256 | 768 | 8,398.737 | 1.0629 | 8,374.8 |
| [formal-256-r2](formal-256-r2/summary.json) | PASS | 256 / 256 | 768 | 8,399.843 | 0.9525 | 8,393.8 |
| [formal-256-r3](formal-256-r3/summary.json) | FAIL | 256 / 256 | 768 | 8,367.090 | 1.5188 | 7,576.6 |
| [formal-64-r1](formal-64-r1/summary.json) | PASS | 64 / 100 | 768 | 8,392.773 | 1.0547 | 8,358.3 |
| [formal-64-r2](formal-64-r2/summary.json) | PASS | 64 / 100 | 768 | 8,390.653 | 0.9349 | 8,324.7 |
| [formal-64-r3](formal-64-r3/summary.json) | PASS | 64 / 100 | 768 | 8,395.590 | 0.8210 | 8,369.0 |
| [negative-p99-headroom](negative-p99-headroom/summary.json) | FAIL | 64 / 256 | 1536 | 999.900 | 1.0502 | 999.8 |

The three calibrations use 60-second measurements. Formal trials and the marked diagnostic use 300 seconds. The P99 negative control deliberately uses a 0.001 ms limit and a ten-second measurement; its failure is expected. The diagnostic has explicit profiling/GC overrides and is excluded from the accepted sets. The original 256-VU/768 MiB three-repeat series is PASS/PASS/FAIL. Its third run missed a bucket despite zero request failures. Only `final-256-r1` through `r3` form the separately budgeted 1,536 MiB 256-VU set.

Each directory retains the raw k6 export, strict summary, environment/configuration/provenance, Docker resource samples, rejection counters and post-load observations. `pool-events.jsonl.gz` and larger JSONL streams are lossless gzip encodings; their companion `.sha256` files hash the decompressed original bytes. Native output files ending in `.log` were renamed `.k6.txt` so Git does not ignore them. `SHA256SUMS` covers every published file except itself.

The `verification/` directory contains Java JUnit XML, local Python/Node/ownership/license audits, RED/GREEN regression evidence, pinned generator tests/provenance, profiling and image-build logs, fault/kind records, and the readable observer/publication helpers. Earlier failed test attempts remain named separately from final passing verification. Go `green.jsonl` includes the initial external-DNS failure under network isolation; `httpext-tests.jsonl` is the full passing network-enabled run. The focused race log is separate.

- [Evidence index](evidence.json)
- [Docker fault suite](verification/burst-faults.json)
- [Local kind suite](verification/burst-kind.json)
- [Deployed image witness and cleanup](verification/burst-deployment-image-witness.json)
- [kind immutable image mapping](verification/kind-image-proof/mapping.json)
- [Application production-tree comparison](verification/headroom-runtime-source-trees.json)
- [Final Java verification](verification/gateway-burst-final-verify.txt)
- [Pinned generator tests](verification/k6/httpext-tests.jsonl)
- [Checksums](SHA256SUMS)
