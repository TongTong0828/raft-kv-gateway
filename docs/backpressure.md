# Diagnosing HTTP 503 under bounded gateway concurrency

All experiments below use local Docker. AWS work remains deferred at the owner's request.

## Finding

The instrumented failures identify two configured backpressure paths: `admission_limit` and `pool_queue_full`. The accepted request configuration has `orders.maxInflight=100`, two healthy backends, 16 concurrent upstream acquisitions per endpoint, and 32 pending acquisitions per endpoint. The gateway also has a global 256-exchange limit and a 512-connection budget. The pinned [Netty acquisition branch](https://github.com/netty/netty/blob/netty-4.2.19.Final/transport/src/main/java/io/netty/channel/pool/FixedChannelPool.java#L229-L269) rejects a new acquisition when the selected endpoint already has its active slots and pending queue occupied. Thus at 64 VUs, one endpoint can be full at 16 active + 32 pending while the other has fewer outstanding operations; summing both endpoint capacities is not a per-request availability guarantee.

A healthy endpoint is eligible for routing; it does not necessarily have a free connection or queue position. Health-aware round robin is not a promise to reroute an acquisition whenever its selected endpoint's queue is full. A fixed arrival rate also does not impose a fixed instantaneous concurrency: scheduler stalls, delayed completions, and subsequent arrivals can fill a bounded queue even when average throughput is high.

No runtime pool-accounting defect has been demonstrated by these observations. The changes supply explicit rejection attribution and resource-lifetime diagnostics. They do not establish that changing the asynchronous release order would repair the observed high-VU failures.

## Instrumented baseline evidence

Both baselines used source and application image revision `88194de6ac5fd2c4a91097a288a7fbf759b4652b`, an offered rate of 8,400/s, 30 seconds of warmup, 300 seconds of measurement, fixed preallocated/max VUs, and 1 KiB backend responses. The generator was limited to four CPUs and 768 MiB. The unchanged gates require at least 8,000 successful in-window completions/s overall and in every 10-second completion bucket, successful full-call P99 <=15 ms, zero eligible request failures including drain, and reconciled outcomes. Whole-experiment dropped iterations are reported; zero drops is not one of those gates.

| Evidence | Fixed VUs | Eligible 503s and attribution | Successful in-window rate | Minimum 10s bucket | Successful full-call P99 | Whole-experiment drops | Status |
|---|---:|---|---:|---:|---:|---:|---|
| [64-VU baseline](results/backpressure-v1/baseline-64vu/summary.json) | 64 | **132 `pool_queue_full`**, zero other/unclassified rejection labels | 8,374.34/s | 7,967.8/s | 0.828292 ms | 10,239 | Failed zero-failure and bucket gates |
| [256-VU baseline](results/backpressure-v1/baseline-256vu/summary.json) | 256 | Raw counts: **7,132 = 5,444 `admission_limit` + 1,688 `pool_queue_full`**; zero other/unclassified labels | Not accepted | Not accepted | Not accepted | 3,642 raw drops | Invalid performance comparison: negative transport-latency minimum |

For 64 VUs, 2,512,436 eligible starts reconcile as 2,512,302 in-window successes + 132 failures + two late successes. The cohort's 132 HTTP 503s exactly equal its 132 `pool_queue_full` response-header classifications. Those failures cannot be attributed to service admission: the cohort's `admission_limit` count is zero.

For 256 VUs, the raw response-header counters provide the diagnostic split above. However, `window_transport_ms.min=-0.036264 ms`, so the fail-closed summarizer rejects the export with `invalid window_transport_ms.min`. These raw failure counts remain observations, not an accepted throughput/latency comparison or a passing result. They must not be repaired by silently dropping the negative sample or selectively publishing other latency values from that export.

The main evidence files are each directory's `environment.json`, `acceptance-8400-r1.json`, `summary.json`, `gateway-after.prom`, and resource-statistics export. The process counters in `gateway-after.prom` have a different scope from response-header cohort counters. For example, the 64-VU process counter is 3,101 queue-full events while its eligible cohort contains 132; warmup and earlier process activity are not excluded from process counters. The later 256-VU process counters are cumulative and are likewise not the eligible cohort totals. Use request-cohort classifications for exact cohort attribution, and a documented before/after interval when interpreting process-counter deltas.

The bounded history includes the exact Netty failure `java.lang.IllegalStateException: Too many outstanding acquire operations`. Sampled states include `acquired=16, borrowed=16, waiting=32, releasing=0, retired=false`, consistent with a selected endpoint reaching its configured 16-plus-32 capacity. Some later samples also contain acquisitions in handoff or releases in progress. These samples support the rejection classification, but do not by themselves reconstruct all outstanding exchanges or explain which scheduling event filled the queue.

Historical runs without rejection attribution remain unclassified. Similar status codes, timings, or configurations do not retrospectively establish that their failures took these same branches.

## Response-header and metric contract

Gateway-generated business HTTP 503 responses carry `X-Gateway-Rejection` with one of these finite reason labels:

| Reason | Meaning |
|---|---|
| `no_configuration` | No route snapshot has been installed. |
| `admission_limit` | `ResourceBudget.admit(service)` rejected the exchange. The label combines global/service admission limits and unavailable service-admission state; it does not distinguish them by itself. |
| `body_budget` | Reserving buffered request/response bytes exceeded the global byte budget. |
| `no_healthy_endpoint` | No configured endpoint was healthy at routing time. |
| `pool_unavailable` | The selected service/endpoint had no registered pool. |
| `pool_queue_full` | The pinned Netty pool reported `Too many outstanding acquire operations`. |
| `pool_timeout` | Pending acquisition exceeded its configured timeout, currently 100 ms. |
| `pool_retired` | The endpoint wrapper or underlying pool was retired/closed. |
| `pool_cancelled` | Acquisition completed with cancellation. |
| `connection_budget` | Creating a physical application upstream connection exhausted the global budget. |
| `pool_acquire_error` | A local acquisition failure did not match a more specific reason. |

The gateway strips `X-Gateway-Rejection` while copying incoming and upstream headers, then writes its own reason when constructing a local 503. A backend's HTTP 503 remains a backend response and does not gain a fabricated gateway reason. The benchmark records an unclassified label when a cohort 503 lacks a recognized reason. This contract concerns the business proxy port; admin readiness responses are separate.

`gateway_rejections_total{reason="..."}` counts reasoned local rejection attempts over the gateway process lifetime. It is incremented when the response is constructed, not when the client confirms receipt, and is not restricted to warmup or measurement. `gateway_service_rejected_total{service="..."}` covers admission rejection for that service, not every cause of 503. Those service series can be removed when configuration removes the service. Process reason counters, response-class counters, acquisition failures, and received client responses are related observations with different boundaries; they are not interchangeable totals under cancellation, failed writes, or configuration changes.

Observed pool acquisition failure paths also feed a bounded diagnostic history: at most **64 most recent entries**, each truncated to **2,048 characters**, with CR/LF removed. Entries appear as `# pool_failure ...` comments in the metrics response, not Prometheus time series. They include endpoint, exception class/message, timestamp, acquired/borrowed/waiting/releasing counts, limit, retired flag, and elapsed acquisition time. Warning logs are limited to the first four samples of each reason per process. The ring overwrites older entries; neither ring nor warning logs are a complete error history. An acquisition can fail after its exchange has already ended, so an acquisition-history entry need not correspond to a newly emitted HTTP 503. One coverage limit remains: a successful native acquisition rejected because the wrapper concurrently retires is categorized for the HTTP response, but that race does not append a failure-history sample.

### Resource gauges and their limits

The current code exports:

- `gateway_pool_active`: wrapper leases counted as borrowed in **currently registered pools**.
- `gateway_pool_waiting`: wrapper acquisition futures awaiting their completion listener in currently registered pools. It includes connection/health-check and promise handoff stages; it is **not Netty's native pending-queue length**.
- `gateway_pool_acquired_slots`: Netty `acquiredChannelCount()` summed over currently registered pools. Claimed slots can include connecting or handoff work, not just requests executing on a backend.
- `gateway_pool_releasing`: accepted lease-release calls whose pool-release futures have not completed, summed over currently registered pools. It overlaps other lifecycle stages and must not simply be added to `active` as a disjoint population.
- `gateway_upstream_connections`: the global budget of physical application upstream channels, including channels draining from retired pools. It excludes downstream clients and separate health probes.
- `gateway_pool_idle`: a clamped approximation, `max(0, global physical application connections - registered borrowed leases)`. It is not a native inventory of immediately reusable idle channels. Retired/draining channels, connection setup, and asynchronous handoff can affect it.

Retiring/removing a pool removes it from registry-derived gauges even while its old leases or channels drain. Thus registered-pool gauges alone cannot prove that every retired pool is empty. The gauges are sampled and are not a single atomic snapshot across fields or pools. `waiting` can decrease before a cancelled native pending task is removed, so low wrapper gauges do not disprove a brief native queue-full condition. The baseline image predates the newest acquired-slot/releasing gauge additions; absent baseline series must not be treated as zero.

## Asynchronous release characterization is not a bug verdict

The throwaway [release-order probe and runner](results/backpressure-v1/verification/) use the real `EndpointPool`, Netty, and `BackendFixture`, with one event loop and a one-connection pool. In a single event-loop task, the probe invoked `lease.release(true)` and then issued 33 new acquisitions. Release invocation left `nativeAcquired=1, borrowed=1, releasing=1`. The 33rd acquisition failed in 43.666 microseconds with the exact queue-full exception, while 32 acquisitions were pending. Cleanup subsequently reduced acquired, native pending, borrowed, waiting, releasing, and physical connections to zero; all wrapper futures completed, and no backend business request was sent. See the [unmodified probe output](results/backpressure-v1/verification/release-order-probe.txt).

This demonstrates that invoking `Lease.release(void)` is not a completion barrier. The wrapper schedules channel cleanup and Netty completes its slot bookkeeping asynchronously. The present contract permits that resource lifetime. It does **not** prove a leak, double release, or that deferred releases caused the measured high-VU failures. Executing release inline when already on the channel event loop would be a scheduling optimization unless stronger evidence demonstrates a violated contract or invariant; it must not be presented as a proven root-cause fix.

Evidence sufficient to establish an implementation error would include a request/lease that can never release its slot, persistent nonzero counters after quiescence, double ownership/release, counter underflow/overflow, response cross-wiring, or violation of a documented release-completion guarantee. Rejection at the declared active/pending limit, by itself, is expected bounded backpressure. Unacceptable rejection rates for a desired workload can justify explicit capacity/routing changes, but that is a workload/capacity decision rather than proof that the pool implementation is incorrect.

The added active-pool regression check fills the queue, observes the exact overflow reason/state, cancels waiting acquisitions, releases the held lease, and waits for acquired/borrowed/waiting/releasing to become zero **without closing the pool**. It then successfully borrows and returns a real lease again. After the measurements, a full-suite replay exposed a race in the new test's observer: its helper read independent counters once to leave a loop and again to assert, and the two observations could straddle asynchronous callbacks. The failure occurred after 21 ms, not the four-second timeout. The test now samples acquired slots, **native pending tasks**, borrowed leases, wrapper waiters and releases together in its sole event loop, and returns from that one consistent zero snapshot. Native pending is read using test-only reflection against the pinned Netty version. All original recovery/reuse assertions remain.

That test-only correction is commit `beeece6200cf9c1a7de650651b2cdff2a92736df`. The corrected case passed **50 consecutive repetitions**, then the full `./scripts/mvn.sh verify` passed **53 Java tests, zero failures/errors/skips**. The [red log, repeated run, final JUnit XML and review addendum](results/backpressure-v1/verification/snapshot-fix/) are retained. The teardown also waits for asynchronous physical closure before shutting down its event loop. Neither test correction changes production pool accounting or measured behavior.

Python accounting/source/image tests (20), Node measurement tests (13), distribution audit, image build and real HTTP smoke also passed. A fresh independent review found no Critical or Important code issue; its minor retirement-history coverage gap is documented above. The bounded test correction received a follow-up review with no findings.

## Native transport-timing anomaly

The rejected 256-VU baseline contains an actual negative native transport minimum. Our benchmark copies `response.timings.duration` directly; it does not derive that value from the full-call clock. Pinned [k6 tracer timestamps](https://github.com/grafana/k6/blob/v2.3.0/lib/netext/httpext/tracer.go#L175-L176) use `time.Now().UnixNano()`, and its [duration calculation](https://github.com/grafana/k6/blob/v2.3.0/lib/netext/httpext/tracer.go#L348-L372) reconstructs times without a monotonic component. [Go's documented subtraction rules](https://github.com/golang/go/blob/go1.27.1/src/time/time.go#L48-L52) then use wall time. This establishes a measurement limitation, not the exact mechanism of this particular negative sample. A [similar upstream Docker for Mac report](https://github.com/grafana/k6/issues/1872) closed without a confirmed fix. We neither clamp the sample nor claim an unverified clock/callback root cause. The raw export and invalid summary remain public; its 503s also independently fail the zero-failure gate.


## Final-source reruns

Source/image revision: `afde91c06369298398aae8f1aa6db1d396bbb242`. The tree stayed clean and source/image identities were verified before and after every run. The sequential cases were 64, 256, then 32 fixed VUs, with one repeat each. The first 32-VU export had no request failures but a negative native transport minimum (-0.51343 ms), so it was rejected and retained. One additional identical 32-VU repeat followed; it is reported separately, not substituted for the invalid record. All retained the baseline's 8,400 offered/s, 30-second warmup, 300-second measurement, 10-second buckets, 1 KiB body, five-second drain and resource limits. Admission, pool bounds, timeout, routing algorithm, release order and durability were unchanged. A host observer fetched gateway admin metrics about once per second; no Maven build, probe or other heavy project task ran during load. The resource sampler and observer have overhead, and all containers share the same Docker VM.

| Case | Fixed VUs | Successful requests/s | Full-call P99 (ms) | Lowest 10s successful rate | Eligible 503s | Admission / pool queue full | Late successes | Whole-experiment drops | Strict gate |
|---|---:|---:|---:|---:|---:|---|---:|---:|---|
| final-64vu | 64 | 8,373.73 | 1.657409 | 8,272.30 | 44 | 0 / 44 | 2 | 9,609 | Fail |
| final-256vu | 256 | 8,380.09 | 1.831239 | 8,254.00 | 4,011 | 3,211 / 800 | 3 | 1,982 | Fail |
| final-32vu | 32 | Invalid export | Invalid export | Invalid export | 0 raw | 0 / 0 raw | See raw | See raw | Invalid/fail |
| final-32vu-retry | 32 | Invalid export | Invalid export | Invalid export | 0 raw | 0 / 0 raw | See raw | See raw | Invalid/fail |

Both final-source 32-VU runs had **zero eligible request failures and zero unaccounted outcomes**, but native transport minima were **-0.51343 ms** and **-0.001181 ms**. Both exports remain invalid under the unchanged validation contract. They do not add a new accepted 32-VU performance pass. The earlier three accepted 32-VU runs remain historical evidence at source `3c1f5f659512b08f44d4f418136ad6326268e2dd`; no historical record was replaced.

Every eligible failure in these four final-source runs is an attributed HTTP 503; no eligible outcome is unaccounted for. No timeout, retired-pool, cancelled-pool, connection-budget, unknown acquisition or unclassified 503 reason occurred. Failed high-VU cases remain failed under the unchanged zero-failure gate, regardless of favorable average throughput or P99. A successful replay is an observation for that workload, not a guarantee against future scheduler bursts.

After **each** run, inflight requests, held body bytes, registered borrowed leases, wrapper waiting, native acquired slots and releases in progress were all zero. All six application containers were running at the expected revision, with zero restarts and no OOM flags. The active-pool test separately verifies cancel/drain/reuse; post-load gauges are sampled observations rather than an exhaustive proof of every lifecycle interleaving.

See [the evidence index and reproduction commands](results/backpressure-v1/README.md), [machine-readable diagnosis](results/backpressure-v1/diagnosis.json), raw exports, per-case process-counter boundaries and post-load states. Existing historical failed/invalid results are retained. No concurrency algorithm repair was justified by this evidence; the implemented correction is explicit attribution and bounded diagnostics, with regressions for header spoofing, accounting and recovery. AWS was not run.
