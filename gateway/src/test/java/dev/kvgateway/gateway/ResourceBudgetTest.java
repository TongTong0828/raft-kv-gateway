package dev.kvgateway.gateway;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ResourceBudgetTest {
  @org.junit.jupiter.api.Test
  @SuppressWarnings("unchecked")
  void retiredPermitCannotDeleteAReactivatedServiceCounter() throws Exception {
    var budget = new ResourceBudget(2, 1, 1024);
    budget.configure("orders", 1);
    var old = budget.admit("orders");
    budget.reconcile(java.util.List.of());
    var field = ResourceBudget.class.getDeclaredField("services");
    field.setAccessible(true);
    var counters = (java.util.concurrent.ConcurrentHashMap<String, Object>) field.get(budget);
    var closing = new Thread(old::close);
    closing.setDaemon(true);
    // Hold the map bin so the old release reaches retirement, then reinstall before it can remove.
    counters.compute(
        "orders",
        (key, counter) -> {
          closing.start();
          long end = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
          while (closing.getState() != Thread.State.BLOCKED && System.nanoTime() < end)
            java.util.concurrent.locks.LockSupport.parkNanos(1000000);
          assertEquals(Thread.State.BLOCKED, closing.getState());
          budget.configure("orders", 1);
          return counter;
        });
    closing.join(3000);
    assertFalse(closing.isAlive());
    var fresh = budget.admit("orders");
    assertNotNull(fresh, "valid reinstalled service must retain admission state");
    fresh.close();
    assertEquals(0, budget.inflight());
  }

  @Test
  void permitSurvivesConfigurationUpdateAndReleasesOnce() {
    var b = new ResourceBudget(2, 1, 16);
    b.configure("orders", 1);
    b.configure("other", 1);
    var permit = b.admit("orders");
    assertNotNull(permit);
    b.configure("orders", 1);
    assertNull(b.admit("orders"));
    var other = b.admit("other");
    assertNotNull(other);
    assertEquals(2, b.inflight());
    other.close();
    permit.close();
    permit.close();
    assertEquals(0, b.inflight());
    assertNotNull(b.admit("orders"));
  }

  @Test
  void aggregateAndConnectionBudgetsCannotOverflow() {
    var b = new ResourceBudget(1, 1, 16);
    assertTrue(b.reserveBytes(12));
    assertFalse(b.reserveBytes(5));
    assertEquals(12, b.bodyBytes());
    b.releaseBytes(12);
    assertEquals(0, b.bodyBytes());
    var connection = b.connection();
    assertNotNull(connection);
    assertNull(b.connection());
    connection.close();
    connection.close();
    assertEquals(0, b.connections());
  }
}
