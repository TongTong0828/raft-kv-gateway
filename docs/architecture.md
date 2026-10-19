# Architecture and consistency

## Control-plane state

Each of the three configured nodes owns a Ratis server, a deterministic `KvStore`, persistent Raft storage, and a state-machine snapshot directory. A majority durably replicates a mutation before the state machine applies it. The state machine serializes changes; no wall clock, DNS lookup, health result, or random value participates in replicated execution.

A key has a value and a modification revision. `expectedRevision=0` means create-if-absent; a positive expected revision must match the current value. Successful changes receive the Raft log index. Failed CAS operations do not change `stateRevision`. After delete/recreate, the new revision prevents an old client from overwriting the replacement. Limits are checked against a candidate copy before publication: keys 256 UTF-8 bytes, values 64 KiB, 1,024 records, and 4 MiB aggregate live KV data.

Reserved service records are serialized `ServiceSpec` messages. Key/ID agreement, endpoint syntax, unique route prefixes, and total service limits are validated together. Invalid changes leave the previous state intact.

Application reads are accepted by a ready leader and perform a local-target, unordered Ratis ReadIndex query with linearizable reads enabled and leader leases disabled. The unordered transport path avoids mixing independent ordered read/write sequence windows on one gRPC stream. Status RPCs are diagnostic and are not linearizable reads. Followers reject KV reads and mutations with leader information when known; clients discover and read-validate the leader before a mutation.

Snapshots contain entries, revisions, and the applied term/index. They are written to a temporary file with a checksum, durably published, and recovered before requests are accepted. Corruption fails closed. Ratis controls snapshot installation and log retention; application code never manually deletes committed log segments.

## Configuration publication

A `ConfigSnapshot` contains the group ID, state revision, and complete service set. A Watch must first pass a leader read barrier. Registration and the initial store snapshot are coordinated so a concurrent change cannot be missed. Each node allows 16 watchers, with one latest pending snapshot per watcher and transport-readiness-aware delivery. Slow subscribers can skip intermediate revisions.

A shared barrier check runs every second, with a two-second bound. Losing a valid leader/quorum fence terminates the old streams; a retained TCP connection is not proof of leadership. A gateway reconnects with bounded exponential backoff and jitter, then receives another complete snapshot.

The gateway installs configurations on one background thread. It validates the group and complete table, checks monotonically increasing revisions and subscription generations, and replaces an immutable route-table reference. New requests capture one table. Routes use the longest raw path segment prefix: `/orders` matches `/orders/42`, but not `/orders2`. Query strings and encoded path bytes are forwarded unchanged.

## HTTP request lifecycle

The downstream EventLoop owns each exchange's lifecycle. It admits the request after decoding headers and before allocating body storage. One request owns its global/service permit, held-byte reservations, deadline, pending acquisition, and upstream lease. Terminal paths are idempotent. Upstream callbacks transfer bounded decoded batches to the owner loop; upstream reads resume only after the previous batch has been handled.

Admitted requests waiting for upstream capacity enter a shared, bounded FIFO for their service. The dispatcher selects a locally healthy endpoint with an available connection slot when the request reaches the head of that FIFO. A busy endpoint therefore does not capture a request while another healthy endpoint has capacity. Service and global admission limits still apply while requests wait. Queue time consumes the original total request deadline; expiry returns 504 instead of starting a new queue timeout.

After reserving a slot, the gateway asynchronously acquires a `FixedChannelPool` connection, sends once, consumes the complete bounded response, and releases or retires the upstream channel. There is no automatic retry or second endpoint attempt after dispatch. Hop headers and `Connection`-named fields are stripped; Host and framing are rebuilt. Client identity forwarding headers are not trusted. HEAD semantics are maintained by the persistent upstream HTTP client codec.

The upstream lease is released before waiting for the downstream flush. The exchange then clears its reference to that lease, so a stale deadline cannot close a connection already reused by another request. The dispatcher holds its endpoint reservation through native acquisition, the borrowed exchange, and completion of Netty's asynchronous return. Calling release alone does not make that slot available to the next request. Incomplete upstream bodies, protocol faults, cancellation, or timeout retire the channel and still return the lease to the pool. Removed endpoints stop accepting new borrows while existing borrows drain to their deadlines.

The default service inflight limit is 100 and the process-wide inflight limit is 256. The explicit `examples/orders-burst.json` profile raises the orders service limit to 256; the global limit remains 256. Both orders examples retain 16 connections per endpoint. These admission limits include requests waiting for dispatch, not just requests currently using a connection.

Each endpoint pool retains a 100 ms native acquisition guard, 32 pending acquisitions, and a 250 ms connection timeout. Ordinary capacity waiting happens in the service FIFO under the original exchange deadline; the native acquisition guard remains a separate safeguard after dispatch. The exchange deadline can terminate either stage sooner. Idle connections are actively closed after 30 seconds, and connections older than five minutes retire after their current exchange. The physical-connection budget is released even when Netty forcibly closes a channel before registration.

`gateway_dispatch_queued` reports tickets waiting for dispatch across services. `gateway_dispatch_reserved` reports endpoint slots reserved through native acquisition and return, including retired pools still draining. These gauges describe dispatcher capacity; they are distinct from admitted inflight requests and native pool acquired/releasing gauges.

Independent health probes use fresh connections, a 250 ms timeout, and at most 32 concurrent probes globally. Probe and passive-failure callbacks carry endpoint generations. An old callback cannot affect a removed/recreated endpoint. Pool saturation, request-body stalls, downstream write stalls, and client cancellation do not count as backend failures; actual connection/protocol faults and upstream response timeouts do.

## Failure boundaries

With one voter unavailable, a majority can elect a leader and continue. A minority cannot serve strong KV reads or committed writes. An existing gateway can still forward using cached routes and active health checks when every control node is unavailable. This availability does not extend to new configurations or a gateway process that has lost its in-memory cache.

The design has no global transaction across gateways, and no claim of simultaneous configuration switching across processes. A timeout after submitting a mutation cannot determine whether it committed. Ratis transport retries do not create a general application-level exactly-once contract. Read the resulting state and revision to reconcile an unknown outcome.
