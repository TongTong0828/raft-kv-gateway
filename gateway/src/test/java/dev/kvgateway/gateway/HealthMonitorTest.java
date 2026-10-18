package dev.kvgateway.gateway;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class HealthMonitorTest {
  @Test
  void asynchronousProbesEventuallyReachEveryEndpointBeyondTheGlobalLimit() {
    var seen = java.util.concurrent.ConcurrentHashMap.<String>newKeySet();
    var pending = new java.util.concurrent.ConcurrentLinkedQueue<CompletableFuture<Boolean>>();
    var wanted = new HashSet<String>();
    for (int i = 0; i < 70; i++) wanted.add("endpoint" + i + ":80");
    try (var monitor =
        new HealthMonitor(
            endpoint -> {
              seen.add(endpoint);
              var future = new CompletableFuture<Boolean>();
              pending.add(future);
              return future;
            })) {
      monitor.reconcile(wanted);
      for (int round = 0; round < 3; round++) {
        for (int i = pending.size(); i > 0; i--) pending.remove().complete(true);
        monitor.refresh();
      }
      assertEquals(70, seen.size(), "a stable map traversal must not starve later endpoints");
    }
  }

  @Test
  void retiredRequestCannotPenalizeRecreatedEndpoint() {
    try (var monitor = new HealthMonitor(e -> CompletableFuture.completedFuture(true))) {
      monitor.reconcile(Set.of("a:80"));
      Object old = monitor.generation("a:80");
      monitor.reconcile(Set.of());
      monitor.reconcile(Set.of("a:80"));
      for (int i = 0; i < 3; i++) monitor.failure("a:80", old);
      assertTrue(monitor.healthy("a:80"));
    }
  }

  @Test
  void oldProbeCannotReviveRemovedAndRecreatedEndpoint() {
    var old = new CompletableFuture<Boolean>();
    var fresh = new CompletableFuture<Boolean>();
    var queue = new java.util.concurrent.ConcurrentLinkedQueue<>(List.of(old, fresh));
    try (var monitor = new HealthMonitor(e -> queue.remove())) {
      monitor.reconcile(Set.of("a:80"));
      monitor.reconcile(Set.of());
      monitor.reconcile(Set.of("a:80"));
      old.complete(true);
      assertFalse(monitor.healthy("a:80"));
      fresh.complete(true);
      assertTrue(monitor.healthy("a:80"));
    }
  }

  @Test
  void threeFailuresAndTwoProbeSuccessesAreRequiredForRecovery() {
    try (var monitor = new HealthMonitor(e -> CompletableFuture.completedFuture(true))) {
      monitor.reconcile(Set.of("a:80"));
      assertTrue(monitor.healthy("a:80"));
      monitor.failure("a:80");
      monitor.failure("a:80");
      assertTrue(monitor.healthy("a:80"));
      monitor.failure("a:80");
      assertFalse(monitor.healthy("a:80"));
      monitor.refresh();
      assertFalse(monitor.healthy("a:80"));
      monitor.refresh();
      assertTrue(monitor.healthy("a:80"));
    }
  }
}
