package dev.kvgateway.gateway;

import com.sun.net.httpserver.HttpServer;
import dev.kvgateway.api.*;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.group.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.*;
import io.netty.handler.codec.http.*;
import io.netty.resolver.dns.*;
import io.netty.util.concurrent.GlobalEventExecutor;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class GatewayServer implements AutoCloseable {
  final RouteManager routes;
  final ResourceBudget budget = new ResourceBudget(256, 512, 64 * 1024 * 1024);
  final GatewayMetrics metrics = new GatewayMetrics();
  final ConcurrentHashMap<String, AtomicInteger> roundRobin = new ConcurrentHashMap<>();
  final PoolRegistry pools;
  final HealthMonitor health;
  private final EventLoopGroup boss = new NioEventLoopGroup(1), workers = new NioEventLoopGroup(2);
  private final DnsAddressResolverGroup resolver =
      new DnsAddressResolverGroup(
          NioDatagramChannel.class, DnsServerAddressStreamProviders.platformDefault());
  private final ChannelGroup clients = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
  private final AtomicInteger downstreams = new AtomicInteger();
  private Channel listener;
  private HttpServer admin;
  private ControlWatcher watcher;
  private final ExecutorService adminExecutor = Executors.newFixedThreadPool(2);
  private final AtomicBoolean closed = new AtomicBoolean();

  private GatewayServer(Settings.Gateway settings) throws Exception {
    routes = new RouteManager(settings.groupId());
    pools = new PoolRegistry(workers, resolver, budget);
    health = new HealthMonitor(new HealthProbe(workers, resolver)::probe);
    routes.onInstall(
        table -> {
          var specs = table.snapshot().getServicesList();
          budget.reconcile(specs);
          pools.reconcile(specs);
          var endpoints = new HashSet<String>();
          var ids = new HashSet<String>();
          for (var s : specs) {
            ids.add(s.getServiceId());
            endpoints.addAll(s.getEndpointsList());
          }
          health.reconcile(endpoints);
          roundRobin.keySet().retainAll(ids);
          metrics.reconcile(ids);
        });
    try {
      listener =
          new ServerBootstrap()
              .group(boss, workers)
              .channel(NioServerSocketChannel.class)
              .childOption(ChannelOption.TCP_NODELAY, true)
              .childHandler(
                  new ChannelInitializer<Channel>() {
                    protected void initChannel(Channel c) {
                      if (downstreams.incrementAndGet() > 2048) {
                        downstreams.decrementAndGet();
                        c.close();
                        return;
                      }
                      clients.add(c);
                      c.closeFuture().addListener(f -> downstreams.decrementAndGet());
                      var config =
                          new HttpDecoderConfig()
                              .setMaxInitialLineLength(4096)
                              .setMaxHeaderSize(8192)
                              .setMaxChunkSize(8192)
                              .setAllowDuplicateContentLengths(false)
                              .setUseRfc9112TransferEncoding(true);
                      c.pipeline()
                          .addLast(
                              new HttpRequestDecoder(config),
                              new HttpResponseEncoder(),
                              new ProxyHandler(GatewayServer.this));
                    }
                  })
              .bind(settings.port())
              .sync()
              .channel();
      admin = HttpServer.create(new InetSocketAddress(settings.adminPort()), 16);
      admin.setExecutor(adminExecutor);
      admin.createContext(
          "/",
          exchange -> {
            String path = exchange.getRequestURI().getPath();
            int status = 200;
            String body;
            if (path.equals("/live")) body = "live\n";
            else if (path.equals("/ready")) {
              status = routes.current() == null ? 503 : 200;
              body = status == 200 ? "ready\n" : "waiting for initial config\n";
            } else if (path.equals("/metrics"))
              body =
                  metrics.render(budget, routes, health)
                      + (watcher == null ? "" : watcher.metrics())
                      + "gateway_pool_active "
                      + pools.borrowed()
                      + "\ngateway_pool_idle "
                      + Math.max(0, budget.connections() - pools.borrowed())
                      + "\ngateway_pool_waiting "
                      + pools.waiting()
                      + "\n";
            else if (path.equals("/status"))
              body =
                  "{\"configured\":"
                      + (routes.current() != null)
                      + ",\"revision\":"
                      + (routes.current() == null ? 0 : routes.current().snapshot().getRevision())
                      + ",\"controlConnected\":"
                      + (watcher != null && watcher.connected())
                      + "}";
            else {
              status = 404;
              body = "not found\n";
            }
            var bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (var out = exchange.getResponseBody()) {
              out.write(bytes);
            }
          });
      admin.start();
      watcher = new ControlWatcher(settings.seeds(), routes);
      watcher.start();
    } catch (Exception e) {
      close();
      throw e;
    }
  }

  public static GatewayServer start(Settings.Gateway settings) throws Exception {
    return new GatewayServer(settings);
  }

  public int port() {
    return ((InetSocketAddress) listener.localAddress()).getPort();
  }

  public void close() {
    if (!closed.compareAndSet(false, true)) return;
    if (watcher != null) watcher.close();
    if (listener != null) listener.close().awaitUninterruptibly();
    clients.close().awaitUninterruptibly();
    if (admin != null) admin.stop(0);
    adminExecutor.shutdownNow();
    health.close();
    pools.close();
    boss.shutdownGracefully(0, 2, TimeUnit.SECONDS).awaitUninterruptibly();
    workers.shutdownGracefully(0, 2, TimeUnit.SECONDS).awaitUninterruptibly();
    resolver.close();
  }
}
