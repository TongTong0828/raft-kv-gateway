package dev.kvgateway.gateway;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

final class GatewayMetrics {
  private final double[] bounds = {
    .001, .005, .01, .025, .05, .1, .25, .5, 1, 2, 5, 10, 30, Double.POSITIVE_INFINITY
  };
  private final LongAdder[] buckets = new LongAdder[bounds.length];
  private final ConcurrentHashMap<String, LongAdder> rejects = new ConcurrentHashMap<>();
  private final LongAdder[] codes = new LongAdder[6];
  private final LongAdder requests = new LongAdder(), nanos = new LongAdder();

  GatewayMetrics() {
    for (int i = 0; i < codes.length; i++) codes[i] = new LongAdder();
    for (int i = 0; i < buckets.length; i++) buckets[i] = new LongAdder();
  }

  void rejected(String service) {
    rejects.computeIfAbsent(service, k -> new LongAdder()).increment();
  }

  void reconcile(Set<String> services) {
    rejects.keySet().retainAll(services);
  }

  void record(int status, long elapsed) {
    requests.increment();
    nanos.add(elapsed);
    if (status / 100 < codes.length) codes[status / 100].increment();
    for (int i = 0; i < bounds.length; i++) if (elapsed / 1e9 <= bounds[i]) buckets[i].increment();
  }

  String render(ResourceBudget budget, RouteManager routes, HealthMonitor health) {
    var table = routes.current();
    var s =
        new StringBuilder(
            "gateway_requests_total "
                + requests.sum()
                + "\ngateway_request_seconds_sum "
                + nanos.sum() / 1e9
                + "\n");
    for (int i = 1; i < 6; i++)
      s.append("gateway_responses_total{class=\"")
          .append(i)
          .append("xx\"} ")
          .append(codes[i].sum())
          .append('\n');
    s.append("gateway_request_seconds_count ").append(requests.sum()).append('\n');
    for (int i = 0; i < bounds.length; i++)
      s.append("gateway_request_seconds_bucket{le=\"")
          .append(Double.isInfinite(bounds[i]) ? "+Inf" : bounds[i])
          .append("\"} ")
          .append(buckets[i].sum())
          .append('\n');
    rejects.forEach(
        (id, n) ->
            s.append("gateway_service_rejected_total{service=\"")
                .append(id)
                .append("\"} ")
                .append(n.sum())
                .append('\n'));
    s.append("gateway_inflight ")
        .append(budget.inflight())
        .append("\ngateway_upstream_connections ")
        .append(budget.connections())
        .append("\ngateway_body_bytes ")
        .append(budget.bodyBytes())
        .append("\ngateway_config_revision ")
        .append(table == null ? 0 : table.snapshot().getRevision())
        .append("\ngateway_healthy_endpoints ")
        .append(health.healthyCount())
        .append('\n');
    budget
        .serviceInflight()
        .forEach(
            (id, n) ->
                s.append("gateway_service_inflight{service=\"")
                    .append(id)
                    .append("\"} ")
                    .append(n)
                    .append('\n'));
    return s.toString();
  }
}
