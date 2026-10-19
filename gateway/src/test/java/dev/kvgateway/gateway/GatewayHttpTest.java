package dev.kvgateway.gateway;

import static org.junit.jupiter.api.Assertions.*;

import dev.kvgateway.api.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;

class GatewayHttpTest {
  BackendFixture backend;
  GatewayServer gateway;
  HttpClient client;

  @BeforeEach
  void start() throws Exception {
    backend = new BackendFixture();
    gateway =
        assertDoesNotThrow(
            () ->
                GatewayServer.start(
                    new Settings.Gateway(Settings.DEFAULT_GROUP, List.of("127.0.0.1:1"), 0, 0)));
    var service =
        ServiceSpec.newBuilder()
            .setServiceId("orders")
            .setPathPrefix("/orders")
            .addEndpoints(backend.endpoint())
            .setMaxConnectionsPerEndpoint(1)
            .setMaxInflight(1)
            .build();
    long generation = gateway.routes.beginSubscription();
    gateway.routes.install(
        RouteManagerTest.config(
            1, service, service.toBuilder().setServiceId("other").setPathPrefix("/other").build()),
        generation);
    client =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(2))
            .build();
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
    while (System.nanoTime() < end) {
      if (get("/orders/ready").statusCode() == 200) return;
      Thread.sleep(20);
    }
    fail("backend not healthy through gateway");
  }

  @AfterEach
  void stop() throws Exception {
    if (gateway != null) gateway.close();
    if (backend != null) backend.close();
    if (client != null) client.close();
  }

  HttpResponse<String> get(String path) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + gateway.port() + path))
            .timeout(Duration.ofSeconds(4))
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  String raw(String text) throws Exception {
    try (var socket = new Socket("127.0.0.1", gateway.port())) {
      socket.setSoTimeout(4000);
      socket.getOutputStream().write(text.getBytes(StandardCharsets.US_ASCII));
      socket.getOutputStream().flush();
      return new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
    }
  }

  @Test
  void keepAliveReusesUpstreamAndHeadHasNoBody() throws Exception {
    backend.businessPorts.clear();
    assertEquals("payload", get("/orders/a").body());
    assertEquals("payload", get("/orders/b").body());
    assertEquals(1, backend.businessPorts.size());
    var head =
        client.send(
            HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + gateway.port() + "/orders/head"))
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, head.statusCode());
    assertEquals("", head.body());
    assertEquals("7", head.headers().firstValue("content-length").orElseThrow());
    assertEquals("payload", get("/orders/after-head").body());
  }

  @Test
  void rejectsAmbiguousFramingAndOversizedBodyBeforeReceivingIt() throws Exception {
    assertTrue(
        raw("POST /orders HTTP/1.1\r\n"
                + "Host: test\r\n"
                + "Content-Length: 0\r\n"
                + "Transfer-Encoding: chunked\r\n"
                + "Connection: close\r\n\r\n"
                + "0\r\n\r\n")
            .startsWith("HTTP/1.1 400"));
    assertTrue(
        raw("POST /orders HTTP/1.1\r\n"
                + "Host: test\r\n"
                + "Content-Length: 0\r\n"
                + "Content-Length: 0\r\n"
                + "Connection: close\r\n\r\n")
            .startsWith("HTTP/1.1 400"));
    assertTrue(
        raw("POST /orders HTTP/1.1\r\nHost: test\r\nContent-Length: 1048577\r\n\r\n")
            .startsWith("HTTP/1.1 413"));
    assertTrue(
        raw("POST /orders HTTP/1.1\r\n"
                + "Host: test\r\n"
                + "Content-Length: 1\r\n"
                + "Expect: 100-continue\r\n\r\n")
            .startsWith("HTTP/1.1 417"));
  }

  @Test
  void chunkedInputAndHopHeadersAreHandledCorrectly() throws Exception {
    var response =
        raw(
            "POST /orders HTTP/1.1\r\n"
                + "Host: test\r\n"
                + "Transfer-Encoding: chunked\r\n"
                + "Connection: close, X-Remove\r\n"
                + "X-Remove: secret\r\n\r\n"
                + "3\r\n"
                + "abc\r\n"
                + "0\r\n\r\n");
    assertTrue(response.startsWith("HTTP/1.1 200"));
    assertTrue(response.endsWith("payload"));
    assertEquals("abc", backend.lastBody);
    assertNull(backend.lastRemoved);
    assertFalse(response.toLowerCase(Locale.ROOT).contains("x-backend-hop"));
  }

  @Test
  void saturationIsIsolatedAndPermitsReturnAfterCancellation() throws Exception {
    int before = backend.requests.get();
    var request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + gateway.port() + "/orders/slow"))
            .GET()
            .build();
    var pending = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (backend.requests.get() == before && System.nanoTime() < end) Thread.sleep(5);
    var overloaded = get("/orders/overloaded");
    assertEquals(503, overloaded.statusCode());
    assertEquals(
        "admission_limit",
        overloaded.headers().firstValue("X-Gateway-Rejection").orElse("missing"));
    assertTrue(
        gateway
            .metrics
            .render(gateway.budget, gateway.routes, gateway.health)
            .contains("gateway_rejections_total{reason=\"admission_limit\"} 1\n"));
    assertEquals(200, get("/other/ok").statusCode());
    pending.cancel(true);
    end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
    while (gateway.budget.inflight() != 0 && System.nanoTime() < end) Thread.sleep(10);
    assertEquals(0, gateway.budget.inflight());
    assertEquals(0, gateway.budget.bodyBytes());
    assertEquals(200, get("/orders/after-cancel").statusCode());
  }

  @Test
  void distinguishesNoHealthyEndpointMissingPoolAndRetiredPool() throws Exception {
    var pool = gateway.pools.get("orders", backend.endpoint());
    pool.close();
    var retired = get("/orders/retired");
    assertEquals(503, retired.statusCode());
    assertEquals(
        "pool_retired", retired.headers().firstValue("X-Gateway-Rejection").orElse("missing"));
    var diagnostics = gateway.metrics.render(gateway.budget, gateway.routes, gateway.health);
    assertTrue(diagnostics.contains("causeMessage=endpoint retired"));
    assertTrue(diagnostics.contains("retired=true"));
    gateway.pools.close();
    var missing = get("/orders/missing-pool");
    assertEquals(
        "pool_unavailable", missing.headers().firstValue("X-Gateway-Rejection").orElse("missing"));
    gateway.health.reconcile(Set.of());
    var unhealthy = get("/orders/unhealthy");
    assertEquals(
        "no_healthy_endpoint",
        unhealthy.headers().firstValue("X-Gateway-Rejection").orElse("missing"));
  }

  @Test
  void repeatedPoolFailuresKeepOnlyBoundedRecentDiagnosticSamples() throws Exception {
    gateway.pools.get("orders", backend.endpoint()).close();
    for (int i = 0; i < 70; i++) {
      assertEquals(
          "pool_retired",
          get("/orders/retired").headers().firstValue("X-Gateway-Rejection").orElse("missing"));
      ControlWatcherTest.await(() -> gateway.budget.inflight() == 0);
    }
    String diagnostics = gateway.metrics.render(gateway.budget, gateway.routes, gateway.health);
    assertEquals(
        64, diagnostics.lines().filter(line -> line.startsWith("# pool_failure ")).count());
    assertTrue(diagnostics.contains("gateway_rejections_total{reason=\"pool_retired\"} 70\n"));
  }

  @Test
  void bodyBudgetRejectionHasItsOwnReason() throws Exception {
    assertTrue(gateway.budget.reserveBytes(64 * 1024 * 1024));
    try {
      var response = get("/orders/no-body-space");
      assertEquals(503, response.statusCode());
      assertEquals(
          "body_budget", response.headers().firstValue("X-Gateway-Rejection").orElse("missing"));
    } finally {
      gateway.budget.releaseBytes(64 * 1024 * 1024);
    }
  }

  @Test
  void backendCannotSpoofGatewayRejectionHeader() throws Exception {
    assertTrue(get("/orders/spoof").headers().firstValue("X-Gateway-Rejection").isEmpty());
  }

  private String adminMetrics() throws Exception {
    var field = GatewayServer.class.getDeclaredField("admin");
    field.setAccessible(true);
    var admin = (com.sun.net.httpserver.HttpServer) field.get(gateway);
    return client
        .send(
            HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + admin.getAddress().getPort() + "/metrics"))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString())
        .body();
  }

  @Test
  void metricsExposeNativeSlotsUntilAnUpstreamLeaseActuallyReturns() throws Exception {
    ControlWatcherTest.await(() -> gateway.budget.inflight() == 0);
    var lease = gateway.pools.get("orders", backend.endpoint()).acquire().get(2, TimeUnit.SECONDS);
    try {
      assertTrue(adminMetrics().contains("gateway_pool_acquired_slots 1\n"));
      assertTrue(adminMetrics().contains("gateway_pool_releasing 0\n"));
    } finally {
      lease.release(true);
    }
    ControlWatcherTest.await(
        () -> {
          try {
            return adminMetrics().contains("gateway_pool_acquired_slots 0\n");
          } catch (Exception e) {
            return false;
          }
        });
  }

  @Test
  void onePostAttemptAndPipeliningCannotMixResponses() throws Exception {
    int before = backend.requests.get();
    var response =
        client.send(
            HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + gateway.port() + "/orders/drop"))
                .POST(HttpRequest.BodyPublishers.ofString("write"))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(502, response.statusCode());
    assertEquals(before + 1, backend.requests.get());
    String pipelined =
        raw(
            "GET /orders/slow HTTP/1.1\r\n"
                + "Host: test\r\n\r\n"
                + "GET /orders/other HTTP/1.1\r\n"
                + "Host: test\r\n\r\n");
    assertFalse(pipelined.contains("payloadpayload"));
  }

  @Test
  void partialBodyCancellationAndLocalTimeoutDoNotPoisonEndpoint() throws Exception {
    try (var socket = new Socket("127.0.0.1", gateway.port())) {
      socket
          .getOutputStream()
          .write(
              "POST /orders/body HTTP/1.1\r\nHost: test\r\nContent-Length: 1048576\r\n\r\n"
                  .getBytes(StandardCharsets.US_ASCII));
      socket.getOutputStream().write(new byte[65536]);
      socket.getOutputStream().flush();
      long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
      while (gateway.budget.bodyBytes() == 0 && System.nanoTime() < end) Thread.sleep(5);
      assertTrue(gateway.budget.bodyBytes() > 0);
    }
    ControlWatcherTest.await(() -> gateway.budget.inflight() == 0);
    assertEquals(0, gateway.budget.bodyBytes());
    assertTrue(gateway.health.healthy(backend.endpoint()));
    var service =
        gateway.routes.current().snapshot().getServices(0).toBuilder()
            .setRequestTimeoutMs(50)
            .build();
    long generation = gateway.routes.beginSubscription();
    gateway.routes.install(RouteManagerTest.config(2, service), generation);
    assertEquals(504, get("/orders/slow").statusCode());
    assertTrue(gateway.health.healthy(backend.endpoint()));
    assertEquals(0, gateway.budget.inflight());
    gateway.routes.install(
        RouteManagerTest.config(3, service.toBuilder().setRequestTimeoutMs(2000).build()),
        generation);
    assertEquals(200, get("/orders/slow").statusCode());
  }

  @Test
  void actualUpstreamResponseTimeoutsCountAsEndpointFailures() throws Exception {
    var service =
        gateway.routes.current().snapshot().getServices(0).toBuilder()
            .setRequestTimeoutMs(30)
            .build();
    gateway.routes.install(RouteManagerTest.config(2, service), gateway.routes.beginSubscription());
    for (int i = 0; i < 3; i++) assertEquals(504, get("/orders/slow").statusCode());
    assertFalse(gateway.health.healthy(backend.endpoint()));
  }
}
