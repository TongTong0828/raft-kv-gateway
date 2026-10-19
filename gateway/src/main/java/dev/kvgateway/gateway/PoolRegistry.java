package dev.kvgateway.gateway;

import dev.kvgateway.api.ServiceSpec;
import io.netty.channel.EventLoopGroup;
import io.netty.resolver.AddressResolverGroup;
import java.util.*;
import java.util.concurrent.*;

final class PoolRegistry implements AutoCloseable {
  private final ConcurrentHashMap<String, EndpointPool> pools = new ConcurrentHashMap<>();
  private final EventLoopGroup group;
  private final AddressResolverGroup<?> resolver;
  private final ResourceBudget budget;
  private final GatewayMetrics metrics;

  PoolRegistry(
      EventLoopGroup group,
      AddressResolverGroup<?> resolver,
      ResourceBudget budget,
      GatewayMetrics metrics) {
    this.group = group;
    this.resolver = resolver;
    this.budget = budget;
    this.metrics = metrics;
  }

  private static String key(String service, String endpoint) {
    return service + "|" + endpoint;
  }

  synchronized void reconcile(List<ServiceSpec> specs) {
    var wanted = new HashSet<String>();
    for (var s : specs)
      for (var e : s.getEndpointsList()) {
        var key = key(s.getServiceId(), e);
        wanted.add(key);
        pools.compute(
            key,
            (k, old) -> {
              if (old != null && old.limit == s.getMaxConnectionsPerEndpoint()) return old;
              if (old != null) old.close();
              return new EndpointPool(
                  e,
                  s.getMaxConnectionsPerEndpoint(),
                  group,
                  resolver,
                  budget,
                  failure -> metrics.poolFailure(e, failure));
            });
      }
    pools.forEach(
        (k, v) -> {
          if (!wanted.contains(k) && pools.remove(k, v)) v.close();
        });
  }

  EndpointPool get(String service, String endpoint) {
    return pools.get(key(service, endpoint));
  }

  int borrowed() {
    return pools.values().stream().mapToInt(p -> p.borrowed.get()).sum();
  }

  int waiting() {
    return pools.values().stream().mapToInt(p -> p.waiting.get()).sum();
  }

  int acquiredSlots() {
    return pools.values().stream().mapToInt(EndpointPool::acquiredSlots).sum();
  }

  int releasing() {
    return pools.values().stream().mapToInt(p -> p.releasing.get()).sum();
  }

  public void close() {
    pools.values().forEach(EndpointPool::close);
    pools.clear();
  }
}
