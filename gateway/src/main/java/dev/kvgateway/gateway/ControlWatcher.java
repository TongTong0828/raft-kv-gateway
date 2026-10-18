package dev.kvgateway.gateway;
import dev.kvgateway.api.*;
import com.google.protobuf.Empty;
import io.grpc.*;
import io.grpc.stub.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
/** One bounded installer thread, one outstanding configuration message, reusable seed channels. */
final class ControlWatcher implements AutoCloseable {
  private final List<ManagedChannel> channels;private final RouteManager routes;private volatile boolean closed;
  private final ScheduledExecutorService executor=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"config-installer");t.setDaemon(true);return t;});
  private volatile ClientCallStreamObserver<Empty> stream;private long backoff=100;
  private volatile boolean connected;private volatile long installedAt;private final LongAdder attempts=new LongAdder(),disconnects=new LongAdder();
  ControlWatcher(List<String> seeds,RouteManager routes){if(seeds.isEmpty()||seeds.size()>16)throw new IllegalArgumentException("1..16 seeds required");this.routes=routes;channels=seeds.stream().map(ServiceSpecs::endpoint).map(s->ManagedChannelBuilder.forTarget(s).usePlaintext().maxInboundMessageSize(8*1024*1024).build()).toList();}
  void start(){executor.execute(this::connect);}
  private void connect(){if(closed)return;attempts.increment();ManagedChannel leader=null;
    for(var c:channels){try{var status=ControlGrpc.newBlockingStub(c).withDeadlineAfter(500,TimeUnit.MILLISECONDS).getStatus(Empty.getDefaultInstance());if(status.getRole().equals("LEADER")){leader=c;break;}}catch(StatusRuntimeException ignored){}}
    if(leader==null){retry();return;}long generation=routes.beginSubscription();var finished=new AtomicBoolean();
    ControlGrpc.newStub(leader).watchConfig(Empty.getDefaultInstance(),new ClientResponseObserver<Empty,ConfigSnapshot>(){
      private ClientCallStreamObserver<Empty> call;
      public void beforeStart(ClientCallStreamObserver<Empty> request){call=request;stream=request;request.disableAutoRequestWithInitial(1);if(closed)request.cancel("gateway stopping",null);}
      public void onNext(ConfigSnapshot config){if(closed)return;submit(()->{if(closed||finished.get())return;try{routes.install(config,generation);connected=true;installedAt=System.currentTimeMillis();backoff=100;call.request(1);}catch(RuntimeException e){call.cancel("invalid configuration",e);end();}});}
      private void end(){connected=false;if(!closed&&finished.compareAndSet(false,true)){disconnects.increment();submit(ControlWatcher.this::retry);}}
      public void onError(Throwable error){end();}public void onCompleted(){end();}
    });
  }
  private void submit(Runnable task){if(!closed)try{executor.execute(task);}catch(RejectedExecutionException ignored){}}
  private void retry(){if(closed)return;long delay=backoff+ThreadLocalRandom.current().nextLong(Math.max(1,backoff/4));backoff=Math.min(5000,backoff*2);executor.schedule(this::connect,delay,TimeUnit.MILLISECONDS);}
  String metrics(){return "gateway_config_connected "+(connected?1:0)+"\ngateway_watch_attempts_total "+attempts.sum()+"\ngateway_watch_disconnects_total "+disconnects.sum()+"\ngateway_config_received_timestamp_seconds "+installedAt/1000.0+"\n";}
  boolean connected(){return connected;}
  public void close(){closed=true;var s=stream;if(s!=null)s.cancel("gateway stopping",null);executor.shutdownNow();channels.forEach(ManagedChannel::shutdownNow);for(var c:channels)try{c.awaitTermination(2,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
}
