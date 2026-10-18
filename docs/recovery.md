# Failure verification

Run the isolated suite with Docker, Python 3.9+, and the built application image:

```bash
./scripts/dev.sh build
python3 scripts/faults.py --output work/fault-results.json
```

The driver chooses unused loopback ports and a unique Compose project. It restores detached networks and removes only its own project's containers, networks, and volumes in cleanup. The regular development stack is independent, and `dev.sh down` keeps its persistent volumes.

The assertions cover:

1. Real forwarding to the fixed-size backends and a route update during traffic.
2. A bounded concurrent CAS/read history checked against a single-key register model, including an unknown write outcome. Separately, the Java suite commits a write while dropping its RPC reply, then reconciles the actual committed value and revision.
3. Cached-route forwarding after all three controls stop.
4. A Raft-only leader partition, preserving its application/management connection. A preexisting Watch must terminate, direct old-leader strong reads must fail, and the other two voters must commit a witness value.
5. A follower stopped while the majority writes enough log data to roll the 8 MiB segment. The surviving nodes snapshot and purge old segments. The lagger must show an increased `snapshotInstalls` count and catch up after restart. Merely seeing data again is insufficient: ordinary log replay would also do that.
6. All control processes restarting with their volumes, retaining the witness value and modification revision.
7. Partial request bodies held across 80 services, global body-budget rejection, no gateway OOM, and zero inflight/body counters after client cleanup. The runner observes server-held bytes for up to five seconds and requires actual HTTP 503 responses. A completed host socket write alone does not prove that Netty has consumed the bytes. Send failures are recorded separately.

The [published run](results/faults-v1.json) records actual roles, revisions, indexes, callback timing, and memory observations. `snapshotInstalls` is a process counter, so the test records the baseline before selecting a lagger. A `logStartIndex` of `-1` can occur immediately after an installed snapshot, before new log entries arrive.

The bounded history checker explores legal orderings consistent with call intervals. It is a focused single-key check with a 24-operation limit, not an exhaustive proof of the entire distributed system. Network partitions and process restarts are separate concrete experiments. The tests do not simulate disk-controller lies, permanent volume loss, Byzantine behavior, dynamic membership, or production cloud failure domains.

Kubernetes validation is described in the [deployment guide](../deploy/kubernetes/README.md), with [kind evidence](results/kind-v1.json). The EKS overlay is only render validated.
