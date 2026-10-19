package dev.kvgateway.gateway;

import dev.kvgateway.api.ServiceSpec;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;
import io.netty.util.concurrent.ScheduledFuture;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Bounded service waiting before a single upstream attempt; all selection runs on one executor. */
final class UpstreamDispatcher implements AutoCloseable {
  static final class RequestDeadlineExceeded extends TimeoutException {
    RequestDeadlineExceeded() {
      super("request deadline elapsed while waiting for upstream dispatch");
    }
  }

  static final class Rejected extends IllegalStateException {
    final RejectionReason reason;

    Rejected(RejectionReason reason) {
      super(reason.label());
      this.reason = reason;
    }
  }

  private record Candidate(String endpoint, EndpointPool pool) {}

  static final class Ticket {
    final ServiceSpec service;
    final long deadlineNanos;
    final List<Candidate> candidates;
    final Promise<EndpointPool.Lease> result;
    final AtomicBoolean counted = new AtomicBoolean();
    volatile String endpoint;
    volatile Object healthGeneration;
    ServiceState state;
    ScheduledFuture<?> expiry;
    boolean queued;

    Ticket(
        ServiceSpec service,
        long deadlineNanos,
        List<Candidate> candidates,
        Promise<EndpointPool.Lease> result) {
      this.service = service;
      this.deadlineNanos = deadlineNanos;
      this.candidates = candidates;
      this.result = result;
    }

    Future<EndpointPool.Lease> future() {
      return result;
    }
  }

  private static final class ServiceState {
    final String id;
    final ArrayDeque<Ticket> queue = new ArrayDeque<>();
    final Set<Ticket> acquiring = Collections.newSetFromMap(new IdentityHashMap<>());
    final Map<EndpointPool, Integer> reservations = new IdentityHashMap<>();
    int reserved;
    boolean draining;

    ServiceState(String id) {
      this.id = id;
    }
  }

  private final EventExecutor executor;
  private final PoolRegistry pools;
  private final HealthMonitor health;
  private final ConcurrentHashMap<String, AtomicInteger> cursors;
  private final Map<String, ServiceState> states = new HashMap<>();
  private final AtomicInteger queued = new AtomicInteger(), reserved = new AtomicInteger();
  private volatile boolean closed;

  UpstreamDispatcher(
      EventExecutor executor,
      PoolRegistry pools,
      HealthMonitor health,
      ConcurrentHashMap<String, AtomicInteger> cursors) {
    this.executor = executor;
    this.pools = pools;
    this.health = health;
    this.cursors = cursors;
    health.onChange(this::reconcile);
  }

  Ticket acquire(ServiceSpec service, long deadlineNanos) {
    var candidates =
        service.getEndpointsList().stream()
            .map(endpoint -> new Candidate(endpoint, pools.get(service.getServiceId(), endpoint)))
            .toList();
    var ticket = new Ticket(service, deadlineNanos, candidates, executor.newPromise());
    if (closed) {
      ticket.result.setFailure(new Rejected(RejectionReason.POOL_RETIRED));
      return ticket;
    }
    for (; ; ) {
      int old = queued.get();
      if (old >= 256) {
        ticket.result.setFailure(new Rejected(RejectionReason.ADMISSION_LIMIT));
        return ticket;
      }
      if (queued.compareAndSet(old, old + 1)) break;
    }
    ticket.counted.set(true);
    ticket.result.addListener(
        f -> {
          if (f.isCancelled()) run(() -> cancel(ticket));
        });
    try {
      run(() -> enqueue(ticket));
    } catch (RuntimeException failure) {
      uncount(ticket);
      ticket.result.tryFailure(failure);
    }
    return ticket;
  }

  private void run(Runnable task) {
    if (executor.inEventLoop()) task.run();
    else executor.execute(task);
  }

  private void enqueue(Ticket ticket) {
    if (ticket.result.isDone()) {
      uncount(ticket);
      return;
    }
    if (closed) {
      fail(ticket, new Rejected(RejectionReason.POOL_RETIRED));
      return;
    }
    if (expired(ticket)) {
      fail(ticket, new RequestDeadlineExceeded());
      return;
    }
    // Preserve initial no-healthy and missing-pool response contracts before creating state.
    if (ticket.candidates.stream().noneMatch(c -> health.healthy(c.endpoint))) {
      fail(ticket, new Rejected(RejectionReason.NO_HEALTHY_ENDPOINT));
      return;
    }
    var state = states.computeIfAbsent(ticket.service.getServiceId(), ServiceState::new);
    ticket.state = state;
    if (state.queue.size() >= ticket.service.getMaxInflight()) {
      fail(ticket, new Rejected(RejectionReason.ADMISSION_LIMIT));
      return;
    }
    ticket.queued = true;
    state.queue.addLast(ticket);
    ticket.expiry =
        executor.schedule(
            () -> expire(ticket),
            Math.max(0, ticket.deadlineNanos - System.nanoTime()),
            TimeUnit.NANOSECONDS);
    drain(state);
  }

  private boolean expired(Ticket ticket) {
    return System.nanoTime() - ticket.deadlineNanos >= 0;
  }

  private void expire(Ticket ticket) {
    if (!ticket.result.isDone()) fail(ticket, new RequestDeadlineExceeded());
  }

  private void cancel(Ticket ticket) {
    removeQueued(ticket);
    stopExpiry(ticket);
    if (ticket.state != null) drain(ticket.state);
    // A dispatched native acquisition is intentionally not cancelled. Its eventual lease is
    // closed below and keeps its reservation until native pool bookkeeping has completed.
  }

  private void uncount(Ticket ticket) {
    if (ticket.counted.compareAndSet(true, false)) queued.decrementAndGet();
  }

  private void removeQueued(Ticket ticket) {
    if (ticket.queued) {
      ticket.state.queue.remove(ticket);
      ticket.queued = false;
    }
    uncount(ticket);
  }

  private void stopExpiry(Ticket ticket) {
    if (ticket.expiry != null) ticket.expiry.cancel(false);
  }

  private void fail(Ticket ticket, Throwable cause) {
    removeQueued(ticket);
    stopExpiry(ticket);
    ticket.result.tryFailure(cause);
    if (ticket.state != null) drain(ticket.state);
  }

  private Candidate retiredCandidate(Ticket ticket) {
    for (var candidate : ticket.candidates)
      if (candidate.pool != null
          && (candidate.pool.retired()
              || pools.get(ticket.service.getServiceId(), candidate.endpoint) != candidate.pool))
        return candidate;
    return null;
  }

  private void retired(Ticket ticket, Candidate candidate) {
    removeQueued(ticket);
    stopExpiry(ticket);
    ticket.endpoint = candidate.endpoint;
    ticket.healthGeneration = health.generation(candidate.endpoint);
    // A removed registry entry can precede its retirement flag. Never start an unreserved
    // native acquisition just to collect diagnostics in that window.
    if (!candidate.pool.retired()) {
      fail(ticket, new Rejected(RejectionReason.POOL_RETIRED));
      return;
    }
    // An already retired wrapper reports its real failure without a native pool acquisition.
    candidate
        .pool
        .acquire()
        .addListener(
            f ->
                run(
                    () -> {
                      if (f.isSuccess()) {
                        ((EndpointPool.Lease) f.getNow()).release(false);
                        ticket.result.tryFailure(new Rejected(RejectionReason.POOL_RETIRED));
                      } else ticket.result.tryFailure(f.cause());
                    }));
    if (ticket.state != null) drain(ticket.state);
  }

  private Candidate choose(Ticket ticket, ServiceState state) {
    int size = ticket.candidates.size();
    int start =
        Math.floorMod(
            cursors.computeIfAbsent(state.id, id -> new AtomicInteger()).getAndIncrement(),
            Math.max(1, size));
    Candidate best = null;
    int least = Integer.MAX_VALUE;
    for (int i = 0; i < size; i++) {
      var candidate = ticket.candidates.get((start + i) % size);
      if (candidate.pool == null || !health.healthy(candidate.endpoint)) continue;
      int count = state.reservations.getOrDefault(candidate.pool, 0);
      if (count < candidate.pool.limit && count < least) {
        best = candidate;
        least = count;
      }
    }
    return best;
  }

  private void drain(ServiceState state) {
    if (state.draining) return;
    state.draining = true;
    try {
      while (!state.queue.isEmpty()) {
        var ticket = state.queue.peekFirst();
        if (ticket.result.isDone()) {
          cancel(ticket);
          continue;
        }
        if (closed) {
          fail(ticket, new Rejected(RejectionReason.POOL_RETIRED));
          continue;
        }
        if (expired(ticket)) {
          fail(ticket, new RequestDeadlineExceeded());
          continue;
        }
        var retired = retiredCandidate(ticket);
        if (retired != null) {
          retired(ticket, retired);
          continue;
        }
        if (ticket.candidates.stream().noneMatch(c -> health.healthy(c.endpoint))) {
          fail(ticket, new Rejected(RejectionReason.NO_HEALTHY_ENDPOINT));
          continue;
        }
        if (ticket.candidates.stream().noneMatch(c -> c.pool != null)) {
          fail(ticket, new Rejected(RejectionReason.POOL_UNAVAILABLE));
          continue;
        }
        var candidate = choose(ticket, state);
        if (candidate == null) break;
        removeQueued(ticket);
        ticket.endpoint = candidate.endpoint;
        ticket.healthGeneration = health.generation(candidate.endpoint);
        state.reservations.merge(candidate.pool, 1, Integer::sum);
        state.reserved++;
        reserved.incrementAndGet();
        state.acquiring.add(ticket);
        candidate
            .pool
            .acquire()
            .addListener(
                f ->
                    run(
                        () -> {
                          state.acquiring.remove(ticket);
                          stopExpiry(ticket);
                          if (f.isSuccess()) {
                            var lease = (EndpointPool.Lease) f.getNow();
                            lease
                                .returned()
                                .addListener(returned -> run(() -> release(state, candidate.pool)));
                            if (expired(ticket))
                              ticket.result.tryFailure(new RequestDeadlineExceeded());
                            if (!ticket.result.trySuccess(lease)) lease.release(false);
                          } else {
                            release(state, candidate.pool);
                            ticket.result.tryFailure(f.cause());
                          }
                        }));
      }
    } finally {
      state.draining = false;
      discardEmpty(state);
    }
  }

  private void release(ServiceState state, EndpointPool pool) {
    int remaining = state.reservations.get(pool) - 1;
    if (remaining == 0) state.reservations.remove(pool);
    else state.reservations.put(pool, remaining);
    state.reserved--;
    reserved.decrementAndGet();
    drain(state);
  }

  private void discardEmpty(ServiceState state) {
    if (state.reserved == 0 && state.queue.isEmpty()) states.remove(state.id, state);
  }

  void reconcile() {
    if (closed) return;
    run(
        () -> {
          for (var state : List.copyOf(states.values())) {
            // Retirement must also fail old revisions behind a still-busy FIFO head promptly.
            for (var ticket : List.copyOf(state.queue)) {
              if (!ticket.queued) continue;
              var retired = retiredCandidate(ticket);
              if (retired != null) retired(ticket, retired);
            }
            drain(state);
          }
        });
  }

  int queued() {
    return queued.get();
  }

  int reserved() {
    return reserved.get();
  }

  public void close() {
    closed = true;
    health.onChange(() -> {});
    Runnable stop =
        () -> {
          for (var state : List.copyOf(states.values())) {
            for (var ticket : List.copyOf(state.queue))
              fail(ticket, new Rejected(RejectionReason.POOL_RETIRED));
            for (var ticket : List.copyOf(state.acquiring)) {
              stopExpiry(ticket);
              ticket.result.tryFailure(new Rejected(RejectionReason.POOL_RETIRED));
            }
          }
        };
    if (executor.inEventLoop()) stop.run();
    else executor.submit(stop).syncUninterruptibly();
  }
}
