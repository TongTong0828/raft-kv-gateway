package dev.kvgateway.api;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class ServiceSpecsTest {
  private ServiceSpec.Builder valid() { return ServiceSpec.newBuilder().setServiceId("orders").setPathPrefix("/orders").addEndpoints("backend:8080"); }
  @Test void defaultsOnlyAbsentFields() {
    var s=ServiceSpecs.normalize(valid().build());
    assertEquals(2000,s.getRequestTimeoutMs()); assertEquals(100,s.getMaxInflight()); assertEquals(16,s.getMaxConnectionsPerEndpoint());
    assertThrows(IllegalArgumentException.class,()->ServiceSpecs.normalize(valid().setMaxInflight(0).build()));
  }
  @Test void rejectsInvalidEndpoint() {
    for(var endpoint:List.of("http://a:80","a:0","a:65536","a:80/path","user@host:80","[::1]:80"))
      assertThrows(IllegalArgumentException.class,()->ServiceSpecs.normalize(valid().clearEndpoints().addEndpoints(endpoint).build()),endpoint);
    assertThrows(IllegalArgumentException.class,()->ServiceSpecs.normalize(valid().addEndpoints("backend:8080").build()));
  }
  @Test void rejectsDuplicatePrefixesAndExcessServices() {
    assertThrows(IllegalArgumentException.class,()->ServiceSpecs.validateAll(List.of(valid().build(),valid().setServiceId("other").build())));
    assertThrows(IllegalArgumentException.class,()->ServiceSpecs.validateAll(java.util.stream.IntStream.range(0,129).mapToObj(i->valid().setServiceId("svc-"+i).setPathPrefix("/s"+i).build()).toList()));
    assertThrows(IllegalArgumentException.class,()->ServiceSpecs.normalize(valid().setPathPrefix("/orders/").build()));
    assertDoesNotThrow(()->ServiceSpecs.normalize(valid().clearEndpoints().build()));
  }
}
