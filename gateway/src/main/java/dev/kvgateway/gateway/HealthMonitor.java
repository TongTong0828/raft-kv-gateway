package dev.kvgateway.gateway;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

public final class HealthMonitor implements AutoCloseable {
  private static final class State {
    volatile boolean healthy, active = true;
    boolean seen;
    int failures, successes;
    final AtomicBoolean probing = new AtomicBoolean();
    volatile CompletableFuture<Boolean> future;

    synchronized boolean result(boolean success) {
      if (!active) return false;
      boolean before = healthy;
      if (success) {
        failures = 0;
        if (!seen || ++successes >= 2) healthy = true;
        seen = true;
      } else {
        successes = 0;
        if (++failures >= 3) {
          healthy = false;
          seen = true;
        }
      }
      return before != healthy;
    }
  }

  private final ConcurrentHashMap<String, State> endpoints = new ConcurrentHashMap<>();
  private final ArrayDeque<String> rotation = new ArrayDeque<>();
  private final Semaphore slots = new Semaphore(32);
  private final Function<String, CompletableFuture<Boolean>> probe;
  private final ScheduledExecutorService scheduler =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            var t = new Thread(r, "health-scheduler");
            t.setDaemon(true);
            return t;
          });
  private volatile boolean closed;
  private volatile Runnable onChange = () -> {};

  public HealthMonitor(Function<String, CompletableFuture<Boolean>> probe) {
    this.probe = probe;
    scheduler.scheduleWithFixedDelay(this::refresh, 1, 1, TimeUnit.SECONDS);
  }

  public synchronized void reconcile(Set<String> wanted) {
    if (closed) return;
    endpoints.forEach(
        (e, s) -> {
          if (!wanted.contains(e) && endpoints.remove(e, s)) {
            s.active = false;
            var f = s.future;
            if (f != null) f.cancel(true);
          }
        });
    rotation.removeIf(e -> !wanted.contains(e));
    for (var e : wanted)
      endpoints.computeIfAbsent(
          e,
          k -> {
            rotation.addLast(k);
            return new State();
          });
    refresh();
  }

  public synchronized void refresh() {
    if (closed) return;
    for (int remaining = rotation.size(); remaining > 0; remaining--) {
      if (!slots.tryAcquire()) break;
      var e = rotation.removeFirst();
      rotation.addLast(e);
      var s = endpoints.get(e);
      if (s == null || !s.active || !s.probing.compareAndSet(false, true)) {
        slots.release();
        continue;
      }
      try {
        var f = probe.apply(e);
        s.future = f;
        f.whenComplete(
            (ok, error) -> {
              try {
                if (endpoints.get(e) == s && s.active)
                  result(s, error == null && Boolean.TRUE.equals(ok));
              } finally {
                s.probing.set(false);
                slots.release();
              }
            });
      } catch (Throwable error) {
        result(s, false);
        s.probing.set(false);
        slots.release();
      }
    }
  }

  void onChange(Runnable listener) {
    onChange = Objects.requireNonNull(listener);
  }

  private void result(State state, boolean success) {
    // Publish transitions only after releasing the individual health-state lock.
    if (state.result(success)) onChange.run();
  }

  public boolean healthy(String endpoint) {
    var s = endpoints.get(endpoint);
    return s != null && s.active && s.healthy;
  }

  public void failure(String endpoint) {
    var s = endpoints.get(endpoint);
    if (s != null) result(s, false);
  }

  Object generation(String endpoint) {
    return endpoints.get(endpoint);
  }

  void failure(String endpoint, Object generation) {
    var state = endpoints.get(endpoint);
    if (state != null && state == generation) result(state, false);
  }

  public int healthyCount() {
    return (int) endpoints.values().stream().filter(s -> s.healthy && s.active).count();
  }

  public Map<String, Boolean> statuses() {
    var out = new TreeMap<String, Boolean>();
    endpoints.forEach((e, s) -> out.put(e, s.healthy));
    return out;
  }

  public synchronized void close() {
    closed = true;
    scheduler.shutdownNow();
    endpoints.forEach(
        (e, s) -> {
          s.active = false;
          if (s.future != null) s.future.cancel(true);
        });
    endpoints.clear();
    rotation.clear();
  }
}
