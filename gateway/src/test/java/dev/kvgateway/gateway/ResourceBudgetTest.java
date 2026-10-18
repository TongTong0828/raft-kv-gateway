package dev.kvgateway.gateway;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ResourceBudgetTest {
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
