### Strengths

- Rejection categories are fixed and consistently attached to gateway-generated 503 responses. Reserved header filtering prevents clients or upstreams from spoofing attribution.
- Failure retention and logging are bounded. Native occupancy and release gauges clarify asynchronous pool state without changing admission limits or release ordering.
- The active-pool regression checks overflow, cancellation, complete recovery and reuse before closure.
- Benchmark categories follow the same eligible cohort as status and failure counters, including drain completions. Historical exports remain supported.
- Checked the suspected connection-budget wrapper issue: the custom factory’s original exception propagates through pinned Netty unchanged. No classification defect there. ([Netty source](https://raw.githubusercontent.com/netty/netty/netty-4.2.19.Final/transport/src/main/java/io/netty/bootstrap/AbstractBootstrap.java))

### Critical findings

None.

### Important findings

None.

### Minor findings

1. **Retirement race omits its diagnostic sample** — `gateway/src/main/java/dev/kvgateway/gateway/EndpointPool.java:212–214`

   When a native acquisition succeeds but the wrapper observes retirement before handing out the lease, it fails the wrapper with `endpoint retired` without calling `reportFailure`. The HTTP rejection remains correctly categorized, but this acquisition failure lacks the retained cause/state evidence emitted by the other failure paths.

   **Fix:** construct the retirement exception once, report it, then fail the wrapper with that same exception.

### Recommendations

Keep the bounded overload interpretation: the supplied measurements and recovery regression establish queue/admission saturation, without demonstrating a lease leak. Complete final benchmark and documentation verification before publishing their conclusions.

The observer callback precedes promise failure, so a deliberately throwing callback could leave that promise pending. I found no ordinary production callback failure path and do not treat this as a merge blocker.

### Behaviors set aside

- AWS deployment and infrastructure validation — explicitly excluded by the user.
- Final load results, English report and GitHub publication — forthcoming and outside the reviewed commit range.

### Assessment

**Ready to merge: Yes**, for the reviewed code range.

No blocking correctness, liveness, security or compatibility defect was found. The minor retirement diagnostic omission does not change request outcomes. This was a read-only review; I did not rerun builds or heavy tests.
