package dev.kvgateway.gateway;

import static org.junit.jupiter.api.Assertions.*;

import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.resolver.DefaultAddressResolverGroup;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class EndpointPoolTest {
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
