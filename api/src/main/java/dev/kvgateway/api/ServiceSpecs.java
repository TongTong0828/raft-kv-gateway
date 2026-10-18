package dev.kvgateway.api;

import java.util.*;

public final class ServiceSpecs {
  private ServiceSpecs() {}

  public static ServiceSpec normalize(ServiceSpec spec) {
    if (!spec.getServiceId().matches("[a-z][a-z0-9-]{0,62}"))
      throw new IllegalArgumentException("invalid service_id");
    if (!spec.getPathPrefix().matches("/(?:[A-Za-z0-9._~-]+(?:/[A-Za-z0-9._~-]+)*)?"))
      throw new IllegalArgumentException("invalid path_prefix");
    if (spec.getEndpointsCount() > 32) throw new IllegalArgumentException("too many endpoints");
    var b = spec.toBuilder().clearEndpoints();
    var unique = new HashSet<String>();
    for (var value : spec.getEndpointsList()) {
      var endpoint = endpoint(value);
      if (!unique.add(endpoint)) throw new IllegalArgumentException("duplicate endpoint");
      b.addEndpoints(endpoint);
    }
    b.setRequestTimeoutMs(
        range(
            spec.hasRequestTimeoutMs() ? spec.getRequestTimeoutMs() : 2000,
            10,
            30000,
            "request_timeout_ms"));
    b.setMaxInflight(
        range(spec.hasMaxInflight() ? spec.getMaxInflight() : 100, 1, 10000, "max_inflight"));
    b.setMaxConnectionsPerEndpoint(
        range(
            spec.hasMaxConnectionsPerEndpoint() ? spec.getMaxConnectionsPerEndpoint() : 16,
            1,
            256,
            "max_connections_per_endpoint"));
    return b.build();
  }

  private static int range(int n, int min, int max, String name) {
    if (n < min || n > max)
      throw new IllegalArgumentException(name + " outside [" + min + "," + max + "]");
    return n;
  }

  public static String endpoint(String value) {
    if (value == null || value.length() > 260)
      throw new IllegalArgumentException("invalid endpoint");
    var p = value.split(":", -1);
    if (p.length != 2 || !p[1].matches("[0-9]{1,5}"))
      throw new IllegalArgumentException("expected host:port");
    range(Integer.parseInt(p[1]), 1, 65535, "port");
    var host = p[0].toLowerCase(Locale.ROOT);
    if (host.isEmpty() || host.length() > 253)
      throw new IllegalArgumentException("invalid hostname");
    for (var label : host.split("\\.", -1))
      if (!label.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?"))
        throw new IllegalArgumentException("invalid hostname");
    if (host.matches("[0-9.]+")) {
      var octets = host.split("\\.");
      if (octets.length != 4) throw new IllegalArgumentException("invalid IPv4");
      for (var x : octets) range(Integer.parseInt(x), 0, 255, "IPv4 octet");
    }
    return host + ":" + Integer.parseInt(p[1]);
  }

  public static void validateAll(Collection<ServiceSpec> specs) {
    if (specs.size() > 128) throw new IllegalArgumentException("too many services");
    var ids = new HashSet<String>();
    var paths = new HashSet<String>();
    for (var s : specs) {
      var n = normalize(s);
      if (!ids.add(n.getServiceId()) || !paths.add(n.getPathPrefix()))
        throw new IllegalArgumentException("duplicate service or path prefix");
    }
  }
}
