# Local throughput failure diagnosis

Source inspected: `3c1f5f659512b08f44d4f418136ad6326268e2dd`. This note analyzes the retained exports, logs, metrics, and corresponding gateway code, including the limits of what those observations establish.

## Observed failures

All rows requested 8,400 iterations/s, used fixed preallocated VUs, and retained the original acceptance gates: at least 8,000 successful completions/s overall and in every 10-second bucket, successful full-call P99 <=15 ms, and zero eligible failures. Drops are reported over the whole experiment, including warmup; they are not a zero-drop gate.

| Evidence | Fixed VUs | Warmup / measurement | Successful completions/s | Minimum complete 10s bucket, /s | Dropped iterations | Eligible HTTP 503 | Outcome |
|---|---:|---|---:|---:|---:|---:|---|
| `256vu/acceptance-8400-r1.json` | 256 | 30s / 300s | 8,398.7133 | 8,361.8 | 499 | 22 | Failed zero-failure gate; P99 1.0547 ms |
| `256vu/acceptance-8400-r2.json` | 256 | Interrupted at 68.6845s total | Not a completed measurement | Not applicable | 56 observed | 26 observed | Interrupted, not a formal result; retain raw evidence |
| `64vu/acceptance-8400-r1.json` | 64 | 30s / 60s | 8,288.2 | 7,735.5 | 6,710 | 75 | Failed bucket and zero-failure gates; P99 1.4805 ms |
| `diagnostic/diagnostic-64.json` | 64 | 30s / 100s | 8,394.79 | 8,371.2 | 683 | 71 | Failed zero-failure gate; P99 0.9566 ms |

The interrupted second 256-VU export contains 30 configured buckets, including unelapsed zero buckets, and five started requests without reconciled outcomes. Dividing its successes by the configured 300s or treating its zero buckets as completed observations would be misleading. Its counts above are partial observations only.

## What the diagnostic narrows

`diagnostic/diagnostic-64.txt` records 71 eligible 503s, all with an empty body and full-call elapsed time 0.131167–1.121000 ms. Their starts occupy scenario time 78,311.361286–78,327.964994 ms: one approximately 16.6 ms burst, not failures spread across the measurement.

`diagnostic/diagnostic-metrics.json` contains 1,280 samples, median interval 105.65 ms, maximum interval 122.31 ms. Across the capture:

- `gateway_service_rejected_total{service="orders"}` is exactly 247 throughout. The 5xx counter increases by 231 during warmup and 71 during the eligible burst, for a total increase of 302, matching the export's whole-experiment HTTP failure count. The recorded failures therefore did not take the admission-failure branch that increments this rejection counter (`ProxyHandler.java:91–97`). The original explanation that the service's 100-request admission limit caused these particular failures is unsupported.
- Configuration revision stays 6035 and healthy endpoint count stays two. `gateway_body_bytes` never exceeds 2,048 bytes; it is zero in the sample immediately after the 71-response counter increase. There is no observed configuration loss, health outage, or approach to the 64 MiB byte budget. These gauges are sampled, however, and do not capture each failing request's instantaneous state.
- Sampled pool active leases peak at 32, waiting at nine, and idle at 32; sampled gateway in-flight peaks at 33. These are aggregate application counters, not Netty's per-endpoint internal pending queue. Sampling at approximately 106 ms cannot exclude a queue filling during the 16.6 ms failure burst.

## Exact candidate code path and remaining ambiguity

`EndpointPool.java:107–117` constructs each Netty `FixedChannelPool` with 16 connections, 32 pending acquisitions, and a 100 ms FAIL acquire timeout. `ProxyHandler.java:228–234` translates `TimeoutException`, **any** `IllegalStateException`, and `CancellationException` from acquisition into the same empty HTTP 503. The current response contains no cause identifier.

In [Netty 4.2.19.Final `FixedChannelPool.acquire0` and `tooManyOutstanding`](https://github.com/netty/netty/blob/netty-4.2.19.Final/transport/src/main/java/io/netty/channel/pool/FixedChannelPool.java#L244-L287), an acquisition reaching an already full per-endpoint pending queue fails immediately with `IllegalStateException("Too many outstanding acquire operations")`. This is the best-fitting observed failure class: fast local rejection without service-admission counter growth. A 100 ms acquire timeout or the 2,000 ms gateway deadline does not fit the observed sub-1.2 ms full calls. Intentional demo-backend errors would retain the configured 1 KiB body; the logged responses are empty.

Queue-full is **not yet established as the actual cause**. Other local `IllegalStateException` paths include a closed/retired pool and the global connection-budget channel factory. Constant revision, stable health, and the small sampled pool footprint provide no supporting evidence for those alternatives, but no artifact captures the actual acquisition exception. The metrics also do not prove why an internal pending queue might fill. Uneven endpoint progress, delayed lease releases, and event-loop scheduling can differ from the client's current request count. Sixty-four VUs do not impose an independent 32-request cap on each endpoint, nor do they prove that previously completed requests' pool releases have finished.

Existing evidence cannot distinguish ordinary configured backpressure from a scheduling/accounting defect. The decisive missing observation is the acquisition failure's concrete exception type **and message**, correlated with its endpoint and per-endpoint pool state. A later instrumented experiment could obtain that evidence; this analysis does not request or perform another run.

## Interpretation limits

Reducing fixed VUs remains a disclosed workload-calibration experiment, not a demonstrated fix: both completed 64-VU attempts above failed. A future passing result must retain the exact VU count, drops, duration, and unchanged gates, and must not replace or conceal these failures. k6's [official arrival-rate allocation guidance](https://grafana.com/docs/k6/latest/using-k6/scenarios/concepts/arrival-rate-vu-allocation/) explains that VU allocation limits which scheduled iterations can start; insufficient VUs cause dropped iterations. Keeping the configured rate at 8,400/s does not by itself prove that all 8,400 requests/s reached the gateway.

The first 256-VU resource capture showed generator memory reaching 755/768 MiB while gateway CPU remained below its two-core limit. This supports a possible generator/resource-pressure hypothesis, but it does not attribute the observed 503s or the later 64-VU burst to GC. A short or lower-VU run alone cannot establish that causal link or demonstrate 300-second stability.

The subsequent 32-VU, 100-second calibration passed at 8,393.24 successful completions/s, P99 0.76623 ms, minimum ten-second bucket 8,370.1/s, zero failed/unaccounted eligible requests, and 993 whole-experiment drops. It is retained as diagnostic evidence, not the formal acceptance interval. Three fresh 300-second measurements subsequently passed at 32 fixed VUs; see the [formal report](../../benchmarks.md#strict-sustained-local-acceptance). No runtime code, durability setting, offered rate, payload requirement, failure gate, latency boundary, or bucket target was relaxed. This establishes the disclosed 32-VU workload; it does not establish zero failures under 64/256-VU bursts or prove the acquisition exception that caused the prior rejections.

The archived `diagnostic/diagnostic.js` is the unmodified script used from the repository's ignored `work/` directory. Copy it to `work/diagnostic.js` before replay so its relative import resolves. Diagnostic exports are distinct from the three formal acceptance records.
