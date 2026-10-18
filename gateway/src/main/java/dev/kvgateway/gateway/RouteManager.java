package dev.kvgateway.gateway;

import dev.kvgateway.api.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public final class RouteManager {
  private final String group;
  private long generation;
  private final AtomicReference<RouteTable> current = new AtomicReference<>();
  private Consumer<RouteTable> listener = t -> {};

  public RouteManager(String group) {
    this.group = group;
  }

  public synchronized void onInstall(Consumer<RouteTable> listener) {
    this.listener = listener;
  }

  public synchronized long beginSubscription() {
    return ++generation;
  }

  public synchronized boolean install(ConfigSnapshot snapshot, long subscriptionGeneration) {
    if (subscriptionGeneration != generation) return false;
    var old = current.get();
    if (old != null && snapshot.getRevision() <= old.snapshot().getRevision()) return false;
    if (!snapshot.getGroupId().equals(group))
      throw new IllegalArgumentException("unexpected control group");
    var table = RouteTable.from(snapshot);
    listener.accept(table);
    current.set(table);
    return true;
  }

  public RouteTable current() {
    return current.get();
  }
}
