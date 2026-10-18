package dev.kvgateway.gateway;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.pool.*;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.resolver.AddressResolverGroup;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;
final class EndpointPool implements AutoCloseable {
  private static final AttributeKey<Long> CREATED=AttributeKey.valueOf("kv.created"),USED=AttributeKey.valueOf("kv.used");
  final String endpoint;final int limit;private final FixedChannelPool pool;private final EventLoopGroup group;
  final AtomicInteger borrowed=new AtomicInteger(),waiting=new AtomicInteger();private volatile boolean retired;
  EndpointPool(String endpoint,int limit,EventLoopGroup group,AddressResolverGroup<?> resolver,ResourceBudget budget){
    this.endpoint=endpoint;this.limit=limit;this.group=group;var parts=endpoint.split(":");
    var bootstrap=new Bootstrap().group(group).channelFactory(()->{
      var permit=budget.connection();if(permit==null)throw new IllegalStateException("global connection budget exhausted");
      try {var channel=newChannel(permit);channel.closeFuture().addListener(f->permit.close());return channel;}catch(Throwable e){permit.close();throw e;}
    }).resolver(resolver).remoteAddress(parts[0],Integer.parseInt(parts[1])).option(ChannelOption.CONNECT_TIMEOUT_MILLIS,250).option(ChannelOption.AUTO_READ,false).option(ChannelOption.TCP_NODELAY,true);
    var handler=new AbstractChannelPoolHandler(){
      public void channelCreated(Channel c){long now=System.nanoTime();c.attr(CREATED).set(now);c.attr(USED).set(now);c.pipeline().addLast("http-client",new HttpClientCodec(4096,8192,8192));}
      public void channelReleased(Channel c){c.attr(USED).set(System.nanoTime());}
    };
    ChannelHealthChecker check=c->{long now=System.nanoTime();var created=c.attr(CREATED).get();var used=c.attr(USED).get();return c.eventLoop().newSucceededFuture(!retired&&c.isActive()&&created!=null&&used!=null&&now-created<TimeUnit.MINUTES.toNanos(5)&&now-used<TimeUnit.SECONDS.toNanos(30));};
    pool=new FixedChannelPool(bootstrap,handler,check,FixedChannelPool.AcquireTimeoutAction.FAIL,100,limit,32,true,false);
  }
  static NioSocketChannel newChannel(ResourceBudget.Permit permit){return new NioSocketChannel(){@Override protected void doClose()throws Exception{try{super.doClose();}finally{permit.close();}}};}
  Future<Lease> acquire(){
    Promise<Lease> result=group.next().newPromise();if(retired){result.setFailure(new IllegalStateException("endpoint retired"));return result;}
    waiting.incrementAndGet();Future<Channel> acquiring=pool.acquire();
    result.addListener(f->{if(f.isCancelled())acquiring.cancel(false);});
    acquiring.addListener(f->{waiting.decrementAndGet();if(f.isSuccess()){
      var lease=new Lease(acquiring.getNow());borrowed.incrementAndGet();if(!result.trySuccess(lease))lease.release(false);
    }else result.tryFailure(f.cause()==null?new java.util.concurrent.CancellationException():f.cause());});return result;
  }
  final class Lease {
    final Channel channel;private final AtomicBoolean released=new AtomicBoolean();
    Lease(Channel channel){this.channel=channel;}
    void release(boolean reusable){if(!released.compareAndSet(false,true))return;channel.eventLoop().execute(()->{
      if(channel.pipeline().get("exchange")!=null)channel.pipeline().remove("exchange");
      if(!reusable||retired)channel.close();borrowed.decrementAndGet();pool.release(channel).addListener(f->{if(!f.isSuccess())channel.close();});
    });}
  }
  public void close(){retired=true;pool.closeAsync();}
}
