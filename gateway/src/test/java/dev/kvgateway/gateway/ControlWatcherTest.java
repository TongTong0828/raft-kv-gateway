package dev.kvgateway.gateway;

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.Empty;
import dev.kvgateway.api.*;
import io.grpc.*;
import io.grpc.stub.StreamObserver;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class ControlWatcherTest {
  @Test
  void realStreamInstallsAndRetainsLastConfigWhileDisconnected() throws Exception {
    var revision = new AtomicLong(1);
    var observers = new CopyOnWriteArrayList<StreamObserver<ConfigSnapshot>>();
    var server =
        ServerBuilder.forPort(0)
            .addService(
                new ControlGrpc.ControlImplBase() {
                  public void getStatus(Empty req, StreamObserver<StatusResponse> out) {
                    out.onNext(StatusResponse.newBuilder().setRole("LEADER").build());
                    out.onCompleted();
                  }

                  public void watchConfig(Empty req, StreamObserver<ConfigSnapshot> out) {
                    observers.add(out);
                    out.onNext(RouteManagerTest.config(revision.get()));
                  }
                })
            .build()
            .start();
    var routes = new RouteManager(Settings.DEFAULT_GROUP);
    try (var watcher = new ControlWatcher(List.of("127.0.0.1:" + server.getPort()), routes)) {
      watcher.start();
      await(() -> routes.current() != null);
      assertEquals(1, routes.current().snapshot().getRevision());
      revision.set(2);
      observers.getFirst().onNext(RouteManagerTest.config(2));
      await(() -> routes.current().snapshot().getRevision() == 2);
      server.shutdownNow().awaitTermination(2, TimeUnit.SECONDS);
      Thread.sleep(200);
      assertEquals(2, routes.current().snapshot().getRevision());
    } finally {
      server.shutdownNow();
    }
  }

  static void await(java.util.function.BooleanSupplier check) throws Exception {
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
    while (!check.getAsBoolean() && System.nanoTime() < end) Thread.sleep(10);
    assertTrue(check.getAsBoolean());
  }
}
