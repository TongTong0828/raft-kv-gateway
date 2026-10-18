package dev.kvgateway.control;

import com.google.protobuf.util.JsonFormat;
import com.sun.net.httpserver.HttpServer;
import dev.kvgateway.api.*;
import io.grpc.*;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.apache.ratis.RaftConfigKeys;
import org.apache.ratis.client.*;
import org.apache.ratis.conf.RaftProperties;
import org.apache.ratis.grpc.GrpcConfigKeys;
import org.apache.ratis.protocol.*;
import org.apache.ratis.retry.RetryPolicies;
import org.apache.ratis.rpc.SupportedRpcType;
import org.apache.ratis.server.*;
import org.apache.ratis.server.storage.RaftStorage;
import org.apache.ratis.thirdparty.com.google.protobuf.ByteString;
import org.apache.ratis.util.*;

public final class ControlNode implements AutoCloseable {
  private final Settings.Node settings;
  private final KvStateMachine state = new KvStateMachine();
  private final RaftGroupId groupId;
  private RaftServer server;
  private RaftClient client;
  private Server rpc;
  private HttpServer admin;
  private ConfigPublisher publisher;
  private final AtomicBoolean closed = new AtomicBoolean();
  private final ExecutorService rpcExecutor =
      new ThreadPoolExecutor(
          4,
          4,
          0,
          TimeUnit.MILLISECONDS,
          new ArrayBlockingQueue<>(256),
          r -> {
            var t = new Thread(r, "control-rpc");
            t.setDaemon(true);
            return t;
          });
  private final ExecutorService adminExecutor =
      Executors.newFixedThreadPool(
          2,
          r -> {
            var t = new Thread(r, "control-admin");
            t.setDaemon(true);
            return t;
          });
  private final LongAdder reads = new LongAdder(),
      writes = new LongAdder(),
      failures = new LongAdder();
  private final LongAdder timeouts = new LongAdder(),
      snapshotSuccess = new LongAdder(),
      snapshotFailure = new LongAdder();
  private final ConcurrentMap<CommandResult.Code, LongAdder> outcomes = new ConcurrentHashMap<>();
  private final ExecutorService snapshotExecutor =
      new ThreadPoolExecutor(
          1,
          1,
          0,
          TimeUnit.MILLISECONDS,
          new ArrayBlockingQueue<>(1),
          r -> new Thread(r, "snapshot-request"));

  private ControlNode(Settings.Node s) {
    settings = s;
    groupId = RaftGroupId.valueOf(UUID.fromString(s.groupId()));
  }

  public static ControlNode start(Settings.Node settings) throws Exception {
    var n = new ControlNode(settings);
    try {
      n.start();
      return n;
    } catch (Exception e) {
      n.close();
      throw e;
    }
  }

  private void start() throws Exception {
    var props = new RaftProperties();
    RaftConfigKeys.Rpc.setType(props, SupportedRpcType.GRPC);
    RaftServerConfigKeys.setStorageDir(props, List.of(settings.dataDir().toFile()));
    GrpcConfigKeys.Server.setPort(props, settings.raftPort());
    GrpcConfigKeys.Server.setHost(props, "0.0.0.0");
    GrpcConfigKeys.Server.setAsyncRequestThreadPoolCached(props, false);
    GrpcConfigKeys.Server.setAsyncRequestThreadPoolSize(props, 4);
    GrpcConfigKeys.Client.setWorkerGroupSize(props, 2);
    GrpcConfigKeys.setMessageSizeMax(props, SizeInBytes.valueOf("8MB"));
    RaftServerConfigKeys.Read.setOption(props, RaftServerConfigKeys.Read.Option.LINEARIZABLE);
    RaftServerConfigKeys.Read.setLeaderLeaseEnabled(props, false);
    RaftServerConfigKeys.Read.setLeaderHeartbeatCheckEnabled(props, true);
    RaftServerConfigKeys.Read.ReadIndex.setType(
        props, RaftServerConfigKeys.Read.ReadIndex.Type.COMMIT_INDEX);
    RaftServerConfigKeys.Read.setTimeout(props, TimeDuration.valueOf(1500, TimeUnit.MILLISECONDS));
    RaftServerConfigKeys.Rpc.setTimeoutMin(props, TimeDuration.valueOf(500, TimeUnit.MILLISECONDS));
    RaftServerConfigKeys.Rpc.setTimeoutMax(
        props, TimeDuration.valueOf(1000, TimeUnit.MILLISECONDS));
    RaftServerConfigKeys.Rpc.setRequestTimeout(props, TimeDuration.valueOf(2, TimeUnit.SECONDS));
    RaftServerConfigKeys.Log.setSegmentSizeMax(props, SizeInBytes.valueOf("8MB"));
    RaftServerConfigKeys.Log.setWriteBufferSize(props, SizeInBytes.valueOf("8MB"));
    RaftServerConfigKeys.Log.setPurgeUptoSnapshotIndex(props, true);
    RaftServerConfigKeys.Log.setPurgeGap(props, 1);
    RaftServerConfigKeys.Snapshot.setAutoTriggerEnabled(props, true);
    RaftServerConfigKeys.Snapshot.setAutoTriggerThreshold(props, 1000);
    RaftServerConfigKeys.Snapshot.setRetentionFileNum(props, 2);
    var peers =
        settings.raftPeers().entrySet().stream()
            .map(e -> RaftPeer.newBuilder().setId(e.getKey()).setAddress(e.getValue()).build())
            .toList();
    var group = RaftGroup.valueOf(groupId, peers);
    server =
        RaftServer.newBuilder()
            .setServerId(RaftPeerId.valueOf(settings.id()))
            .setGroup(group)
            .setStateMachine(state)
            .setProperties(props)
            .setOption(RaftStorage.StartupOption.RECOVER)
            .build();
    server.start();
    var cp = new RaftProperties();
    RaftConfigKeys.Rpc.setType(cp, SupportedRpcType.GRPC);
    GrpcConfigKeys.Client.setWorkerGroupSize(cp, 2);
    RaftClientConfigKeys.Rpc.setRequestTimeout(
        cp, TimeDuration.valueOf(1500, TimeUnit.MILLISECONDS));
    RaftClientConfigKeys.Async.setOutstandingRequestsMax(cp, 128);
    client =
        RaftClient.newBuilder()
            .setRaftGroup(group)
            .setLeaderId(RaftPeerId.valueOf(settings.id()))
            .setProperties(cp)
            .setRetryPolicy(
                RetryPolicies.retryUpToMaximumCountWithFixedSleep(
                    3, TimeDuration.valueOf(100, TimeUnit.MILLISECONDS)))
            .build();
    publisher =
        new ConfigPublisher(
            state.store(),
            settings.groupId(),
            () -> read(Command.newBuilder().setGetConfig(true).build(), Duration.ofSeconds(2)));
    rpc =
        NettyServerBuilder.forPort(settings.grpcPort())
            .executor(rpcExecutor)
            .maxInboundMessageSize(8 * 1024 * 1024)
            .addService(new ControlService(this, publisher))
            .build()
            .start();
    admin = HttpServer.create(new InetSocketAddress(settings.adminPort()), 16);
    admin.setExecutor(adminExecutor);
    admin.createContext(
        "/",
        exchange -> {
          int code = 200;
          String body;
          String type = "application/json";
          try {
            body =
                switch (exchange.getRequestURI().getPath()) {
                  case "/live", "/ready" -> "{\"ok\":true}";
                  case "/status" ->
                      JsonFormat.printer().includingDefaultValueFields().print(status());
                  case "/metrics" -> {
                    type = "text/plain; version=0.0.4";
                    yield metrics();
                  }
                  case "/snapshot" -> {
                    if (!exchange.getRequestMethod().equals("POST")) {
                      code = 405;
                      yield "{}";
                    }
                    yield "{\"index\":" + triggerSnapshot().get(30, TimeUnit.SECONDS) + "}";
                  }
                  default -> {
                    code = 404;
                    yield "{}";
                  }
                };
          } catch (Exception e) {
            code = 503;
            body = "{\"error\":\"unavailable\"}";
          }
          var bytes = body.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", type);
          exchange.sendResponseHeaders(code, bytes.length);
          try (var out = exchange.getResponseBody()) {
            out.write(bytes);
          }
        });
    admin.start();
  }

  private RuntimeException notLeader() {
    var id = status().getLeaderId();
    return (id.isEmpty() ? Status.UNAVAILABLE : Status.FAILED_PRECONDITION)
        .withDescription("not leader; leader_id=" + id)
        .asRuntimeException();
  }

  private boolean leaderReady() {
    try {
      return !closed.get() && server.getDivision(groupId).getInfo().isLeaderReady();
    } catch (Exception e) {
      return false;
    }
  }

  private CompletableFuture<CommandResult> execute(
      Command command, Duration timeout, boolean read) {
    if (!leaderReady()) return CompletableFuture.failedFuture(notLeader());
    if (read) reads.increment();
    else writes.increment();
    var msg = Message.valueOf(ByteString.copyFrom(command.toByteArray()));
    var f =
        read
            ? client.async().sendReadOnlyUnordered(msg, RaftPeerId.valueOf(settings.id()))
            : client.async().send(msg);
    return f.orTimeout(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS)
        .thenApply(
            reply -> {
              if (!reply.isSuccess()) throw new CompletionException(reply.getException());
              try {
                var result = CommandResult.parseFrom(reply.getMessage().getContent().toByteArray());
                if (!read)
                  outcomes.computeIfAbsent(result.getCode(), k -> new LongAdder()).increment();
                return result;
              } catch (Exception e) {
                throw new CompletionException(e);
              }
            })
        .whenComplete(
            (r, e) -> {
              if (e != null) {
                failures.increment();
                Throwable cause = e;
                while (cause.getCause() != null) cause = cause.getCause();
                if (cause instanceof TimeoutException
                    || cause instanceof java.net.SocketTimeoutException) timeouts.increment();
              }
            });
  }

  public CompletableFuture<CommandResult> read(Command c, Duration timeout) {
    return execute(c, timeout, true);
  }

  public CompletableFuture<CommandResult> write(Command c, Duration timeout) {
    return execute(c, timeout, false);
  }

  public StatusResponse status() {
    var b =
        StatusResponse.newBuilder()
            .setNodeId(settings.id())
            .setGroupId(settings.groupId())
            .setRole(closed.get() ? "CLOSED" : "STARTING");
    try {
      var division = server.getDivision(groupId);
      var info = division.getInfo();
      var leader = info.getLeaderId();
      var applied = state.getLastAppliedTermIndex();
      var snapshot = state.getLatestSnapshot();
      b.setRole(info.getCurrentRole().name())
          .setLeaderId(leader == null ? "" : leader.toString())
          .setLeaderTarget(
              leader == null ? "" : settings.grpcPeers().getOrDefault(leader.toString(), ""));
      if (applied != null)
        b.setAppliedIndex(Math.max(0, applied.getIndex()))
            .setAppliedTerm(Math.max(0, applied.getTerm()));
      b.setStateRevision(state.store().configuration(settings.groupId()).getRevision())
          .setSnapshotInstalls(state.installations())
          .setSnapshotIndex(snapshot == null ? 0 : Math.max(0, snapshot.getIndex()))
          .setLogStartIndex(division.getRaftLog().getStartIndex());
    } catch (Exception ignored) {
    }
    return b.build();
  }

  public CompletableFuture<Long> triggerSnapshot() {
    return CompletableFuture.supplyAsync(
            () -> {
              try {
                var r =
                    client
                        .getSnapshotManagementApi(RaftPeerId.valueOf(settings.id()))
                        .create(1, 30000);
                if (!r.isSuccess()) throw new CompletionException(r.getException());
                return r.getLogIndex();
              } catch (Exception e) {
                throw new CompletionException(e);
              }
            },
            snapshotExecutor)
        .whenComplete(
            (v, e) -> {
              if (e == null) snapshotSuccess.increment();
              else snapshotFailure.increment();
            });
  }

  public String metrics() {
    var s = status();
    var text =
        new StringBuilder(
            "kv_reads_total "
                + reads.sum()
                + "\nkv_writes_total "
                + writes.sum()
                + "\nkv_rpc_failures_total "
                + failures.sum()
                + "\nkv_timeouts_total "
                + timeouts.sum()
                + "\nraft_role{role=\""
                + s.getRole()
                + "\"} 1\nraft_applied_index "
                + s.getAppliedIndex()
                + "\nraft_snapshot_index "
                + s.getSnapshotIndex()
                + "\nraft_snapshot_installs_total "
                + s.getSnapshotInstalls()
                + "\nraft_log_start_index "
                + s.getLogStartIndex()
                + "\nraft_snapshot_requests_success_total "
                + snapshotSuccess.sum()
                + "\nraft_snapshot_requests_failure_total "
                + snapshotFailure.sum()
                + "\nconfig_revision "
                + s.getStateRevision()
                + "\nconfig_watchers "
                + publisher.count()
                + "\n");
    outcomes.forEach(
        (code, n) ->
            text.append("kv_mutation_results_total{result=\"")
                .append(code.name())
                .append("\"} ")
                .append(n.sum())
                .append('\n'));
    return text.toString();
  }

  @Override
  public void close() throws Exception {
    if (!closed.compareAndSet(false, true)) return;
    if (admin != null) admin.stop(0);
    if (publisher != null) publisher.close();
    if (rpc != null) {
      rpc.shutdownNow();
      rpc.awaitTermination(3, TimeUnit.SECONDS);
    }
    if (client != null) client.close();
    if (server != null) server.close();
    rpcExecutor.shutdownNow();
    adminExecutor.shutdownNow();
    snapshotExecutor.shutdownNow();
  }
}
