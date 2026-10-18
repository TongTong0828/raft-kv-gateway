package dev.kvgateway.gateway;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
class HealthMonitorTest {
  @Test void retiredRequestCannotPenalizeRecreatedEndpoint(){
    try(var monitor=new HealthMonitor(e->CompletableFuture.completedFuture(true))){monitor.reconcile(Set.of("a:80"));Object old=monitor.generation("a:80");monitor.reconcile(Set.of());monitor.reconcile(Set.of("a:80"));for(int i=0;i<3;i++)monitor.failure("a:80",old);assertTrue(monitor.healthy("a:80"));}
  }
  @Test void oldProbeCannotReviveRemovedAndRecreatedEndpoint(){
    var old=new CompletableFuture<Boolean>();var fresh=new CompletableFuture<Boolean>();var queue=new java.util.concurrent.ConcurrentLinkedQueue<>(List.of(old,fresh));
    try(var monitor=new HealthMonitor(e->queue.remove())){
      monitor.reconcile(Set.of("a:80"));monitor.reconcile(Set.of());monitor.reconcile(Set.of("a:80"));old.complete(true);assertFalse(monitor.healthy("a:80"));fresh.complete(true);assertTrue(monitor.healthy("a:80"));
    }
  }
  @Test void threeFailuresAndTwoProbeSuccessesAreRequiredForRecovery(){
    try(var monitor=new HealthMonitor(e->CompletableFuture.completedFuture(true))){
      monitor.reconcile(Set.of("a:80"));assertTrue(monitor.healthy("a:80"));monitor.failure("a:80");monitor.failure("a:80");assertTrue(monitor.healthy("a:80"));monitor.failure("a:80");assertFalse(monitor.healthy("a:80"));
      monitor.refresh();assertFalse(monitor.healthy("a:80"));monitor.refresh();assertTrue(monitor.healthy("a:80"));
    }
  }
}
