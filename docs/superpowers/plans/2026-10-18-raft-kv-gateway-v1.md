# Raft KV & Gateway v1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build, test and publish the approved reproducible three-node Raft KV and Netty gateway personal project.

**Architecture:** Maven reactor with `api`, `control-plane`, `gateway`, `cli`, `demo-backend`, and `integration-tests`. Ratis owns consensus and durable logs; the deterministic KV owns CAS and snapshot state; full gRPC snapshots drive immutable gateway routes. Business HTTP requests stay entirely on the data plane.

**Tech Stack:** Java 21, Maven 3.9.16, Ratis 3.3.1, Netty 4.2.19.Final, gRPC 1.85.0, protobuf 3.25.9, JUnit 5.14.4, Docker Compose, Kubernetes/kind, k6 2.3.0.

**Spec:** `docs/superpowers/specs/2026-10-18-raft-kv-gateway-design.md`

**Execution:** Native in this directory on `feat/v1`, with independent subagent research/review and one fresh whole-branch review. User explicitly authorized writing this plan and then implementing without another approval turn. Starting base: `9a4c117`.

## Global Constraints

- Java compilation target 21; dependency versions and image digests exactly as spec §5; never mix application protobuf with Ratis shaded protobuf.
- Three fixed voters; quorum 2; linearizable Get/GetConfig; conditional mutations only; successful revision = Raft log index; outcome unknown on submitted mutation timeout.
- Key 256 UTF-8 bytes, value 64 KiB, 1,024 records, 4 MiB aggregate; service 128, endpoints/service 32; config encoded <=4 MiB and transport <=8 MiB.
- Service timeout 10–30,000 ms/default 2,000; maxInflight 1–10,000/default 100; endpoint connections 1–256/default 16.
- Watch maximum 16/node; one pending latest snapshot; transport-readiness-aware; 1 s quorum/read-barrier heartbeat with 2 s timeout; reconnect 100 ms–5 s with jitter.
- HTTP/1.1 bounded aggregate only; request/response each <=1 MiB, initial line 4 KiB, headers 8 KiB; no retries, pipelining, trailers, Upgrade, CONNECT or HTTP/2 proxying.
- Admission before body aggregation; <=2,048 downstream connections, <=256 exchanges, <=512 business upstream connections, <=64 MiB held aggregate bytes; acquire 100 ms, connect 250 ms, header stall 5 s.
- Health `/health`: 200, <=4 KiB, 250 ms; nominal 1 s, <=32 concurrent probes; 3 failures/2 recoveries, first UNKNOWN success healthy; count only endpoint-attributable passive failures.
- Compose runtime memory limits: three controls ×320 MiB + gateway 768 MiB + two backends ×128 MiB; down preserves data.
- Public repo default `TongTong0828/raft-kv-gateway`, Apache-2.0; Docker-only quickstart, English/Chinese docs; EKS example unless actually verified; never publish fabricated measurements.

## Review Focus

- A mutation committed before its reply is lost must remain an unknown client outcome, not be silently retried as a second operation; cover Task 3/5 response-loss history.
- A removed/recreated key or endpoint must reject stale revisions/callbacks; cover Task 2 ABA and Task 4 endpoint/config generations.
- An isolated former Leader must not return a stale successful read or keep a validated Watch forever; cover Task 3 barriers and Task 5 Raft-only partition.
- Client cancellation, late pool acquisition, slow body or response must not leak resources or penalize healthy endpoints; cover Task 4 raw HTTP/pool tests.
- Slow configuration subscribers and concurrent large bodies must stay bounded even across many services; cover Task 3/4 budgets and Task 5 measured memory scenario.

## File structure

`pom.xml` owns versions/plugin management. Each module has its own POM. Java packages start with `dev.kvgateway`. Generated API package is `dev.kvgateway.api`, with `java_multiple_files=true`.

- `api/src/main/proto/control.proto`: public and internal serialized command/snapshot schemas.
- `api/src/main/java/dev/kvgateway/api/ServiceSpecs.java`: pure deterministic service/config validation and defaults.
- `api/src/main/java/dev/kvgateway/api/Settings.java`: explicit environment/argument parsing; no external state.
- `control-plane/src/main/java/dev/kvgateway/control/{KvStore,KvStateMachine,ControlNode,ControlService,ConfigPublisher,ControlMain}.java`: state, durability, consensus lifecycle, RPC boundary, bounded fanout, launcher.
- `gateway/src/main/java/dev/kvgateway/gateway/{RouteTable,RouteManager,ControlWatcher,HealthMonitor,ResourceBudget,EndpointPool,ProxyHandler,GatewayServer,GatewayMain}.java`: independent responsibilities with explicit lifecycle.
- `cli/src/main/java/dev/kvgateway/cli/{ControlClient,CliMain}.java`: Leader discovery and commands.
- `demo-backend/src/main/java/dev/kvgateway/demo/DemoMain.java`: deterministic demo HTTP behavior.
- `integration-tests/src/test/java/dev/kvgateway/it/`: real cluster/process tests and bounded history checker.
- `docker/`, `compose.yaml`, `scripts/`, `deploy/kubernetes/`, `benchmarks/`, `docs/`, `.github/workflows/`: reproducible distribution and evidence.

---

### Task 1: Reproducible build and API/service contract

**Files:** Create root/module POMs, `.gitignore`, `scripts/mvn.sh`, `api/src/main/proto/control.proto`, `ServiceSpecs.java`, `Settings.java`; tests `api/src/test/java/dev/kvgateway/api/{ServiceSpecsTest,SettingsTest}.java`.

**Interfaces:** Produces protobuf `Entry(key,value,mod_revision)`, `ServiceSpec(service_id,path_prefix,endpoints,optional request_timeout_ms,optional max_inflight,optional max_connections_per_endpoint)`, `ConfigSnapshot(group_id,revision,services)`, Get/Put/Delete requests/responses, `StatusResponse`, `Command` oneof get/put/delete/get_config, `CommandResult`, and `StoreSnapshot(format_version,last_term,last_index,state_revision,entries)`. Public `Control` service methods Get/Put/Delete/GetConfig/WatchConfig/GetStatus. Produces `ServiceSpecs.normalize(ServiceSpec): ServiceSpec`, `validateAll(Collection<ServiceSpec>): void`, and `Settings.from(String[]): Settings` with typed defaults.

- [ ] Step 1: Add Maven reactor, pinned Docker Maven wrapper (cache volume, host UID handling, Java21), generated API, and behavioral tests first. `rejectsInvalidEndpoint`, `defaultsOnlyAbsentFields`, `rejectsDuplicatePrefixesAndExcessServices`, `parsesExplicitNodeSettings` assert literal spec boundaries and values.
- [ ] Step 2: Run `./scripts/mvn.sh -pl api -am test`; expected RED for missing validator/settings behavior, not unavailable dependencies. Retain log in `work/verification/`.
- [ ] Step 3: Implement deterministic normalization/validation and parsing; ensure defaults distinguish protobuf optional omission from explicit zero. No DNS/network calls in validators.
- [ ] Step 4: Run `./scripts/mvn.sh -pl api -am verify`; expected all tests green and generated API compiled.
- [ ] Step 5: Commit `build: add reproducible Java reactor and validated API`.

### Task 2: Deterministic CAS state and durable snapshots

**Files:** `KvStore.java`, `KvStateMachine.java`; tests `control-plane/src/test/java/dev/kvgateway/control/{KvStoreTest,KvSnapshotTest}.java`.

**Interfaces:** Consumes Task1 messages. `KvStore.apply(Command,long logIndex): CommandResult`; `query(Command): CommandResult`; `snapshot(long term,long index): StoreSnapshot`; `restore(StoreSnapshot): void`; `configuration(String groupId): ConfigSnapshot`. `KvStateMachine extends BaseStateMachine` bridges Ratis byte messages, serialized transaction application, term/index, initialization/reinitialization, and snapshot capture/install; exposes store and configuration listener without exposing mutable maps.

- [ ] Step 1: Write tests for `concurrentCasHasOneWinner`, `deleteRecreateRejectsOldRevision`, `invalidAndOverBudgetMutationDoesNotChangeState`, `servicePrefixConflictIsAtomic`, `snapshotRestoresValuesAndRevisions`, `corruptOrPartialSnapshotCannotReplaceValidState` using literal committed indices 10/20/30.
- [ ] Step 2: Run `./scripts/mvn.sh -pl control-plane -am test`; expected RED for missing state behavior. Inspect Ratis 3.3.1 source APIs before the adapter implementation.
- [ ] Step 3: Implement synchronized deterministic store and Ratis adapter; durable temporary snapshot/checksum/atomic publication, valid metadata, framework-managed log retention. No uncontrolled executor for apply ordering.
- [ ] Step 4: Run `./scripts/mvn.sh -pl control-plane -am verify`; expected green state and snapshot tests.
- [ ] Step 5: Commit `feat: implement revision checked KV state and snapshots`.

### Task 3: Real Ratis nodes, linearizable RPC, Watch and CLI

**Files:** ControlNode/ControlService/ConfigPublisher/ControlMain, CLI ControlClient/CliMain; control tests `ControlClusterTest`, `ConfigPublisherTest`; CLI `ControlClientTest`.

**Interfaces:** `ControlNode.start(NodeSettings): ControlNode implements AutoCloseable`, `read(Command,Duration): CompletableFuture<CommandResult>`, `write(Command,Duration): CompletableFuture<CommandResult>`, `status(): StatusResponse`, `triggerSnapshot(): CompletableFuture<Long>`. `ConfigPublisher.subscribe(ServerCallStreamObserver<ConfigSnapshot>)` registers atomically with state snapshot; lifecycle close/fence. `ControlClient(List<String> seeds,Duration timeout)` discovers Leader for read/watch and sends a mutation once after discovery; `get`, `put`, `delete`, `getConfig`, `status`, `watch`, `close`. CLI commands `status`, `kv get|put|delete`, `service get|put|delete`, `watch`; service JSON file supported.

- [ ] Step 1: Write real three-node tests for successful CAS/read, Follower Leader-hint, minority read failure, commit/reply-loss outcome, node restart, and tests with backpressured real gRPC observers for subscriber total/latest-value/cancel. Write CLI tests against actual RPC, not mocked stub calls.
- [ ] Step 2: Run `./scripts/mvn.sh -pl cli -am test`; expected RED for unavailable project node/RPC behavior.
- [ ] Step 3: Implement Node lifecycle and bounded Ratis request retries, strong reads, error/status mapping, gRPC dispatch and transport readiness, shared read-barrier fencing; no blocking EventLoop. Implement shared application client and CLI. Management HTTP exposes live/ready/status/metrics and POST snapshot internally.
- [ ] Step 4: Run `./scripts/mvn.sh -pl cli -am verify`; expected green including leadership/restart/watch tests; generated runnable control/CLI jars.
- [ ] Step 5: Commit `feat: expose durable Raft KV and bounded configuration RPC`.

### Task 4: Netty gateway with bounded lifecycle

**Files:** Gateway classes listed above and demo backend; tests under `gateway/src/test/java/dev/kvgateway/gateway/{RouteManagerTest,ResourceBudgetTest,HealthMonitorTest,GatewayHttpTest,EndpointPoolTest}.java`.

**Interfaces:** `RouteTable.from(ConfigSnapshot)` immutable matching; `RouteManager.install(ConfigSnapshot,long subscriptionGeneration): boolean`, `current(): RouteTable`; `ResourceBudget` owns atomic closeable admission/byte/connection leases; `HealthMonitor` owns endpoint generation and async probe scheduling; `EndpointPool.acquire(deadline): Future<Lease>` and exactly-once lease close/release; `GatewayServer.start(GatewaySettings): GatewayServer implements AutoCloseable`; `ControlWatcher` feeds RouteManager and tracks generation. Demo backend returns `/health` and fixed byte payload with `X-Backend-Id`, supports bounded delay/failure via explicit process settings.

- [ ] Step 1: Write behavior tests: segment-prefix matching; old async build cannot roll back; service permit survives revision change; stale endpoint probe cannot restore removal; health excludes pool/client failure; raw HTTP framing/HEAD/chunked/pipelining; actual keep-alive reuses upstream; tiny pool times out cleanly; late acquire/cancel/partial body retires channel; one POST attempt; body and global budgets reject without leaks.
- [ ] Step 2: Run `./scripts/mvn.sh -pl gateway -am test`; expected RED for missing gateway behavior.
- [ ] Step 3: Implement route/config lifecycle, health probes, independent budgets, real Netty pool and HTTP exchange state machine in small focused classes. Acquire admission on headers before aggregation; one owner cleans every terminal path. Implement watcher reconnect and diagnostics. Add demo backend and runnable jars.
- [ ] Step 4: Run `./scripts/mvn.sh verify`; expected all reactor tests green, Netty leak detection enabled for gateway tests, no pending live threads after close.
- [ ] Step 5: Commit `feat: add bounded health aware Netty data plane`.

### Task 5: Docker quickstart and real fault evidence

**Files:** `docker/Dockerfile`, `compose.yaml`, `scripts/dev.sh`, `scripts/smoke.sh`, `scripts/faults.py`, `scripts/cli.sh`, sample service JSON; integration tests `ClusterIT`, `LinearizabilityCheckerTest` and checker helpers.

**Interfaces:** Consumes packaged module jars and CLI commands. `dev.sh build|up|seed|smoke|down|reset`, down preserves volumes; reset is explicit. `faults.py --project NAME --output PATH` creates isolated resources and restores networks/processes in finally. Structured JSON evidence stores operation start/end/result/revision, topology events and snapshots. Internal management status supplies applied/snapshot/install counts.

- [ ] Step 1: Write smoke/fault assertions before starting implementation: seed then real HTTP 200; update routing during traffic; cached routing with all controls down; isolate current Leader's Raft network while keeping API accessible; force snapshots after Follower stops and prove install upon restart; restart all with volumes; bounded concurrent history includes unknown writes. Add hand-written valid/invalid histories for checker RED.
- [ ] Step 2: Run checker tests and smoke against missing stack; expected specific RED. Do not confuse Docker connectivity failure with feature RED.
- [ ] Step 3: Implement multi-stage image, memory-limited Compose with app/Raft networks, tools profile, bounded shell/Python orchestration, endpoint seed rewriting for container/local use, and checker. Fail fast with useful diagnostics; preserve raw logs under work for scratch and docs/results for curated evidence.
- [ ] Step 4: Run `./scripts/mvn.sh verify`, `./scripts/dev.sh up`, `./scripts/dev.sh smoke`, isolated fault suite. Expected all required behaviors proven; record actual event/term/index data; measure large-body memory bounds and no OOM.
- [ ] Step 5: Commit `test: add reproducible cluster quickstart and fault verification`.

### Task 6: Kubernetes deployment and local validation

**Files:** `deploy/kubernetes/base/` Namespace/ConfigMap/StatefulSet/Headless Service/PVC/PDB/Deployment/Service/kustomization, `overlays/kind/`, `overlays/eks/`, `scripts/kind-smoke.sh`.

**Interfaces:** Uses same image/config/ports as Task5. kind overlay loads local image, uses standard provisioned PVC and NodePort/port-forward; EKS overlay uses gp3 assumptions and LoadBalancer only for gateway. Script owns a uniquely named local cluster and never mutates an unrelated current kubectl context.

- [ ] Step 1: Define smoke assertions for three ready controls, installed gateway config, 200 route, retained data after one StatefulSet Pod replacement, and gateway rollout without config loss. YAML/prose changes need execution validation, not source-text tests.
- [ ] Step 2: Render with `kubectl kustomize` and run smoke; expected initial failures before manifests exist.
- [ ] Step 3: Implement Parallel startup, independent probes/PVC, PDB minAvailable2 and topology spread; obtain official kind runtime locally if necessary, without system/global setup changes.
- [ ] Step 4: Run isolated `scripts/kind-smoke.sh`; expected real HTTP/config/persistence checks green. EKS is render-checked only unless cloud execution is separately authorized and performed.
- [ ] Step 5: Commit `deploy: add verified local Kubernetes and EKS example`.

### Task 7: Benchmarks, documentation, CI and public release

**Files:** `benchmarks/{gateway,kv,slow-backend,isolation}.js`, benchmark runner/summary tools, `docs/results/`, README.md/README.zh-CN.md, docs architecture/protocol/recovery/benchmark guide, LICENSE/NOTICE/CONTRIBUTING/SECURITY, `.github/workflows/ci.yml`.

**Interfaces:** Benchmarks consume Task5 stack and metadata, use fixed k6 image; all JSON raw results tie to a commit and environment. CI runs `scripts/mvn.sh verify`, Compose smoke/fault checks and Kubernetes render. Docs commands must match actual scripts/CLI.

- [ ] Step 1: Add actual scenario assertions (expected size/status, no silent retries), parser fixtures with dropped/failed requests and literal success/error outputs. Run summary tests RED before parser implementation.
- [ ] Step 2: Implement fixed-arrival scenarios and summary with all outcomes, precise units, hardware and three repeats. Run the spec's six offered-load levels with bounded resources and synthetic fixed-size backend; do not change sync durability to improve results.
- [ ] Step 3: Write bilingual quickstart/architecture/limitations/evidence and Apache license/notice; document EKS example status, HTTP exclusions and unknown write results. Run documented quickstart from clean Git checkout, all verification and secret/credential review on tracked files.
- [ ] Step 4: Commit, create whole-branch review package and dispatch a fresh capable reviewer. Re-grade findings; reproduce and fix important issues with RED→GREEN tests and a green full suite; ledger deviations and deferred minor findings.
- [ ] Step 5: Verify `gh auth status`, ensure the default target does not already contain another project, create PUBLIC `TongTong0828/raft-kv-gateway` and push the verified implementation. If name collision exists, choose an unused descriptive suffix and record it. Inspect remote contents and CI outcome; fix actual failures. Do not claim EKS execution or unmeasured performance.
- [ ] Step 6: Record final URL, commit, verification commands/results and any remaining verified limitations. Commit `docs: publish measured v1 results and usage` as needed; ensure remote HEAD matches final local commit.

## Self-review and coverage

Every spec section maps to a task: goals/scope/versions/modules→1; KV/durability→2–3; configuration→3–4; HTTP/health/resources→4; Compose/fault acceptance→5; Kubernetes/EKS→6; performance/open source/CI→7. Shared interfaces are generated messages, ServiceSpecs validation, ControlClient RPCs, configuration revision/generation and executable jar/CLI contracts; later tasks consume the exact names listed above. Requirements may be split into additional focused classes when implementation evidence warrants, with changes recorded in the execution ledger.

The spec remains authoritative. Any unsupported upstream API or discovered correctness conflict is resolved against it, logged with the cost of the decision, and covered by a behavioral test. Task completions require real passing command output; a missing external environment is recorded as unverified and does not become a passing test.
