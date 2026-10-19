# Commands and protocol

## Local lifecycle

`scripts/dev.sh build|up|seed|smoke|down|reset` operates on the current Compose project. `up` waits for the node management listeners; `smoke` seeds routing and verifies business HTTP. `down` preserves volumes. `reset` explicitly removes them. Use `COMPOSE_PROJECT_NAME` to select a separate project, and override loopback host ports to avoid collisions:

```bash
KV_HTTP_PORT=18080 KV_GATEWAY_ADMIN=19090 ./scripts/dev.sh up
```

Keep the same environment for subsequent commands. Control gRPC host ports default to 15051/15052/15053 (`KV_CP1_PORT` etc.); management ports default to 19101/19102/19103 (`KV_CP1_ADMIN` etc.). Gateway HTTP/admin defaults are 8080/9090. Raft port 6000 is not published. Container-to-container traffic uses the internal service names and ports, so host port overrides do not change service JSON.

## KV operations

```bash
./scripts/cli.sh kv put sample first --expected-revision 0
./scripts/cli.sh kv get sample
# Replace 7 with the returned entry.modRevision:
./scripts/cli.sh kv put sample second --expected-revision 7
./scripts/cli.sh kv delete sample --expected-revision 9
```

The example revision numbers are placeholders, not fixed values. KV output represents binary values as protobuf JSON base64. A revision conflict returns gRPC `ABORTED` and the CLI exits nonzero. Invalid input returns `INVALID_ARGUMENT`; unavailable leadership/quorum and deadlines also return nonzero. A submitted mutation may already have committed when the caller times out. Re-read and reconcile; do not blindly repeat it.

The wire schema is [control.proto](../api/src/main/proto/control.proto), package `kvgateway.v1`, Java package `dev.kvgateway.api`. RPCs are `Get`, `Put`, `Delete`, `GetConfig`, `WatchConfig`, and `GetStatus`. The CLI sends a mutation once after read-validated leader discovery.

## Routing configuration

```bash
./scripts/cli.sh service get orders
./scripts/cli.sh service put /workspace/examples/orders.json --expected-revision 1
./scripts/cli.sh service delete orders --expected-revision 12
./scripts/cli.sh watch
```

CLI tools mount the repository read-only at `/workspace`; a JSON path must be inside that mount. Read the current revision before an update or delete. `watch` prints full configurations until canceled; stream errors are written to stderr. The CLI is an interactive observer, not the automatic Watch reconnect implementation used by the gateway.

```json
{
  "serviceId": "orders",
  "pathPrefix": "/orders",
  "endpoints": ["backend-a:8081", "backend-b:8081"],
  "requestTimeoutMs": 2000,
  "maxInflight": 100,
  "maxConnectionsPerEndpoint": 16
}
```

Optional fields receive defaults only when omitted; explicit zero is invalid. IDs match `[a-z][a-z0-9-]{0,62}`. Endpoints are plain HTTP DNS/IPv4 `host:port`; IPv6 targets are outside v1. At most 128 services and 32 endpoints per service are accepted. Routes match complete raw path segments and never strip the configured prefix.

## HTTP behavior

Allowed methods: GET, HEAD, POST, PUT, PATCH, DELETE, OPTIONS. HTTP/1.1 sequential keep-alive and bounded chunked bodies are supported. Requests and responses are aggregated up to 1 MiB each; the initial line is limited to 4 KiB and headers to 8 KiB. Informational upstream responses, trailers, streaming, Upgrade/WebSocket, CONNECT, and HTTP/2 forwarding are outside v1. Pipelining closes the connection and cancels its current exchange. TE+CL and duplicate CL are rejected with 400. Oversized request bodies return 413. `Expect` returns 417 and closes.

| Status | Meaning |
|---|---|
| 404 | Valid configuration, no matching route |
| 503 | No initial configuration, no healthy endpoint, or a local admission/pool/body budget is exhausted |
| 502 | Upstream connection, malformed response, or response-size failure |
| 504 | The service's total request deadline expired |
| 501 | Unsupported proxy mode or method |

Gateway-generated business HTTP 503 responses include `X-Gateway-Rejection` with a fixed reason such as `admission_limit`, `pool_queue_full` or `pool_timeout`. The header is reserved and stripped from inbound/upstream copies to prevent spoofing. A forwarded backend 503 has no fabricated gateway reason. See [all reason labels, counter scopes and diagnosis](backpressure.md#response-header-and-metric-contract).

No automatic upstream retry occurs, including POST. A total deadline covers request body, acquisition, connection, response, and downstream write. If a response has already started when the deadline expires, the connection closes.

## Management

```bash
curl http://localhost:9090/status
curl http://localhost:9090/metrics
curl http://localhost:19101/status
curl -X POST http://localhost:19101/snapshot
```

Both processes provide `/live`, `/ready`, and `/metrics`. Control status includes role, leader, applied term/index, snapshot index, install count, and log start index. Gateway status distinguishes configured state from control connectivity. Snapshot requests are internal operational tools, not a substitute for a tested backup procedure.

Metrics expose bounded labels, request latency buckets, outcomes, inflight/body/connection budgets, configured-pool active/idle/waiting gauges, health count, and configuration/Watch state. Pool gauges describe currently configured pools; the global connection budget also includes retired channels draining existing exchanges. No metric labels contain arbitrary KV keys or full request URLs.

`gateway_rejections_total{reason="..."}` counts local rejection decisions over process lifetime; it does not equal received client responses under every cancellation/write failure. `/metrics` also includes at most 64 bounded `# pool_failure` diagnostic comments and native `gateway_pool_acquired_slots` / `gateway_pool_releasing` gauges. Registered-pool gauges exclude removed pools that still drain. Wrapper `gateway_pool_waiting` is not Netty's native pending-queue length; `gateway_pool_idle` is an approximation. See [gauge interpretation](backpressure.md#resource-gauges-and-their-limits).
