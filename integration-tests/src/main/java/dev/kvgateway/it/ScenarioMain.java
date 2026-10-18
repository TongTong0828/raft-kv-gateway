package dev.kvgateway.it;

import static dev.kvgateway.it.LinearizabilityChecker.*;

import com.google.gson.Gson;
import com.google.protobuf.*;
import dev.kvgateway.api.*;
import io.grpc.*;
import io.grpc.stub.StreamObserver;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Real-network acceptance driver, packaged in the tools image. Never retries a mutation. */
public final class ScenarioMain {
  public static void main(String[] args) throws Exception {
    var settings = Settings.from(Arrays.copyOfRange(args, 1, args.length));
    var seeds = List.of(settings.get("control-seeds", "cp1:50051,cp2:50051,cp3:50051").split(","));
    var channels = new ArrayList<ManagedChannel>();
    try {
      String target = settings.get("target", "");
      ManagedChannel leader = null;
      for (var seed : target.isEmpty() ? seeds : List.of(target)) {
        var c =
            ManagedChannelBuilder.forTarget(seed)
                .usePlaintext()
                .maxInboundMessageSize(8 * 1024 * 1024)
                .build();
        channels.add(c);
        try {
          if (ControlGrpc.newBlockingStub(c)
              .withDeadlineAfter(2, TimeUnit.SECONDS)
              .getStatus(Empty.getDefaultInstance())
              .getRole()
              .equals("LEADER")) {
            leader = c;
            break;
          }
        } catch (StatusRuntimeException ignored) {
        }
      }
      if (leader == null) throw new IllegalStateException("no leader");
      var stub = ControlGrpc.newBlockingStub(leader);
      switch (args[0]) {
        case "smoke" -> smoke();
        case "fill" -> {
          long revision = 0;
          String key = "/fault/fill-" + UUID.randomUUID();
          byte[] value = new byte[settings.integer("bytes", 16384, 1, 65536)];
          for (int i = 0; i < settings.integer("count", 1200, 1, 10000); i++) {
            Arrays.fill(value, (byte) (i % 127));
            revision =
                rpc(stub)
                    .put(
                        PutRequest.newBuilder()
                            .setKey(key)
                            .setExpectedRevision(revision)
                            .setValue(ByteString.copyFrom(value))
                            .build())
                    .getRevision();
          }
          print(Map.of("fillRevision", revision));
        }
        case "history" -> history(stub);
        case "fence" -> fence(leader);
        case "seed-many", "clean-many" -> {
          int count = settings.integer("count", 80, 1, 100);
          for (int i = 0; i < count; i++) {
            String id = "bulk" + i, key = "/services/" + id;
            if (args[0].equals("seed-many")) {
              var spec =
                  ServiceSpec.newBuilder()
                      .setServiceId(id)
                      .setPathPrefix("/" + id)
                      .addEndpoints("backend-a:8081")
                      .setRequestTimeoutMs(10000)
                      .setMaxInflight(4)
                      .build();
              rpc(stub)
                  .put(PutRequest.newBuilder().setKey(key).setValue(spec.toByteString()).build());
            } else {
              var entry = rpc(stub).get(GetRequest.newBuilder().setKey(key).build()).getEntry();
              rpc(stub)
                  .delete(
                      DeleteRequest.newBuilder()
                          .setKey(key)
                          .setExpectedRevision(entry.getModRevision())
                          .build());
            }
          }
          print(Map.of("services", count, "mode", args[0]));
        }
        default -> throw new IllegalArgumentException("unknown scenario");
      }
    } finally {
      channels.forEach(ManagedChannel::shutdownNow);
      for (var c : channels) c.awaitTermination(2, TimeUnit.SECONDS);
    }
  }

  static void smoke() throws Exception {
    var seen = new TreeSet<String>();
    try (var client =
        java.net.http.HttpClient.newBuilder()
            .version(java.net.http.HttpClient.Version.HTTP_1_1)
            .build()) {
      long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
      while (System.nanoTime() < end) {
        try {
          var response =
              client.send(
                  java.net.http.HttpRequest.newBuilder(
                          java.net.URI.create("http://gateway:8080/orders/item"))
                      .timeout(java.time.Duration.ofSeconds(2))
                      .build(),
                  java.net.http.HttpResponse.BodyHandlers.ofByteArray());
          if (response.statusCode() == 200 && response.body().length == 1024)
            seen.add(response.headers().firstValue("X-Backend-Id").orElse(""));
          if (seen.equals(Set.of("backend-a", "backend-b"))) break;
        } catch (java.io.IOException ignored) {
        }
        Thread.sleep(100);
      }
      if (!seen.equals(Set.of("backend-a", "backend-b")))
        throw new AssertionError("missing healthy backend: " + seen);
      var metrics =
          client
              .send(
                  java.net.http.HttpRequest.newBuilder(
                          java.net.URI.create("http://gateway:9090/metrics"))
                      .build(),
                  java.net.http.HttpResponse.BodyHandlers.ofString())
              .body();
      if (!metrics.contains("gateway_inflight 0\n") || !metrics.contains("gateway_body_bytes 0\n"))
        throw new AssertionError("gateway did not release resources");
      print(Map.of("smoke", "PASS", "backends", seen, "responseBytes", 1024));
    }
  }

  static void history(ControlGrpc.ControlBlockingStub stub) throws Exception {
    String key = "/history/" + UUID.randomUUID();
    var history = new CopyOnWriteArrayList<Op>();
    var threads = Executors.newFixedThreadPool(3);
    var gate = new CountDownLatch(1);
    var winners = new AtomicReference<MutationResponse>();
    try {
      var futures = new ArrayList<Future<?>>();
      for (int i = 0; i < 3; i++) {
        String value = "writer-" + i;
        futures.add(
            threads.submit(
                () -> {
                  try {
                    gate.await();
                  } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                  }
                  long start = System.nanoTime();
                  String result = "OK";
                  long revision = 0;
                  try {
                    var r =
                        rpc(stub)
                            .put(
                                PutRequest.newBuilder()
                                    .setKey(key)
                                    .setValue(ByteString.copyFromUtf8(value))
                                    .build());
                    revision = r.getRevision();
                    winners.set(r);
                  } catch (StatusRuntimeException e) {
                    result =
                        e.getStatus().getCode() == Status.Code.ABORTED ? "CONFLICT" : "UNKNOWN";
                  }
                  history.add(new Op(start, System.nanoTime(), "put", value, 0, result, revision));
                }));
      }
      gate.countDown();
      for (var f : futures) f.get(10, TimeUnit.SECONDS);
      var winner = winners.get();
      if (winner == null) throw new AssertionError("no completed winner");
      read(stub, key, history);
      long start = System.nanoTime();
      String result = "OK";
      long revision = 0;
      try {
        revision =
            stub.withDeadlineAfter(1, TimeUnit.NANOSECONDS)
                .put(
                    PutRequest.newBuilder()
                        .setKey(key)
                        .setExpectedRevision(winner.getRevision())
                        .setValue(ByteString.copyFromUtf8("uncertain"))
                        .build())
                .getRevision();
      } catch (StatusRuntimeException e) {
        result = "UNKNOWN";
      }
      history.add(
          new Op(
              start,
              System.nanoTime(),
              "put",
              "uncertain",
              winner.getRevision(),
              result,
              revision));
      read(stub, key, history);
      if (!check(history))
        throw new AssertionError("nonlinearizable history: " + new Gson().toJson(history));
      print(Map.of("linearizable", true, "history", history));
    } finally {
      threads.shutdownNow();
    }
  }

  static void read(ControlGrpc.ControlBlockingStub stub, String key, List<Op> history) {
    long start = System.nanoTime();
    var r = rpc(stub).get(GetRequest.newBuilder().setKey(key).build());
    history.add(
        new Op(
            start,
            System.nanoTime(),
            "read",
            r.getEntry().getValue().toStringUtf8(),
            0,
            "OK",
            r.getEntry().getModRevision()));
  }

  static void fence(ManagedChannel channel) throws Exception {
    var ready = new CountDownLatch(1);
    var closed = new CountDownLatch(1);
    var error = new AtomicReference<String>();
    var fenceTime = new AtomicLong();
    long started = System.nanoTime();
    var context = Context.current().withCancellation();
    context.run(
        () ->
            ControlGrpc.newStub(channel)
                .watchConfig(
                    Empty.getDefaultInstance(),
                    new StreamObserver<ConfigSnapshot>() {
                      public void onNext(ConfigSnapshot c) {
                        ready.countDown();
                      }

                      public void onCompleted() {
                        closed.countDown();
                      }

                      public void onError(Throwable e) {
                        error.set(Status.fromThrowable(e).getCode().name());
                        fenceTime.set(System.nanoTime());
                        closed.countDown();
                      }
                    }));
    try {
      if (!ready.await(5, TimeUnit.SECONDS)) throw new AssertionError("watch not initialized");
      print(Map.of("watchReady", true));
      Thread.sleep(1000);
      try {
        ControlGrpc.newBlockingStub(channel)
            .withDeadlineAfter(2, TimeUnit.SECONDS)
            .getConfig(Empty.getDefaultInstance());
        throw new AssertionError("isolated leader served successful read");
      } catch (StatusRuntimeException expected) {
        print(Map.of("oldLeaderRead", expected.getStatus().getCode().name()));
      }
      if (!closed.await(3, TimeUnit.SECONDS)) throw new AssertionError("old watch not fenced");
      print(
          Map.of(
              "watchFenced",
              true,
              "elapsedMs",
              (fenceTime.get() - started) / 1000000,
              "code",
              String.valueOf(error.get())));
    } finally {
      context.cancel(null);
    }
  }

  static ControlGrpc.ControlBlockingStub rpc(ControlGrpc.ControlBlockingStub stub) {
    return stub.withDeadlineAfter(3, TimeUnit.SECONDS);
  }

  static void print(Object value) {
    System.out.println(new Gson().toJson(value));
    System.out.flush();
  }
}
