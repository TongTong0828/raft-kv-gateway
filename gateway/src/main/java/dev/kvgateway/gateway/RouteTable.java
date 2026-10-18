package dev.kvgateway.gateway;

import dev.kvgateway.api.*;
import java.util.*;

public final class RouteTable {
  private final ConfigSnapshot snapshot;
  private final List<ServiceSpec> routes;

  private RouteTable(ConfigSnapshot snapshot, List<ServiceSpec> routes) {
    this.snapshot = snapshot;
    this.routes = routes;
  }

  public static RouteTable from(ConfigSnapshot snapshot) {
    if (snapshot.getSerializedSize() > 4 * 1024 * 1024 || snapshot.getRevision() < 0)
      throw new IllegalArgumentException("invalid snapshot size/revision");
    var normalized = snapshot.getServicesList().stream().map(ServiceSpecs::normalize).toList();
    ServiceSpecs.validateAll(normalized);
    var sorted =
        normalized.stream()
            .sorted(
                Comparator.comparingInt((ServiceSpec s) -> s.getPathPrefix().length()).reversed())
            .toList();
    return new RouteTable(
        snapshot.toBuilder().clearServices().addAllServices(normalized).build(), sorted);
  }

  public ServiceSpec match(String uri) {
    var path = uri.split("\\?", 2)[0];
    for (var s : routes) {
      var p = s.getPathPrefix();
      if (p.equals("/") || path.equals(p) || path.startsWith(p + "/")) return s;
    }
    return null;
  }

  public ConfigSnapshot snapshot() {
    return snapshot;
  }
}
