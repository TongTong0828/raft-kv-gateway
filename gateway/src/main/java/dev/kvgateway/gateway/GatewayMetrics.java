package dev.kvgateway.gateway;
import java.util.concurrent.atomic.LongAdder;
final class GatewayMetrics {
  private final LongAdder[] codes=new LongAdder[6];private final LongAdder requests=new LongAdder(),nanos=new LongAdder();
  GatewayMetrics(){for(int i=0;i<codes.length;i++)codes[i]=new LongAdder();}
  void record(int status,long elapsed){requests.increment();nanos.add(elapsed);if(status/100<codes.length)codes[status/100].increment();}
  String render(ResourceBudget budget,RouteManager routes,HealthMonitor health){
    var table=routes.current();var s=new StringBuilder("gateway_requests_total "+requests.sum()+"\ngateway_request_seconds_sum "+nanos.sum()/1e9+"\n");
    for(int i=1;i<6;i++)s.append("gateway_responses_total{class=\"").append(i).append("xx\"} ").append(codes[i].sum()).append('\n');
    s.append("gateway_inflight ").append(budget.inflight()).append("\ngateway_upstream_connections ").append(budget.connections()).append("\ngateway_body_bytes ").append(budget.bodyBytes()).append("\ngateway_config_revision ").append(table==null?0:table.snapshot().getRevision()).append("\ngateway_healthy_endpoints ").append(health.healthyCount()).append('\n');
    budget.serviceInflight().forEach((id,n)->s.append("gateway_service_inflight{service=\"").append(id).append("\"} ").append(n).append('\n'));return s.toString();
  }
}
