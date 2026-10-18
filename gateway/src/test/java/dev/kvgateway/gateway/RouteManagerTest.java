package dev.kvgateway.gateway;

import static org.junit.jupiter.api.Assertions.*;

import dev.kvgateway.api.*;
import org.junit.jupiter.api.Test;

class RouteManagerTest {
  static ServiceSpec spec(String id, String prefix) {
    return ServiceSpec.newBuilder()
        .setServiceId(id)
        .setPathPrefix(prefix)
        .addEndpoints("backend:8080")
        .build();
  }

  static ConfigSnapshot config(long revision, ServiceSpec... specs) {
    return ConfigSnapshot.newBuilder()
        .setGroupId(Settings.DEFAULT_GROUP)
        .setRevision(revision)
        .addAllServices(java.util.List.of(specs))
        .build();
  }

  @Test
  void segmentPrefixAndLongestRouteAreStable() {
    var t =
        RouteTable.from(
            config(
                1, spec("root", "/"), spec("orders", "/orders"), spec("detail", "/orders/detail")));
    assertEquals("orders", t.match("/orders/123?x=1").getServiceId());
    assertEquals("root", t.match("/orders2").getServiceId());
    assertEquals("detail", t.match("/orders/detail/1").getServiceId());
    assertEquals("root", t.match("/orders%2f123").getServiceId());
  }

  @Test
  void staleRevisionOrOldSubscriptionCannotRollBack() {
    var m = new RouteManager(Settings.DEFAULT_GROUP);
    long first = m.beginSubscription();
    assertTrue(m.install(config(12, spec("new", "/new")), first));
    assertFalse(m.install(config(11, spec("old", "/old")), first));
    long second = m.beginSubscription();
    assertFalse(m.install(config(13, spec("old", "/old")), first));
    assertTrue(m.install(config(14, spec("next", "/next")), second));
    assertEquals(14, m.current().snapshot().getRevision());
    assertThrows(
        IllegalArgumentException.class,
        () -> m.install(config(15, spec("duplicate", "/same"), spec("other", "/same")), second));
    assertEquals(14, m.current().snapshot().getRevision());
  }
}
