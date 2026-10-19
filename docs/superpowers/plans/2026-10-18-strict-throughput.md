# Strict Local Throughput Acceptance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Verify sustained local successful throughput with a strict completion window while respecting the owner's refusal to create paid AWS resources.
**Architecture:** A pure completion classifier feeds one fixed-VU k6 scenario covering warmup and measurement. A separate summary validator checks throughput, per-bucket continuity, latency, all cohort outcomes and measurement integrity. Reuse the existing container/image attribution and bounded runner; keep historical exports unchanged.
**Tech Stack:** k6 2.3.0, JavaScript modules, Python stdlib, Node's built-in test runner, Docker.
**Spec:** docs/acceptance-targets.md. Owner clarified there is no AWS account and no paid AWS resources are authorized; this plan implements only its local measurement stage.

## Global Constraints

- No AWS account setup, cloud resource creation or paid service usage.
- Default 30s warmup +300s measurement, three repeats, 8,400 offered/s, 256 fixed preallocated/max VUs, existing four-CPU/768MiB generator cap.
- Correct 1,024-byte `x` payload, known backend identity, no retry/redirect. HTTP timeout3s, drain5s.
- Eligible starts and successful completions both lie in [warmup,end). Late outcomes remain separate; all eligible started attempts must reconcile.
- Actual successes/300 >=8,000/s; all30 ten-second buckets >=80,000 successes; successful full-client-call P99 <=15ms; no failed or unaccounted eligible attempt.
- Record all experiment drops, including warmup, without treating offered load as throughput. No durability or application changes.
- Results must identify actual running images and a clean committed source; EKS remains pending.

## Review Focus

- A late successful completion must not inflate the fixed-window rate.
- Warmup straddles, exact boundaries and absent/zero buckets cannot fabricate sustained success.
- Malformed/nonfinite/missing counts or percentile values must fail closed.
- A request error or interruption during drain cannot vanish from cohort accounting.
- Real k6 clock/summary behavior must match the pure classifier and parser; raw exports survive a failed gate.

### Task 1: Completion classification and acceptance validation

**Files:** benchmarks/window.mjs, benchmarks/test_window.mjs, benchmarks/acceptance.py, benchmarks/test_acceptance.py.
**Interfaces:** `classifyCompletion(startMs,endMs,warmupMs,measurementMs,bucketMs)` returns `warmup|outside|late|window` plus bucket index for window; throws invalid clock/settings. `summarize_acceptance(data,rate,seconds,min_rps,p99_ms,bucket_seconds)` returns passed/reasons, successfulRps, bucket rates, cohort accounting, latency, totalExperimentDropped.

- [ ] Write literal boundary cases: 999→1001 warmup;1000→1001 first bucket;1999→2000 next bucket;2999→3000 late;3000→3001 outside; reversed clocks rejected. Python fixtures pin late successes excluded, failure/interruption visible, missing bucket/latency fail, nonfinite/negative/fractional counts fail, exact inclusive thresholds pass.
- [ ] Run `node --test benchmarks/test_window.mjs` and Python unittest; observe RED for missing classification/validator.
- [ ] Implement pure classification and fail-closed validation without defaulting required measurements to success.
- [ ] Run Node tests plus all Python benchmark tests; expected PASS. Commit.

### Task 2: Real k6 measurement and bounded runner

**Files:** benchmarks/acceptance.js; modify benchmarks/run.py and .github/workflows/ci.yml; add integration evidence under work.
**Interfaces:** acceptance.js exports raw named metrics consumed by Task1; `run.py --suite acceptance` defaults to the global constraints, supports short calibration flags, retains raw summary even when k6 exits99 for failed thresholds, records validator output and exits nonzero after collecting repeats if a gate fails.

- [ ] Run the new acceptance suite against its absent entrypoint/runner mode; expected RED. Use real short k6 calibration rather than source-text tests.
- [ ] Add one constant-arrival scenario with fixed VUs, shared clock and premeasurement warmup; record full call elapsed and transport time separately; check exact payload/backend. Seed zero counters to preserve missing buckets, require actual threshold counts, retain drain failures and account for interruption.
- [ ] Extend existing runner with validated acceptance settings and unchanged historical modes/image attribution. Add Node pure tests to CI.
- [ ] Commit, build clean image, up/smoke, then short acceptance calibration (1s warmup,2s window,1s buckets,1000offered/900minimum). Verify real clock/raw counts, outputs and expected failure fixture. Run all relevant tests; expected PASS. Commit any evidence-driven correction before formal measurement.

### Task 3: Three sustained runs, review and English evidence

**Files:** docs/results/strict-local-v1/, docs/benchmarks.md, README.md, docs/acceptance-targets.md, docs/verification.md.
**Interfaces:** consumes raw k6 files, actual-container metadata and acceptance parser. Publishes per-run outcome,10s bins,P99 and drops as local observations; records AWS refusal without implying EKS completion.

- [ ] Run three default acceptance repeats on the clean source image. Read each result; if a real gate fails, diagnose and fix instead of relabeling failure as success.
- [ ] Verify raw exports against parsed output and image identities; observe post-load zero inflight/body bytes and process state; preserve own development volumes when stopping.
- [ ] Run Java reactor once, all Python and Node tests and license audit. Obtain one fresh whole-branch review; fix Critical/Important findings with RED→GREEN evidence and no second review.
- [ ] Update English docs and checksum raw JSON. Publish authorized changes, verify final remote HEAD and CI, clean up only this managed worktree after integrating to main. All performance claims retain the local Docker environment and EKS pending status.
