package dev.kvgateway.gateway;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.pool.*;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.resolver.AddressResolverGroup;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.*;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;

final class EndpointPool implements AutoCloseable {
  private static final AttributeKey<Long> CREATED = AttributeKey.valueOf("kv.created"),
      USED = AttributeKey.valueOf("kv.used");
  private static final AttributeKey<Boolean> LEASED = AttributeKey.valueOf("kv.leased");
  private final Set<Channel> channels = ConcurrentHashMap.newKeySet();
  private final ScheduledFuture<?> reaper;
  final String endpoint;
  final int limit;
  private final FixedChannelPool pool;
  private final EventLoopGroup group;
  final AtomicInteger borrowed = new AtomicInteger(), waiting = new AtomicInteger();
  final AtomicInteger releasing = new AtomicInteger();
  private final Consumer<Failure> failures;
  private volatile boolean retired;

  record Failure(
      Throwable cause,
      int acquired,
      int borrowed,
      int waiting,
      int releasing,
      int limit,
      boolean retired,
      long elapsedNanos) {}

  EndpointPool(
      String endpoint,
      int limit,
      EventLoopGroup group,
      AddressResolverGroup<?> resolver,
      ResourceBudget budget,
      Consumer<Failure> failures) {
    this(endpoint, limit, group, resolver, budget, 30000, 300000, failures);
  }

  EndpointPool(
      String endpoint,
      int limit,
      EventLoopGroup group,
      AddressResolverGroup<?> resolver,
      ResourceBudget budget) {
    this(endpoint, limit, group, resolver, budget, 30000, 300000);
  }

  EndpointPool(
      String endpoint,
      int limit,
      EventLoopGroup group,
      AddressResolverGroup<?> resolver,
      ResourceBudget budget,
      long idleMs,
      long lifeMs) {
    this(endpoint, limit, group, resolver, budget, idleMs, lifeMs, failure -> {});
  }

  private EndpointPool(
      String endpoint,
      int limit,
      EventLoopGroup group,
      AddressResolverGroup<?> resolver,
      ResourceBudget budget,
      long idleMs,
      long lifeMs,
      Consumer<Failure> failures) {
    this.endpoint = endpoint;
    this.failures = failures;
    this.limit = limit;
    this.group = group;
    var parts = endpoint.split(":");
    var bootstrap =
        new Bootstrap()
            .group(group)
            .channelFactory(
                () -> {
                  var permit = budget.connection();
                  if (permit == null)
                    throw new IllegalStateException("global connection budget exhausted");
                  try {
                    var channel = newChannel(permit);
                    channel.closeFuture().addListener(f -> permit.close());
                    return channel;
                  } catch (Throwable e) {
                    permit.close();
                    throw e;
                  }
                })
            .resolver(resolver)
            .remoteAddress(parts[0], Integer.parseInt(parts[1]))
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 250)
            .option(ChannelOption.AUTO_READ, false)
            .option(ChannelOption.TCP_NODELAY, true);
    var handler =
        new AbstractChannelPoolHandler() {
          public void channelCreated(Channel c) {
            long now = System.nanoTime();
            c.attr(CREATED).set(now);
            c.attr(USED).set(now);
            c.attr(LEASED).set(false);
            channels.add(c);
            c.closeFuture().addListener(f -> channels.remove(c));
            c.pipeline().addLast("http-client", new HttpClientCodec(4096, 8192, 8192));
          }

          public void channelAcquired(Channel c) {
            c.attr(LEASED).set(true);
          }

          public void channelReleased(Channel c) {
            c.attr(USED).set(System.nanoTime());
            c.attr(LEASED).set(false);
          }
        };
    ChannelHealthChecker check =
        c -> {
          long now = System.nanoTime();
          var created = c.attr(CREATED).get();
          var used = c.attr(USED).get();
          return c.eventLoop()
              .newSucceededFuture(
                  !retired
                      && c.isActive()
                      && created != null
                      && used != null
                      && now - created < TimeUnit.MILLISECONDS.toNanos(lifeMs)
                      && now - used < TimeUnit.MILLISECONDS.toNanos(idleMs));
        };
    pool =
        new FixedChannelPool(
            bootstrap,
            handler,
            check,
            FixedChannelPool.AcquireTimeoutAction.FAIL,
            100,
            limit,
            32,
            true,
            false);
    long tick = Math.min(1000, Math.max(5, idleMs / 2));
    reaper =
        group
            .next()
            .scheduleWithFixedDelay(
                () ->
                    channels.forEach(
                        c ->
                            c.eventLoop()
                                .execute(
                                    () -> {
                                      long now = System.nanoTime();
                                      if (!Boolean.TRUE.equals(c.attr(LEASED).get())
                                          && (now - c.attr(USED).get()
                                                  >= TimeUnit.MILLISECONDS.toNanos(idleMs)
                                              || now - c.attr(CREATED).get()
                                                  >= TimeUnit.MILLISECONDS.toNanos(lifeMs)))
                                        c.close();
                                    })),
                tick,
                tick,
                TimeUnit.MILLISECONDS);
  }

  static NioSocketChannel newChannel(ResourceBudget.Permit permit) {
    return new NioSocketChannel() {
      @Override
      protected void doClose() throws Exception {
        try {
          super.doClose();
        } finally {
          permit.close();
        }
      }
    };
  }

  Future<Lease> acquire() {
    long began = System.nanoTime();
    Promise<Lease> result = group.next().newPromise();
    if (retired) {
      var cause = new IllegalStateException("endpoint retired");
      reportFailure(cause, began);
      result.setFailure(cause);
      return result;
    }
    waiting.incrementAndGet();
    Future<Channel> acquiring = pool.acquire();
    result.addListener(
        f -> {
          if (f.isCancelled()) acquiring.cancel(false);
        });
    acquiring.addListener(
        f -> {
          waiting.decrementAndGet();
          if (f.isSuccess()) {
            var lease = new Lease(acquiring.getNow());
            borrowed.incrementAndGet();
            if (retired) {
              lease.release(false);
              result.tryFailure(new IllegalStateException("endpoint retired"));
            } else if (!result.trySuccess(lease)) lease.release(false);
          } else {
            reportFailure(
                f.cause() == null ? new java.util.concurrent.CancellationException() : f.cause(),
                began);
            result.tryFailure(
                f.cause() == null ? new java.util.concurrent.CancellationException() : f.cause());
          }
        });
    return result;
  }

  private void reportFailure(Throwable cause, long began) {
    failures.accept(
        new Failure(
            cause,
            pool.acquiredChannelCount(),
            borrowed.get(),
            waiting.get(),
            releasing.get(),
            limit,
            retired,
            System.nanoTime() - began));
  }

  int acquiredSlots() {
    return pool.acquiredChannelCount();
  }

  final class Lease {
    final Channel channel;
    private final AtomicBoolean released = new AtomicBoolean();

    Lease(Channel channel) {
      this.channel = channel;
    }

    void release(boolean reusable) {
      if (!released.compareAndSet(false, true)) return;
      releasing.incrementAndGet();
      channel
          .eventLoop()
          .execute(
              () -> {
                if (channel.pipeline().get("exchange") != null)
                  channel.pipeline().remove("exchange");
                if (!reusable || retired) channel.close();
                borrowed.decrementAndGet();
                pool.release(channel)
                    .addListener(
                        f -> {
                          releasing.decrementAndGet();
                          if (!f.isSuccess()) channel.close();
                        });
              });
    }
  }

  public void close() {
    retired = true;
    reaper.cancel(false);
    pool.closeAsync();
  }
}
