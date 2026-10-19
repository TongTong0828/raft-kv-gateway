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

@Timeout(20)
class GatewayDispatchTest {
  BackendFixture first;
  BackendFixture second;
  GatewayServer gateway;
  HttpClient client;
  long revision;

  @BeforeEach
  void start() throws Exception {
    first = new BackendFixture();
    second = new BackendFixture();
    gateway =
        GatewayServer.start(
            new Settings.Gateway(Settings.DEFAULT_GROUP, List.of("127.0.0.1:1"), 0, 0));
    client =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(2))
            .build();
  }

  @AfterEach
  void stop() throws Exception {
    if (first != null && first.businessGate != null) first.businessGate.countDown();
    if (second != null && second.businessGate != null) second.businessGate.countDown();
    if (gateway != null) gateway.close();
    if (first != null) first.close();
    if (second != null) second.close();
    if (client != null) client.close();
  }

  ServiceSpec service(int limit, int timeout, String... endpoints) {
    return ServiceSpec.newBuilder()
        .setServiceId("orders")
        .setPathPrefix("/orders")
        .addAllEndpoints(List.of(endpoints))
        .setMaxInflight(limit)
        .setMaxConnectionsPerEndpoint(1)
        .setRequestTimeoutMs(timeout)
        .build();
  }

  void install(ServiceSpec... specs) throws Exception {
    gateway.routes.install(
        RouteManagerTest.config(++revision, specs), gateway.routes.beginSubscription());
    for (var spec : specs)
      for (var endpoint : spec.getEndpointsList())
        ControlWatcherTest.await(() -> gateway.health.healthy(endpoint));
  }

  CompletableFuture<HttpResponse<String>> request(String path, boolean post) {
    var builder =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + gateway.port() + path))
            .timeout(Duration.ofSeconds(8));
    if (post) builder.POST(HttpRequest.BodyPublishers.ofString("one-side-effect"));
    else builder.GET();
    return client.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  void drained() throws Exception {
    ControlWatcherTest.await(
        () ->
            gateway.budget.inflight() == 0
                && gateway.budget.bodyBytes() == 0
                && gateway.pools.borrowed() == 0
                && gateway.pools.waiting() == 0
                && gateway.pools.acquiredSlots() == 0
                && gateway.dispatcher.queued() == 0
                && gateway.dispatcher.reserved() == 0);
  }

  @Test
  void sixtyFourRequestBurstWaitsWithinServiceBudgetInsteadOfFillingNativePendingQueue()
      throws Exception {
    install(service(100, 5000, first.endpoint()));
    first.businessGate = new CountDownLatch(1);
    var burst = new ArrayList<CompletableFuture<HttpResponse<String>>>();
    for (int i = 0; i < 64; i++) burst.add(request("/orders/burst-" + i, false));
    ControlWatcherTest.await(
        () ->
            gateway.budget.inflight() == 64
                || burst.stream()
                    .anyMatch(
                        f ->
                            f.isDone()
                                && !f.isCompletedExceptionally()
                                && f.getNow(null).statusCode() == 503));
    assertTrue(
        burst.stream().noneMatch(CompletableFuture::isDone),
        "accepted burst must wait behind the held physical slot, rather than receive a local 503");
    assertEquals(64, gateway.budget.inflight());
    assertEquals(1, first.requests.get());
    first.businessGate.countDown();
    for (var response : burst) {
      assertEquals(200, response.get(6, TimeUnit.SECONDS).statusCode());
      assertEquals("payload", response.getNow(null).body());
    }
    assertEquals(64, first.requests.get());
    assertEquals(64, new HashSet<>(first.businessPaths).size());
    drained();
  }

  @Test
  void blockedHealthyBackendDoesNotQueueWorkWhileAnotherBackendHasAFreeSlot() throws Exception {
    install(service(100, 5000, first.endpoint(), second.endpoint()));
    first.businessGate = new CountDownLatch(1);
    var held = request("/orders/held", false);
    ControlWatcherTest.await(() -> first.requests.get() == 1);
    var fast = new ArrayList<CompletableFuture<HttpResponse<String>>>();
    for (int i = 0; i < 24; i++) fast.add(request("/orders/fast-" + i, false));
    for (var response : fast) assertEquals(200, response.get(3, TimeUnit.SECONDS).statusCode());
    assertFalse(held.isDone(), "the blocked backend was deliberately not released");
    assertEquals(1, first.requests.get());
    assertEquals(24, second.requests.get());
    first.businessGate.countDown();
    assertEquals(200, held.get(3, TimeUnit.SECONDS).statusCode());
    drained();
  }

  @Test
  void cancellingQueuedPostNeverForwardsItsBodyAndLeavesAReusableSlot() throws Exception {
    install(service(3, 5000, first.endpoint()));
    first.businessGate = new CountDownLatch(1);
    var held = request("/orders/held", false);
    ControlWatcherTest.await(() -> first.requests.get() == 1);
    try (var socket = new Socket("127.0.0.1", gateway.port())) {
      socket
          .getOutputStream()
          .write(
              ("POST /orders/cancelled-post HTTP/1.1\r\nHost: test\r\n"
                      + "Content-Length: 5\r\n\r\nwrite")
                  .getBytes(StandardCharsets.US_ASCII));
      socket.getOutputStream().flush();
      ControlWatcherTest.await(() -> gateway.budget.inflight() == 2);
    }
    ControlWatcherTest.await(() -> gateway.budget.inflight() == 1);
    first.businessGate.countDown();
    assertEquals(200, held.get(3, TimeUnit.SECONDS).statusCode());
    assertEquals(200, request("/orders/after-cancel", false).get(3, TimeUnit.SECONDS).statusCode());
    assertFalse(first.businessPaths.contains("/orders/cancelled-post"));
    drained();
  }

  @Test
  void queuedPostUsesItsRemainingTotalDeadlineAndIsNeverSentAfterItExpires() throws Exception {
    var original = service(3, 5000, first.endpoint());
    install(original);
    first.businessGate = new CountDownLatch(1);
    var held = request("/orders/held", false);
    ControlWatcherTest.await(() -> first.requests.get() == 1);
    install(original.toBuilder().setRequestTimeoutMs(250).build());
    var expired = request("/orders/expired-post", true).get(2, TimeUnit.SECONDS);
    assertEquals(
        504, expired.statusCode(), "shared waiting uses the request deadline, not pool timeout");
    assertEquals(1, first.requests.get());
    assertTrue(gateway.health.healthy(first.endpoint()));
    first.businessGate.countDown();
    assertEquals(200, held.get(3, TimeUnit.SECONDS).statusCode());
    assertEquals(
        200, request("/orders/after-timeout", false).get(3, TimeUnit.SECONDS).statusCode());
    assertFalse(first.businessPaths.contains("/orders/expired-post"));
    drained();
  }

  @Test
  void queuedRequestsStillConsumeServiceAdmissionAndDoNotBlockAnotherService() throws Exception {
    var orders = service(2, 5000, first.endpoint());
    var other =
        service(1, 5000, second.endpoint()).toBuilder()
            .setServiceId("other")
            .setPathPrefix("/other")
            .build();
    install(orders, other);
    first.businessGate = new CountDownLatch(1);
    var held = request("/orders/held", false);
    ControlWatcherTest.await(() -> first.requests.get() == 1);
    var queued = request("/orders/queued", false);
    ControlWatcherTest.await(() -> gateway.budget.inflight() == 2);
    var rejected = request("/orders/overload", false).get(2, TimeUnit.SECONDS);
    assertEquals(503, rejected.statusCode());
    assertEquals(
        "admission_limit", rejected.headers().firstValue("X-Gateway-Rejection").orElseThrow());
    assertEquals(200, request("/other/fast", false).get(2, TimeUnit.SECONDS).statusCode());
    first.businessGate.countDown();
    assertEquals(200, held.get(3, TimeUnit.SECONDS).statusCode());
    assertEquals(200, queued.get(3, TimeUnit.SECONDS).statusCode());
    drained();
  }

  @Test
  void retiringAnEndpointFailsItsQueuedPostWithoutSendingOrRetryingIt() throws Exception {
    install(service(3, 5000, first.endpoint()));
    first.businessGate = new CountDownLatch(1);
    var held = request("/orders/held", false);
    ControlWatcherTest.await(() -> first.requests.get() == 1);
    var queued = request("/orders/retired-post", true);
    ControlWatcherTest.await(() -> gateway.budget.inflight() == 2);
    install(service(3, 5000, second.endpoint()));
    var retired = queued.get(2, TimeUnit.SECONDS);
    assertEquals(503, retired.statusCode());
    assertEquals("pool_retired", retired.headers().firstValue("X-Gateway-Rejection").orElseThrow());
    first.businessGate.countDown();
    assertEquals(200, held.get(3, TimeUnit.SECONDS).statusCode());
    assertEquals(200, request("/orders/new-endpoint", false).get(3, TimeUnit.SECONDS).statusCode());
    assertFalse(first.businessPaths.contains("/orders/retired-post"));
    assertFalse(second.businessPaths.contains("/orders/retired-post"));
    drained();
  }
}
