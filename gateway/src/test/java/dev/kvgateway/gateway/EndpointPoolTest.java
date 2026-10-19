package dev.kvgateway.gateway;

import static org.junit.jupiter.api.Assertions.*;

import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.resolver.DefaultAddressResolverGroup;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class EndpointPoolTest {
  @Test
  void overflowAndCancelledWaitersDrainWithoutClosingThePoolAndARealLeaseCanBeReused()
      throws Exception {
    var group = new NioEventLoopGroup(1);
    var budget = new ResourceBudget(128, 1, 1024);
    var observations = new ConcurrentLinkedQueue<EndpointPool.Failure>();
    try (var backend = new BackendFixture();
        var pool =
            new EndpointPool(
                backend.endpoint(),
                1,
                group,
                DefaultAddressResolverGroup.INSTANCE,
                budget,
                observations::add)) {
      var held = pool.acquire().get(2, TimeUnit.SECONDS);
      var waiting = new java.util.ArrayList<io.netty.util.concurrent.Future<EndpointPool.Lease>>();
      group
          .next()
          .submit(
              () -> {
                try {
                  for (int i = 0; i < 32; i++) waiting.add(pool.acquire());
                  var rejected = pool.acquire();
                  assertTrue(rejected.isDone());
                  assertEquals(
                      RejectionReason.POOL_QUEUE_FULL,
                      RejectionReason.acquisition(rejected.cause()));
                  var snapshot =
                      observations.stream()
                          .filter(
                              f ->
                                  RejectionReason.acquisition(f.cause())
                                      == RejectionReason.POOL_QUEUE_FULL)
                          .findFirst()
                          .orElseThrow();
                  assertEquals(1, snapshot.acquired());
                  assertEquals(1, snapshot.borrowed());
                  assertEquals(32, snapshot.waiting());
                  assertEquals(0, snapshot.releasing());
                } finally {
                  for (var future : waiting) {
                    future.addListener(
                        done -> {
                          if (done.isSuccess()) future.getNow().release(false);
                        });
                    if (!future.isDone()) future.cancel(false);
                  }
                  held.release(true);
                }
              })
          .get(2, TimeUnit.SECONDS);
      ControlWatcherTest.await(
          () ->
              pool.acquiredSlots() == 0
                  && pool.borrowed.get() == 0
                  && pool.waiting.get() == 0
                  && pool.releasing.get() == 0);
      assertTrue(waiting.stream().allMatch(io.netty.util.concurrent.Future::isDone));
      var recovered = pool.acquire().get(2, TimeUnit.SECONDS);
      assertTrue(recovered.channel.isActive());
      recovered.release(true);
      ControlWatcherTest.await(
          () ->
              pool.acquiredSlots() == 0
                  && pool.borrowed.get() == 0
                  && pool.waiting.get() == 0
                  && pool.releasing.get() == 0);
      assertEquals(1, budget.connections());
    } finally {
      try {
        ControlWatcherTest.await(() -> budget.connections() == 0);
      } finally {
        group.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
      }
    }
    assertEquals(0, budget.connections());
  }

  @Test
  void idleConnectionsExpireWithoutAnotherAcquireAndBorrowedConnectionsDrain() throws Exception {
    var group = new NioEventLoopGroup(1);
    var budget = new ResourceBudget(2, 2, 1024);
    try (var backend = new BackendFixture();
        var pool =
            new EndpointPool(
                backend.endpoint(),
                1,
                group,
                DefaultAddressResolverGroup.INSTANCE,
                budget,
                30,
                60)) {
      var first = pool.acquire().get(2, TimeUnit.SECONDS);
      Thread.sleep(90);
      assertTrue(first.channel.isActive());
      first.release(true);
      ControlWatcherTest.await(() -> budget.connections() == 0);
      var second = pool.acquire().get(2, TimeUnit.SECONDS);
      second.release(true);
      ControlWatcherTest.await(() -> budget.connections() == 0);
    } finally {
      group.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
    }
  }

  @Test
  void forcedUnregisteredCloseReturnsPhysicalConnectionBudget() {
    var budget = new ResourceBudget(1, 1, 1);
    var permit = budget.connection();
    var channel = EndpointPool.newChannel(permit);
    channel.unsafe().closeForcibly();
    assertEquals(0, budget.connections());
    permit.close();
    assertEquals(0, budget.connections());
  }

  @Test
  void timeoutCancelAndRetirementDrainBorrowedChannel() throws Exception {
    var group = new NioEventLoopGroup(1);
    var budget = new ResourceBudget(10, 2, 1024);
    try (var backend = new BackendFixture();
        var pool =
            new EndpointPool(
                backend.endpoint(), 1, group, DefaultAddressResolverGroup.INSTANCE, budget)) {
      var first = pool.acquire().get(2, TimeUnit.SECONDS);
      var cancelled = pool.acquire();
      assertTrue(cancelled.cancel(false));
      var waiting = pool.acquire();
      assertThrows(ExecutionException.class, () -> waiting.get(2, TimeUnit.SECONDS));
      pool.close();
      assertTrue(first.channel.isActive(), "retirement must drain a borrowed channel");
      first.release(true);
      first.release(true);
      long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
      while ((budget.connections() != 0 || pool.borrowed.get() != 0 || pool.waiting.get() != 0)
          && System.nanoTime() < end) Thread.sleep(5);
      assertEquals(0, budget.connections());
      assertEquals(0, pool.borrowed.get());
      assertEquals(0, pool.waiting.get());
    } finally {
      group.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
    }
  }
}
