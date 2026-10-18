package dev.kvgateway.control;

import static org.junit.jupiter.api.Assertions.*;

import dev.kvgateway.api.*;
import io.grpc.stub.ServerCallStreamObserver;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class ConfigPublisherTest {
  static final class Sink extends ServerCallStreamObserver<ConfigSnapshot> {
    volatile boolean ready, cancelled;
    volatile Runnable onReady, onCancel;
    final List<ConfigSnapshot> values = new CopyOnWriteArrayList<>();
    volatile Throwable error;

    public void onNext(ConfigSnapshot v) {
      values.add(v);
    }

    public void onError(Throwable t) {
      error = t;
    }

    public void onCompleted() {}

    public boolean isCancelled() {
      return cancelled;
    }

    public void setOnCancelHandler(Runnable r) {
      onCancel = r;
    }

    public void setCompression(String s) {}

    public boolean isReady() {
      return ready;
    }

    public void setOnReadyHandler(Runnable r) {
      onReady = r;
    }

    public void disableAutoInboundFlowControl() {}

    public void request(int n) {}

    public void setMessageCompression(boolean b) {}

    void ready() {
      ready = true;
      if (onReady != null) onReady.run();
    }

    void cancel() {
      cancelled = true;
      if (onCancel != null) onCancel.run();
    }
  }

  static void await(java.util.function.BooleanSupplier condition) throws Exception {
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
    while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(10);
    assertTrue(condition.getAsBoolean());
  }

  @Test
  void slowSubscriberGetsOnlyLatestAndCapacityRecoversOnCancel() throws Exception {
    var store = new KvStore();
    try (var publisher =
        new ConfigPublisher(store, "g", () -> CompletableFuture.completedFuture(null))) {
      var sinks = new ArrayList<Sink>();
      for (int i = 0; i < 16; i++) {
        var sink = new Sink();
        sinks.add(sink);
        publisher.subscribe(sink);
      }
      var excess = new Sink();
      publisher.subscribe(excess);
      await(() -> excess.error != null);
      store.apply(KvStoreTest.put("x", "one", 0), 10);
      store.apply(KvStoreTest.put("x", "two", 10), 20);
      var sink = sinks.getFirst();
      sink.ready();
      await(() -> !sink.values.isEmpty());
      assertEquals(20, sink.values.getLast().getRevision());
      assertEquals(1, sink.values.size());
      sink.cancel();
      var replacement = new Sink();
      replacement.ready();
      publisher.subscribe(replacement);
      await(() -> !replacement.values.isEmpty());
      assertNull(replacement.error);
    }
  }

  @Test
  void failedQuorumBarrierEndsWatch() throws Exception {
    try (var p =
        new ConfigPublisher(
            new KvStore(),
            "g",
            () -> CompletableFuture.failedFuture(new IllegalStateException("partition")))) {
      var s = new Sink();
      p.subscribe(s);
      await(() -> s.error != null);
    }
  }
}
