package dev.kvgateway.gateway;

import static org.junit.jupiter.api.Assertions.*;

import dev.kvgateway.api.*;
import io.netty.channel.Channel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.pool.ChannelPoolHandler;
import io.netty.channel.pool.SimpleChannelPool;
import io.netty.resolver.*;
import io.netty.util.concurrent.DefaultEventExecutor;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Promise;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;

@Timeout(20)
class UpstreamDispatcherTest {
  static ServiceSpec service(int limit, String... endpoints) {
    return ServiceSpecs.normalize(
        ServiceSpec.newBuilder()
            .setServiceId("orders")
            .setPathPrefix("/orders")
            .setMaxInflight(limit)
            .setRequestTimeoutMs(5000)
            .setMaxConnectionsPerEndpoint(1)
            .addAllEndpoints(List.of(endpoints))
            .build());
  }

  static long deadline() {
    return System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
  }

  static final class Fixture implements AutoCloseable {
    final NioEventLoopGroup group = new NioEventLoopGroup(1);
    final ResourceBudget budget = new ResourceBudget(256, 4, 65536);
    final PoolRegistry pools;
    final HealthMonitor health;
    final UpstreamDispatcher dispatcher;

    Fixture(AddressResolverGroup<?> resolver) {
      this(resolver, null);
    }

    Fixture(AddressResolverGroup<?> resolver, EventExecutor coordinator) {
      pools = new PoolRegistry(group, resolver, budget, new GatewayMetrics());
      health =
          new HealthMonitor(new HealthProbe(group, DefaultAddressResolverGroup.INSTANCE)::probe);
      dispatcher =
          new UpstreamDispatcher(
              coordinator == null ? group.next() : coordinator,
              pools,
              health,
              new ConcurrentHashMap<>());
    }

    void install(ServiceSpec... specs) throws Exception {
      pools.reconcile(List.of(specs));
      var endpoints = new HashSet<String>();
      for (var spec : specs) endpoints.addAll(spec.getEndpointsList());
      health.reconcile(endpoints);
      dispatcher.reconcile();
      for (var endpoint : endpoints) ControlWatcherTest.await(() -> health.healthy(endpoint));
      group.next().submit(() -> {}).get(2, TimeUnit.SECONDS);
    }

    void drained() throws Exception {
      ControlWatcherTest.await(
          () -> {
            try {
              return group
                  .next()
                  .submit(
                      () ->
                          dispatcher.queued() == 0
                              && dispatcher.reserved() == 0
                              && pools.acquiredSlots() == 0
                              && pools.borrowed() == 0
                              && pools.waiting() == 0
                              && pools.releasing() == 0)
                  .get(2, TimeUnit.SECONDS);
            } catch (Exception error) {
              throw new AssertionError(error);
            }
          });
    }

    public void close() throws Exception {
      dispatcher.close();
      health.close();
      pools.close();
      try {
        ControlWatcherTest.await(() -> budget.connections() == 0);
      } finally {
        group.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
      }
    }
  }

  static void cancelAndRelease(UpstreamDispatcher.Ticket ticket) {
    if (ticket == null) return;
    ticket
        .future()
        .addListener(
            f -> {
              if (f.isSuccess()) ((EndpointPool.Lease) f.getNow()).release(false);
            });
    ticket.future().cancel(false);
  }

  @RepeatedTest(20)
  void expiredHeadWakesLaterRevisionWhoseHealthyEndpointHasAnIdleSlot() throws Exception {
    try (var a = new BackendFixture();
        var b = new BackendFixture();
        var f = new Fixture(DefaultAddressResolverGroup.INSTANCE)) {
      var withB = service(10, a.endpoint(), b.endpoint());
      f.install(withB);
      var onlyA = service(10, a.endpoint());
      var held = f.dispatcher.acquire(onlyA, deadline()).future().get(2, TimeUnit.SECONDS);
      List<UpstreamDispatcher.Ticket> queued = List.of();
      try {
        queued =
            f.group
                .next()
                .submit(
                    () -> {
                      // Both endpoints are already healthy: setup cannot consume the head's
                      // deadline.
                      var head =
                          f.dispatcher.acquire(
                              onlyA, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(400));
                      var tail = f.dispatcher.acquire(withB, deadline());
                      try {
                        assertTrue(head.queued);
                        assertTrue(tail.queued);
                        assertFalse(head.future().isDone());
                        assertFalse(tail.future().isDone());
                        assertEquals(2, f.dispatcher.queued());
                        assertEquals(1, f.dispatcher.reserved());
                        assertEquals(0, f.pools.get("orders", b.endpoint()).borrowed.get());
                        return List.of(head, tail);
                      } catch (RuntimeException | AssertionError failure) {
                        cancelAndRelease(head);
                        cancelAndRelease(tail);
                        throw failure;
                      }
                    })
                .get(2, TimeUnit.SECONDS);
        var head = queued.get(0);
        var tail = queued.get(1);
        var failure =
            assertThrows(ExecutionException.class, () -> head.future().get(2, TimeUnit.SECONDS));
        assertInstanceOf(UpstreamDispatcher.RequestDeadlineExceeded.class, failure.getCause());
        var lease = tail.future().get(1, TimeUnit.SECONDS);
        assertEquals(b.endpoint(), tail.endpoint);
        lease.release(true);
      } finally {
        for (var ticket : queued) cancelAndRelease(ticket);
        held.release(true);
      }
      f.drained();
    }
  }

  @RepeatedTest(20)
  void recoveredHealthyIdleEndpointWakesQueuedWorkWithoutAnotherLeaseReturning() throws Exception {
    try (var a = new BackendFixture();
        var b = new BackendFixture();
        var f = new Fixture(DefaultAddressResolverGroup.INSTANCE)) {
      var spec = service(10, a.endpoint(), b.endpoint());
      f.install(spec);
      var heldTicket = f.dispatcher.acquire(spec, deadline());
      var held = heldTicket.future().get(2, TimeUnit.SECONDS);
      assertEquals(a.endpoint(), heldTicket.endpoint);
      UpstreamDispatcher.Ticket waiting = null;
      try {
        waiting =
            f.group
                .next()
                .submit(
                    () -> {
                      // Establish unhealthy B and real FIFO membership in one coordinator task.
                      for (int i = 0; i < 3; i++) f.health.failure(b.endpoint());
                      assertFalse(f.health.healthy(b.endpoint()));
                      var ticket = f.dispatcher.acquire(spec, deadline());
                      try {
                        assertTrue(ticket.queued);
                        assertFalse(ticket.future().isDone());
                        assertEquals(1, f.dispatcher.queued());
                        assertEquals(1, f.dispatcher.reserved());
                        return ticket;
                      } catch (RuntimeException | AssertionError failure) {
                        cancelAndRelease(ticket);
                        throw failure;
                      }
                    })
                .get(2, TimeUnit.SECONDS);
        ControlWatcherTest.await(
            () -> {
              f.health.refresh();
              return f.health.healthy(b.endpoint());
            });
        var recovered = waiting.future().get(1, TimeUnit.SECONDS);
        assertEquals(b.endpoint(), waiting.endpoint);
        recovered.release(true);
      } finally {
        cancelAndRelease(waiting);
        held.release(true);
      }
      f.drained();
    }
  }

  @Test
  void waitingGuardRemainsBoundedAcrossServicesAndReturnsAllCancelledCapacity() throws Exception {
    try (var a = new BackendFixture();
        var f = new Fixture(DefaultAddressResolverGroup.INSTANCE)) {
      var spec = service(10000, a.endpoint());
      var other = spec.toBuilder().setServiceId("payments").setPathPrefix("/payments").build();
      f.install(spec, other);
      var held = f.dispatcher.acquire(spec, deadline()).future().get(2, TimeUnit.SECONDS);
      var otherHeld = f.dispatcher.acquire(other, deadline()).future().get(2, TimeUnit.SECONDS);
      var waiting = new ArrayList<UpstreamDispatcher.Ticket>();
      try {
        for (int i = 0; i < 128; i++) {
          waiting.add(f.dispatcher.acquire(spec, deadline()));
          waiting.add(f.dispatcher.acquire(other, deadline()));
        }
        ControlWatcherTest.await(() -> f.dispatcher.queued() == 256);
        var rejected = f.dispatcher.acquire(spec, deadline());
        var failure =
            assertThrows(
                ExecutionException.class, () -> rejected.future().get(2, TimeUnit.SECONDS));
        assertEquals(
            RejectionReason.ADMISSION_LIMIT,
            assertInstanceOf(UpstreamDispatcher.Rejected.class, failure.getCause()).reason);
        assertEquals(2, f.dispatcher.reserved());
        assertEquals(256, f.dispatcher.queued());
      } finally {
        for (var ticket : waiting) ticket.future().cancel(false);
        held.release(true);
        otherHeld.release(true);
      }
      f.drained();
      var reusable = f.dispatcher.acquire(spec, deadline()).future().get(2, TimeUnit.SECONDS);
      assertTrue(reusable.channel.isActive());
      reusable.release(true);
      f.drained();
    }
  }

  @Test
  void retiringANonHeadRevisionFailsItWithoutWaitingForAnUnrelatedBusyHead() throws Exception {
    try (var a = new BackendFixture();
        var b = new BackendFixture();
        var f = new Fixture(DefaultAddressResolverGroup.INSTANCE)) {
      var withB = service(10, a.endpoint(), b.endpoint());
      f.install(withB);
      var onlyA = service(10, a.endpoint());
      var held = f.dispatcher.acquire(onlyA, deadline()).future().get(2, TimeUnit.SECONDS);
      var paused = new CountDownLatch(1);
      var resume = new CountDownLatch(1);
      f.group
          .next()
          .execute(
              () -> {
                paused.countDown();
                try {
                  if (!resume.await(5, TimeUnit.SECONDS))
                    throw new AssertionError("test did not resume the dispatcher");
                } catch (InterruptedException error) {
                  Thread.currentThread().interrupt();
                  throw new AssertionError(error);
                }
              });
      UpstreamDispatcher.Ticket head = null, tail = null;
      try {
        assertTrue(paused.await(2, TimeUnit.SECONDS));
        head = f.dispatcher.acquire(onlyA, deadline());
        tail = f.dispatcher.acquire(service(10, b.endpoint()), deadline());
        ControlWatcherTest.await(() -> f.dispatcher.queued() == 2);
        assertFalse(head.queued, "aggregate gauge includes work not yet enqueued");
        assertFalse(tail.queued, "tail is only in transit while its executor is paused");
        resume.countDown();
        var queuedHead = head;
        var queuedTail = tail;
        f.group
            .next()
            .submit(
                () -> {
                  // The gauge includes in-transit tickets; only the coordinator can prove FIFO
                  // membership before config/health removal. B is idle behind the busy A head.
                  assertTrue(queuedHead.queued);
                  assertTrue(queuedTail.queued);
                  assertFalse(queuedHead.future().isDone());
                  assertFalse(queuedTail.future().isDone());
                  assertEquals(2, f.dispatcher.queued());
                  assertEquals(1, f.dispatcher.reserved());
                  assertEquals(0, f.pools.get("orders", b.endpoint()).borrowed.get());
                })
            .get(2, TimeUnit.SECONDS);
        f.install(onlyA);
        var retiring = tail;
        var failure =
            assertThrows(
                ExecutionException.class, () -> retiring.future().get(1, TimeUnit.SECONDS));
        assertEquals("endpoint retired", failure.getCause().getMessage());
        assertFalse(head.future().isDone());
        assertEquals(0, b.requests.get());
      } finally {
        resume.countDown();
        cancelAndRelease(head);
        cancelAndRelease(tail);
        held.release(true);
      }
      f.drained();
    }
  }

  @Test
  void closingFailsQueuedWorkButKeepsTheBorrowedReservationUntilNativeReturn() throws Exception {
    try (var a = new BackendFixture();
        var f = new Fixture(DefaultAddressResolverGroup.INSTANCE)) {
      var spec = service(10, a.endpoint());
      f.install(spec);
      var held = f.dispatcher.acquire(spec, deadline()).future().get(2, TimeUnit.SECONDS);
      var waiting = f.dispatcher.acquire(spec, deadline());
      try {
        ControlWatcherTest.await(() -> f.dispatcher.queued() == 1);
        f.dispatcher.close();
        var failure =
            assertThrows(ExecutionException.class, () -> waiting.future().get(1, TimeUnit.SECONDS));
        assertEquals(
            RejectionReason.POOL_RETIRED,
            assertInstanceOf(UpstreamDispatcher.Rejected.class, failure.getCause()).reason);
        assertEquals(0, f.dispatcher.queued());
        assertEquals(1, f.dispatcher.reserved());
        var rejected = f.dispatcher.acquire(spec, deadline());
        assertTrue(rejected.future().isDone());
        assertEquals(
            RejectionReason.POOL_RETIRED,
            assertInstanceOf(UpstreamDispatcher.Rejected.class, rejected.future().cause()).reason);
      } finally {
        held.release(false);
      }
      f.drained();
    }
  }

  @Test
  void retirementRacingNativeSuccessKeepsReservationUntilTheLateLeaseActuallyReturns()
      throws Exception {
    var coordinator = new DefaultEventExecutor();
    var acquired = new CountDownLatch(1);
    var deliverSuccess = new CountDownLatch(1);
    var returningBlocked = new CountDownLatch(1);
    var allowReturn = new CountDownLatch(1);
    try (var a = new BackendFixture();
        var f = new Fixture(DefaultAddressResolverGroup.INSTANCE, coordinator)) {
      var spec = service(10, a.endpoint());
      f.install(spec);
      var pool = f.pools.get("orders", a.endpoint());
      var poolField = EndpointPool.class.getDeclaredField("pool");
      poolField.setAccessible(true);
      var nativePool = (SimpleChannelPool) poolField.get(pool);
      var handlerField = SimpleChannelPool.class.getDeclaredField("handler");
      handlerField.setAccessible(true);
      var original = (ChannelPoolHandler) handlerField.get(nativePool);
      handlerField.set(
          nativePool,
          new ChannelPoolHandler() {
            public void channelCreated(Channel channel) throws Exception {
              original.channelCreated(channel);
            }

            public void channelReleased(Channel channel) throws Exception {
              original.channelReleased(channel);
            }

            public void channelAcquired(Channel channel) throws Exception {
              original.channelAcquired(channel);
              acquired.countDown();
              if (!deliverSuccess.await(5, TimeUnit.SECONDS))
                throw new AssertionError("test did not release native acquire delivery");
            }
          });
      var ticket = f.dispatcher.acquire(spec, deadline());
      try {
        assertTrue(acquired.await(2, TimeUnit.SECONDS));
        // Native success will be published in this callback before closeAsync can execute.
        pool.close();
        f.group
            .next()
            .execute(
                () -> {
                  returningBlocked.countDown();
                  try {
                    if (!allowReturn.await(5, TimeUnit.SECONDS))
                      throw new AssertionError("test did not unblock native lease return");
                  } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(error);
                  }
                });
        deliverSuccess.countDown();
        assertTrue(returningBlocked.await(2, TimeUnit.SECONDS));
        coordinator.submit(() -> {}).get(2, TimeUnit.SECONDS);
        assertEquals(1, pool.borrowed.get());
        assertEquals(1, pool.releasing.get());
        assertFalse(ticket.future().isDone(), "retirement failure must await native lease return");
        assertEquals(1, f.dispatcher.reserved());
      } finally {
        deliverSuccess.countDown();
        allowReturn.countDown();
      }
      var failure =
          assertThrows(ExecutionException.class, () -> ticket.future().get(2, TimeUnit.SECONDS));
      assertEquals("endpoint retired", failure.getCause().getMessage());
      f.drained();
      assertEquals(0, f.budget.connections());
      assertEquals(0, a.requests.get());
    } finally {
      deliverSuccess.countDown();
      allowReturn.countDown();
      coordinator.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
    }
  }

  static final class GatedResolver extends AddressResolverGroup<InetSocketAddress> {
    final CountDownLatch started = new CountDownLatch(1);
    volatile Promise<InetSocketAddress> pending;
    volatile InetSocketAddress address;
    final AtomicBoolean open = new AtomicBoolean();

    protected AddressResolver<InetSocketAddress> newResolver(EventExecutor executor) {
      return new AbstractAddressResolver<InetSocketAddress>(executor, InetSocketAddress.class) {
        protected boolean doIsResolved(InetSocketAddress candidate) {
          return false;
        }

        protected void doResolve(InetSocketAddress candidate, Promise<InetSocketAddress> promise) {
          address = new InetSocketAddress("127.0.0.1", candidate.getPort());
          pending = promise;
          if (open.get()) promise.trySuccess(address);
          started.countDown();
        }

        protected void doResolveAll(
            InetSocketAddress candidate, Promise<List<InetSocketAddress>> promise) {
          promise.setSuccess(List.of(new InetSocketAddress("127.0.0.1", candidate.getPort())));
        }
      };
    }

    void release() {
      open.set(true);
      var promise = pending;
      if (promise != null) promise.trySuccess(address);
    }
  }

  @Test
  void cancellingDispatchedConnectKeepsItsReservationUntilTheLateNativeLeaseCloses()
      throws Exception {
    try (var a = new BackendFixture();
        var resolver = new GatedResolver();
        var f = new Fixture(resolver)) {
      var spec = service(10, a.endpoint());
      f.install(spec);
      var ticket = f.dispatcher.acquire(spec, deadline());
      try {
        assertTrue(resolver.started.await(2, TimeUnit.SECONDS));
        assertTrue(ticket.future().cancel(false));
        f.group.next().submit(() -> {}).get(2, TimeUnit.SECONDS);
        assertEquals(1, f.dispatcher.reserved());
        assertEquals(1, f.pools.waiting(), "native acquisition must survive outer cancellation");
      } finally {
        resolver.release();
      }
      f.drained();
      assertEquals(0, f.budget.connections(), "the cancelled late connection is not reused");
      var lease = f.dispatcher.acquire(spec, deadline()).future().get(2, TimeUnit.SECONDS);
      assertTrue(lease.channel.isActive());
      lease.release(true);
      f.drained();
    }
  }
}
