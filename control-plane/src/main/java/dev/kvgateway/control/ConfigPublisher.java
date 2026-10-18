package dev.kvgateway.control;
import dev.kvgateway.api.*;
import io.grpc.stub.ServerCallStreamObserver;
import java.util.concurrent.*;
import java.util.function.Supplier;
import java.util.*;
import java.util.concurrent.atomic.*;
import io.grpc.Status;
public final class ConfigPublisher implements AutoCloseable {
  private final KvStore store;private final String group;
  private final Set<Subscriber> subscribers=ConcurrentHashMap.newKeySet();
  private final ScheduledExecutorService executor=Executors.newScheduledThreadPool(2,r->{var t=new Thread(r,"config-publisher");t.setDaemon(true);return t;});
  private final AtomicBoolean checking=new AtomicBoolean();private volatile boolean closed;
  public ConfigPublisher(KvStore store,String group,Supplier<CompletableFuture<?>> barrier){
    this.store=store;this.group=group;store.onChange(this::publish);
    executor.scheduleWithFixedDelay(()->{
      if(subscribers.isEmpty()||!checking.compareAndSet(false,true))return;
      try {barrier.get().orTimeout(2,TimeUnit.SECONDS).whenComplete((v,e)->{checking.set(false);if(e!=null)fence();});}
      catch(Throwable e){checking.set(false);fence();}
    },1,1,TimeUnit.SECONDS);
  }
  public void subscribe(ServerCallStreamObserver<ConfigSnapshot> observer){
    subscribe(observer,()->CompletableFuture.completedFuture(null));
  }
  public void subscribe(ServerCallStreamObserver<ConfigSnapshot> observer,Supplier<CompletableFuture<?>> initialBarrier){
    final Subscriber subscriber;
    synchronized(store){
      if(closed){observer.onError(Status.UNAVAILABLE.asRuntimeException());return;}
      if(subscribers.size()>=16){observer.onError(Status.RESOURCE_EXHAUSTED.withDescription("Watch limit reached").asRuntimeException());return;}
      subscriber=new Subscriber(observer);subscribers.add(subscriber);observer.setOnCancelHandler(subscriber::cancel);observer.setOnReadyHandler(subscriber::schedule);
      if(observer.isCancelled())subscriber.cancel();
    }
    try {initialBarrier.get().whenComplete((v,e)->{if(e!=null){subscriber.fail(Status.UNAVAILABLE.withDescription("configuration read barrier failed").asRuntimeException());return;}synchronized(store){subscriber.validated=true;subscriber.offer(store.configuration(group));}});}
    catch(Throwable e){subscriber.fail(Status.UNAVAILABLE.asRuntimeException());}
  }
  private void publish(){if(closed||subscribers.isEmpty())return;var value=store.configuration(group);for(var s:subscribers)if(s.validated)s.offer(value);}
  public void fence(){for(var s:subscribers)s.fail(Status.UNAVAILABLE.withDescription("configuration leader lost quorum").asRuntimeException());}
  public int count(){return subscribers.size();}
  @Override public void close(){closed=true;fence();executor.shutdownNow();}
  private final class Subscriber {
    final ServerCallStreamObserver<ConfigSnapshot> observer;final AtomicReference<ConfigSnapshot> pending=new AtomicReference<>();final AtomicBoolean scheduled=new AtomicBoolean();volatile boolean ended,validated;
    Subscriber(ServerCallStreamObserver<ConfigSnapshot> observer){this.observer=observer;}
    void offer(ConfigSnapshot value){synchronized(this){if(ended)return;pending.set(value);}schedule();}
    void schedule(){if(closed||!scheduled.compareAndSet(false,true))return;try{executor.execute(this::drain);}catch(RejectedExecutionException e){scheduled.set(false);}}
    void drain(){
      try {synchronized(this){if(ended||observer.isCancelled()){cancel();return;}if(observer.isReady()){var v=pending.getAndSet(null);if(v!=null)observer.onNext(v);}}}
      catch(Throwable e){fail(Status.UNAVAILABLE.asRuntimeException());}
      finally {scheduled.set(false);if(!ended&&pending.get()!=null&&observer.isReady())schedule();}
    }
    synchronized void cancel(){ended=true;pending.set(null);subscribers.remove(this);}
    synchronized void fail(Throwable error){if(ended)return;cancel();if(!observer.isCancelled())try{observer.onError(error);}catch(RuntimeException ignored){}}
  }
}
