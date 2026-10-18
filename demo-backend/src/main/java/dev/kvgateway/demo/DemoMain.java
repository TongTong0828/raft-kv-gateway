package dev.kvgateway.demo;
import dev.kvgateway.api.Settings;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.buffer.Unpooled;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.*;
public final class DemoMain {
  public static void main(String[] args)throws Exception {
    var settings=Settings.from(args);int port=settings.integer("port",8081,1,65535),size=settings.integer("response-bytes",1024,1,1048576),delay=settings.integer("delay-ms",0,0,30000),failure=settings.integer("failure-status",200,200,599);String id=settings.get("backend-id","demo");
    byte[] payload=new byte[size];Arrays.fill(payload,(byte)'x');var boss=new NioEventLoopGroup(1);var workers=new NioEventLoopGroup(2);
    var server=new ServerBootstrap().group(boss,workers).channel(NioServerSocketChannel.class).childOption(ChannelOption.TCP_NODELAY,true).childHandler(new ChannelInitializer<Channel>(){protected void initChannel(Channel c){c.pipeline().addLast(new HttpServerCodec(),new HttpObjectAggregator(1048576),new SimpleChannelInboundHandler<FullHttpRequest>(){protected void channelRead0(ChannelHandlerContext ctx,FullHttpRequest request){
      boolean health=request.uri().equals("/health"),head=request.method().equals(HttpMethod.HEAD),keep=HttpUtil.isKeepAlive(request);var bytes=health?"ok".getBytes(StandardCharsets.US_ASCII):payload;int status=health?200:failure;
      Runnable send=()->{if(!ctx.channel().isActive())return;var out=new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,HttpResponseStatus.valueOf(status),Unpooled.wrappedBuffer(head?new byte[0]:bytes));out.headers().setInt(HttpHeaderNames.CONTENT_LENGTH,bytes.length).set("X-Backend-Id",id);HttpUtil.setKeepAlive(out,keep);var f=ctx.writeAndFlush(out);if(!keep)f.addListener(ChannelFutureListener.CLOSE);};
      if(!health&&delay>0)ctx.executor().schedule(send,delay,TimeUnit.MILLISECONDS);else send.run();
    }});}}).bind(port).sync().channel();
    Runtime.getRuntime().addShutdownHook(new Thread(()->{server.close().awaitUninterruptibly();boss.shutdownGracefully(0,1,TimeUnit.SECONDS).awaitUninterruptibly();workers.shutdownGracefully(0,1,TimeUnit.SECONDS).awaitUninterruptibly();}));System.out.println("Backend "+id+" listening on "+port);server.closeFuture().sync();
  }
}
