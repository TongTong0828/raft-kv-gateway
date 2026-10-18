package dev.kvgateway.gateway;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.resolver.AddressResolverGroup;
import java.util.concurrent.*;
final class HealthProbe {
  private final EventLoopGroup group;private final AddressResolverGroup<?> resolver;
  HealthProbe(EventLoopGroup group,AddressResolverGroup<?> resolver){this.group=group;this.resolver=resolver;}
  CompletableFuture<Boolean> probe(String endpoint){
    var result=new CompletableFuture<Boolean>();var parts=endpoint.split(":");
    var bootstrap=new Bootstrap().group(group).channel(NioSocketChannel.class).resolver(resolver).option(ChannelOption.CONNECT_TIMEOUT_MILLIS,250)
      .handler(new ChannelInitializer<Channel>(){protected void initChannel(Channel c){c.pipeline().addLast(new HttpClientCodec(),new HttpObjectAggregator(4096),new SimpleChannelInboundHandler<FullHttpResponse>(){
        protected void channelRead0(ChannelHandlerContext ctx,FullHttpResponse response){result.complete(response.decoderResult().isSuccess()&&response.status().code()==200&&response.content().readableBytes()<=4096);ctx.close();}
        public void exceptionCaught(ChannelHandlerContext ctx,Throwable error){result.complete(false);ctx.close();}
        public void channelInactive(ChannelHandlerContext ctx){result.complete(false);}
      });}});
    var connecting=bootstrap.connect(parts[0],Integer.parseInt(parts[1]));
    var timer=group.next().schedule(()->result.complete(false),250,TimeUnit.MILLISECONDS);
    result.whenComplete((v,e)->{timer.cancel(false);connecting.channel().close();});
    connecting.addListener(f->{if(!f.isSuccess()){result.complete(false);return;}if(result.isDone()){connecting.channel().close();return;}
      var request=new DefaultFullHttpRequest(HttpVersion.HTTP_1_1,HttpMethod.GET,"/health");request.headers().set(HttpHeaderNames.HOST,endpoint).set(HttpHeaderNames.CONNECTION,HttpHeaderValues.CLOSE);connecting.channel().writeAndFlush(request);
    });return result;
  }
}
