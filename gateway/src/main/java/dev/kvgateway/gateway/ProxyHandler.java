package dev.kvgateway.gateway;
import dev.kvgateway.api.ServiceSpec;
import io.netty.buffer.*;
import io.netty.channel.*;
import io.netty.handler.codec.http.*;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
final class ProxyHandler extends ChannelInboundHandlerAdapter {
  private static final int MAX_BODY=1024*1024;
  private static final Set<HttpMethod> METHODS=Set.of(HttpMethod.GET,HttpMethod.HEAD,HttpMethod.POST,HttpMethod.PUT,HttpMethod.PATCH,HttpMethod.DELETE,HttpMethod.OPTIONS);
  private final GatewayServer server;private ChannelHandlerContext ctx;private Exchange active;private ScheduledFuture<?> headersTimer;
  ProxyHandler(GatewayServer server){this.server=server;}
  public void channelActive(ChannelHandlerContext ctx){this.ctx=ctx;armHeaders();}
  private void armHeaders(){headersTimer=ctx.executor().schedule(()->ctx.close(),5,TimeUnit.SECONDS);}
  public void channelRead(ChannelHandlerContext ctx,Object msg){try{
    if(msg instanceof HttpRequest request){
      if(active!=null){active.abort();ctx.close();return;}
      headersTimer.cancel(false);
      if(!request.decoderResult().isSuccess()){reject(400);return;}
      if(!request.protocolVersion().equals(HttpVersion.HTTP_1_1)||!METHODS.contains(request.method())||request.headers().contains(HttpHeaderNames.UPGRADE)||!request.uri().startsWith("/")){reject(501);return;}
      if(request.headers().contains(HttpHeaderNames.EXPECT)){reject(417);return;}
      if(!request.headers().contains(HttpHeaderNames.HOST)||request.headers().contains(HttpHeaderNames.TRAILER)){reject(400);return;}
      long length;try{length=HttpUtil.getContentLength(request,0);}catch(RuntimeException e){reject(400);return;}
      if(length>MAX_BODY){reject(413);return;}
      var table=server.routes.current();if(table==null){reject(503);return;}var service=table.match(request.uri());if(service==null){reject(404);return;}
      var permit=server.budget.admit(service.getServiceId());if(permit==null){server.metrics.rejected(service.getServiceId());reject(503);return;}
      active=new Exchange(request,service,permit);
    }
    if(msg instanceof HttpContent content&&active!=null){var e=active;if(!content.decoderResult().isSuccess()){e.fail(400,false);return;}
      if(e.requestComplete){e.abort();ctx.close();return;}
      if(!e.append(content.content(),true))return;
      if(content instanceof LastHttpContent last){if(!last.trailingHeaders().isEmpty()){e.fail(400,false);return;}e.requestComplete=true;e.forward();}
    }
  }finally{ReferenceCountUtil.release(msg);}}
  private void reject(int status){server.metrics.record(status,0);var out=new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,HttpResponseStatus.valueOf(status));out.headers().setInt(HttpHeaderNames.CONTENT_LENGTH,0).set(HttpHeaderNames.CONNECTION,HttpHeaderValues.CLOSE);ctx.writeAndFlush(out).addListener(ChannelFutureListener.CLOSE);}
  public void channelInactive(ChannelHandlerContext ctx){if(headersTimer!=null)headersTimer.cancel(false);if(active!=null)active.abort();}
  public void exceptionCaught(ChannelHandlerContext ctx,Throwable cause){if(active!=null)active.abort();ctx.close();}
  private final class Exchange {
    final ServiceSpec service;final ResourceBudget.Permit permit;final HttpMethod method;final String uri;final HttpHeaders requestHeaders;final boolean keepAlive;final long started=System.nanoTime();
    ByteBuf request=Unpooled.buffer(0),response=Unpooled.buffer(0);int reserved;boolean requestComplete,responding;volatile boolean ended;ScheduledFuture<?> deadline;Future<EndpointPool.Lease> acquiring;EndpointPool.Lease lease;String endpoint;Object healthGeneration;HttpResponse responseHead;
    Exchange(HttpRequest head,ServiceSpec service,ResourceBudget.Permit permit){this.service=service;this.permit=permit;method=head.method();uri=head.uri();requestHeaders=new DefaultHttpHeaders().set(head.headers());keepAlive=HttpUtil.isKeepAlive(head);deadline=ctx.executor().schedule(()->{if(!ended&&!responding)fail(504,false);else if(!ended){abort();ctx.close();}},service.getRequestTimeoutMs(),TimeUnit.MILLISECONDS);}
    boolean append(ByteBuf source,boolean inbound){var target=inbound?request:response;int n=source.readableBytes();if(n>MAX_BODY-target.readableBytes()){fail(inbound?413:502,!inbound);return false;}
      if(!server.budget.reserveBytes(n)){fail(503,false);return false;}reserved+=n;target.writeBytes(source,source.readerIndex(),n);return true;}
    void forward(){var available=service.getEndpointsList().stream().filter(server.health::healthy).toList();if(available.isEmpty()){fail(503,false);return;}
      var counter=server.roundRobin.computeIfAbsent(service.getServiceId(),s->new AtomicInteger());endpoint=available.get(Math.floorMod(counter.getAndIncrement(),available.size()));var pool=server.pools.get(service.getServiceId(),endpoint);if(pool==null){fail(503,false);return;}
      healthGeneration=server.health.generation(endpoint);acquiring=pool.acquire();acquiring.addListener(f->{EndpointPool.Lease obtained=f.isSuccess()?acquiring.getNow():null;ctx.executor().execute(()->{
        if(ended||responding){if(obtained!=null)obtained.release(false);return;}
        if(!f.isSuccess()){var cause=f.cause();boolean local=cause instanceof java.util.concurrent.TimeoutException||cause instanceof IllegalStateException||cause instanceof java.util.concurrent.CancellationException;fail(local?503:502,!local);return;}
        lease=obtained;var channel=obtained.channel;
        var out=new DefaultFullHttpRequest(HttpVersion.HTTP_1_1,method,uri,request.retainedDuplicate());HttpPolicy.copy(requestHeaders,out.headers(),true);out.headers().set(HttpHeaderNames.HOST,endpoint).setInt(HttpHeaderNames.CONTENT_LENGTH,out.content().readableBytes());HttpUtil.setKeepAlive(out,true);
        channel.eventLoop().execute(()->{
          if(ended){out.release();obtained.release(false);return;}
          channel.pipeline().addLast("exchange",new ChannelInboundHandlerAdapter(){
            public void channelRead(ChannelHandlerContext upstream,Object value){ctx.executor().execute(()->{try{receive(value);}finally{ReferenceCountUtil.release(value);}});}
            public void channelReadComplete(ChannelHandlerContext upstream){ctx.executor().execute(()->{if(!ended&&!responding&&lease==obtained)upstream.read();});}
            public void channelInactive(ChannelHandlerContext upstream){ctx.executor().execute(()->{if(!ended&&!responding&&lease==obtained)fail(502,true);});}
            public void exceptionCaught(ChannelHandlerContext upstream,Throwable error){ctx.executor().execute(()->{if(!ended&&!responding&&lease==obtained)fail(502,true);});}
          });
          channel.writeAndFlush(out).addListener(w->{if(!w.isSuccess())ctx.executor().execute(()->{if(!ended&&!responding)fail(502,true);});});channel.read();
        });
      });});
    }
    void receive(Object msg){if(ended||responding)return;
      if(msg instanceof HttpObject object&&!object.decoderResult().isSuccess()){fail(502,true);return;}
      if(msg instanceof HttpResponse head){if(responseHead!=null||head.status().code()<200||head.headers().contains(HttpHeaderNames.TRAILER)){fail(502,true);return;}responseHead=new DefaultHttpResponse(head.protocolVersion(),head.status(),new DefaultHttpHeaders().set(head.headers()));
        if(!method.equals(HttpMethod.HEAD)&&HttpUtil.getContentLength(head,0)>MAX_BODY){fail(502,true);return;}}
      if(msg instanceof HttpContent content){if(responseHead==null){fail(502,true);return;}if(!append(content.content(),false))return;if(content instanceof LastHttpContent last){if(!last.trailingHeaders().isEmpty()){fail(502,true);return;}respond();}}
    }
    void respond(){responding=true;var released=lease;lease=null;released.release(HttpUtil.isKeepAlive(responseHead));
      var out=new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,responseHead.status(),response.retainedDuplicate());HttpPolicy.copy(responseHead.headers(),out.headers(),false);
      if(method.equals(HttpMethod.HEAD)){var value=responseHead.headers().get(HttpHeaderNames.CONTENT_LENGTH);if(value!=null)out.headers().set(HttpHeaderNames.CONTENT_LENGTH,value);}else if(responseHead.status().code()!=204&&responseHead.status().code()!=304)out.headers().setInt(HttpHeaderNames.CONTENT_LENGTH,out.content().readableBytes());
      HttpUtil.setKeepAlive(out,keepAlive);int status=out.status().code();ctx.writeAndFlush(out).addListener(f->{finish(status);if(!keepAlive||!f.isSuccess())ctx.close();else armHeaders();});
    }
    void fail(int status,boolean endpointFault){if(ended||responding)return;if(endpointFault&&endpoint!=null)server.health.failure(endpoint,healthGeneration);responding=true;releaseUpstream();var out=new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,HttpResponseStatus.valueOf(status));out.headers().setInt(HttpHeaderNames.CONTENT_LENGTH,0).set(HttpHeaderNames.CONNECTION,HttpHeaderValues.CLOSE);ctx.writeAndFlush(out).addListener(f->{finish(status);ctx.close();});}
    void releaseUpstream(){if(acquiring!=null&&!acquiring.isDone())acquiring.cancel(false);if(lease!=null){lease.release(false);lease=null;}}
    void abort(){finish(499);}
    void finish(int status){if(ended)return;ended=true;deadline.cancel(false);releaseUpstream();request.release();response.release();server.budget.releaseBytes(reserved);permit.close();server.metrics.record(status,System.nanoTime()-started);if(active==this)active=null;}
  }
}
