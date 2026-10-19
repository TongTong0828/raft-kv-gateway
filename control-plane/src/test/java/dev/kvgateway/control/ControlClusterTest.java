package dev.kvgateway.control;

import static org.junit.jupiter.api.Assertions.*;

import dev.kvgateway.api.*;
import java.net.ServerSocket;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ControlClusterTest {
  @TempDir static Path dir;
  static final List<ControlNode> nodes = new ArrayList<>();
  static final List<Settings.Node> settings = new ArrayList<>();

  static int port() throws Exception {
    try (var socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }

  @BeforeAll
  static void start() throws Exception {
    var raft = new TreeMap<String, String>();
    var grpc = new TreeMap<String, String>();
    for (int i = 0; i < 3; i++) {
      raft.put("n" + i, "localhost:" + port());
      grpc.put("n" + i, "localhost:" + port());
    }
    for (int i = 0; i < 3; i++)
      settings.add(
          new Settings.Node(
              "n" + i,
              Settings.DEFAULT_GROUP,
              Map.copyOf(raft),
              Map.copyOf(grpc),
              dir.resolve("n" + i),
              Integer.parseInt(grpc.get("n" + i).split(":")[1]),
              Integer.parseInt(raft.get("n" + i).split(":")[1]),
              0));
    for (var s : settings) nodes.add(assertDoesNotThrow(() -> ControlNode.start(s)));
  }

  static ControlNode leader() throws Exception {
    long end = System.nanoTime() + Duration.ofSeconds(15).toNanos();
    while (System.nanoTime() < end) {
      for (var n : nodes)
        if (n != null && n.status().getRole().equals("LEADER")) {
          try {
            n.read(Command.newBuilder().setGetConfig(true).build(), Duration.ofSeconds(1)).get();
            return n;
          } catch (Exception ignored) {
          }
        }
      Thread.sleep(50);
    }
    throw new AssertionError("no usable leader");
  }

  @AfterAll
  static void stop() throws Exception {
    for (var n : nodes) if (n != null) n.close();
  }

  @Test
  @Order(1)
  void majorityCommitAndLinearizableRead() throws Exception {
    var n = leader();
    var r = n.write(KvStoreTest.put("durable", "value", 0), Duration.ofSeconds(3)).get();
    assertEquals(CommandResult.Code.OK, r.getCode());
    assertTrue(r.getStateRevision() > 0);
    assertEquals(
        "value",
        n.read(KvStoreTest.get("durable"), Duration.ofSeconds(3))
            .get()
            .getEntry()
            .getValue()
            .toStringUtf8());
    assertEquals(
        CommandResult.Code.CONFLICT,
        n.write(KvStoreTest.put("durable", "stale", 0), Duration.ofSeconds(3)).get().getCode());
  }

  @Test
  @Order(2)
  void followerRejectsApplicationReadAndLeaderFailoverRetainsCommit() throws Exception {
    var old = leader();
    var follower = nodes.stream().filter(n -> n != old).findFirst().orElseThrow();
    assertThrows(
        Exception.class,
        () -> follower.read(KvStoreTest.get("durable"), Duration.ofSeconds(1)).get());
    int i = nodes.indexOf(old);
    old.close();
    nodes.set(i, null);
    var next = leader();
    assertEquals(
        "value",
        next.read(KvStoreTest.get("durable"), Duration.ofSeconds(3))
            .get()
            .getEntry()
            .getValue()
            .toStringUtf8());
    nodes.set(i, ControlNode.start(settings.get(i)));
  }

  @Test
  @Order(3)
  void snapshotAndFullRestartPreserveRevision() throws Exception {
    var n = leader();
    long revision =
        n.read(KvStoreTest.get("durable"), Duration.ofSeconds(3)).get().getEntry().getModRevision();
    assertTrue(n.triggerSnapshot().get() > 0);
    for (int i = 0; i < 3; i++) {
      nodes.get(i).close();
      nodes.set(i, null);
    }
    for (int i = 0; i < 3; i++) nodes.set(i, ControlNode.start(settings.get(i)));
    var read = leader().read(KvStoreTest.get("durable"), Duration.ofSeconds(3)).get();
    assertEquals(revision, read.getEntry().getModRevision());
    assertEquals("value", read.getEntry().getValue().toStringUtf8());
  }

  @Test
  @Order(4)
  void realGrpcWatchDeliversInitialSnapshot() throws Exception {
    var n = leader();
    var target =
        settings.stream()
            .filter(s -> s.id().equals(n.status().getNodeId()))
            .findFirst()
            .orElseThrow()
            .grpcPeers()
            .get(n.status().getNodeId());
    var channel = io.grpc.ManagedChannelBuilder.forTarget(target).usePlaintext().build();
    var latch = new java.util.concurrent.CountDownLatch(1);
    var error = new java.util.concurrent.atomic.AtomicReference<Throwable>();
    var value = new java.util.concurrent.atomic.AtomicReference<ConfigSnapshot>();
    try {
      ControlGrpc.newStub(channel)
          .withDeadlineAfter(5, java.util.concurrent.TimeUnit.SECONDS)
          .watchConfig(
              com.google.protobuf.Empty.getDefaultInstance(),
              new io.grpc.stub.StreamObserver<>() {
                public void onNext(ConfigSnapshot s) {
                  value.set(s);
                  latch.countDown();
                }

                public void onError(Throwable e) {
                  error.set(e);
                  latch.countDown();
                }

                public void onCompleted() {
                  latch.countDown();
                }
              });
      assertTrue(latch.await(6, java.util.concurrent.TimeUnit.SECONDS));
      assertNull(error.get());
      assertNotNull(value.get());
      assertEquals(Settings.DEFAULT_GROUP, value.get().getGroupId());
    } finally {
      channel.shutdownNow().awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS);
    }
  }

  @Test
  @Order(5)
  void commitBeforeReplyLossCanBeReconciled() throws Exception {
    var n = leader();
    int rpcPort = port();
    try (var publisher =
        new ConfigPublisher(
            new KvStore(),
            Settings.DEFAULT_GROUP,
            () -> java.util.concurrent.CompletableFuture.completedFuture(null))) {
      var dropReplies =
          new io.grpc.ServerInterceptor() {
            public <Q, S> io.grpc.ServerCall.Listener<Q> interceptCall(
                io.grpc.ServerCall<Q, S> call,
                io.grpc.Metadata headers,
                io.grpc.ServerCallHandler<Q, S> next) {
              return next.startCall(
                  new io.grpc.ForwardingServerCall.SimpleForwardingServerCall<Q, S>(call) {
                    @Override
                    public void sendMessage(S value) {}

                    @Override
                    public void close(io.grpc.Status status, io.grpc.Metadata trailers) {}
                  },
                  headers);
            }
          };
      var transport =
          io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder.forPort(rpcPort)
              .addService(
                  io.grpc.ServerInterceptors.intercept(
                      new ControlService(n, publisher), dropReplies))
              .build()
              .start();
      var channel =
          io.grpc.ManagedChannelBuilder.forAddress("localhost", rpcPort).usePlaintext().build();
      try {
        var lost =
            assertThrows(
                io.grpc.StatusRuntimeException.class,
                () ->
                    ControlGrpc.newBlockingStub(channel)
                        .withDeadlineAfter(1, java.util.concurrent.TimeUnit.SECONDS)
                        .put(
                            PutRequest.newBuilder()
                                .setKey("reply-lost")
                                .setValue(com.google.protobuf.ByteString.copyFromUtf8("committed"))
                                .build()));
        assertEquals(io.grpc.Status.Code.DEADLINE_EXCEEDED, lost.getStatus().getCode());
        var state = n.read(KvStoreTest.get("reply-lost"), Duration.ofSeconds(3)).get();
        assertEquals("committed", state.getEntry().getValue().toStringUtf8());
        assertEquals(
            CommandResult.Code.CONFLICT,
            n.write(KvStoreTest.put("reply-lost", "retry", 0), Duration.ofSeconds(3))
                .get()
                .getCode());
      } finally {
        channel.shutdownNow().awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS);
        transport.shutdownNow().awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS);
      }
    }
  }

  @Test
  @Order(6)
  void shortDeadlinesNeverExhaustRatisClientSlots() throws Exception {
    var node = leader();
    var timeouts = new java.util.concurrent.atomic.AtomicInteger();
    var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
    try {
      var progress =
          executor.submit(
              () -> {
                for (int i = 0; i < 512 && !Thread.currentThread().isInterrupted(); i++) {
                  try {
                    node.write(
                            KvStoreTest.put("short-deadline", "x".repeat(60000), 0),
                            Duration.ofMillis(1))
                        .join();
                  } catch (java.util.concurrent.CompletionException error) {
                    if (error.getCause() instanceof java.util.concurrent.TimeoutException)
                      timeouts.incrementAndGet();
                  }
                }
                return node.write(
                        KvStoreTest.put("after-short-deadlines", "alive", 0), Duration.ofSeconds(3))
                    .get();
              });
      var result = progress.get(15, java.util.concurrent.TimeUnit.SECONDS);
      assertTrue(timeouts.get() > 128, "exercise more timeouts than all configured client slots");
      assertEquals(CommandResult.Code.OK, result.getCode());
      assertEquals("LEADER", node.status().getRole());
    } finally {
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS));
    }
  }
}
