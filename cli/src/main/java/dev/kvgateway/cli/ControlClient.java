package dev.kvgateway.cli;

import com.google.protobuf.ByteString;
import com.google.protobuf.Empty;
import dev.kvgateway.api.*;
import io.grpc.*;
import io.grpc.stub.StreamObserver;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Application client. Mutations are issued once after read-validated leader discovery. */
public final class ControlClient implements AutoCloseable {
  private final List<ManagedChannel> channels;
  private final long timeoutMs;

  public ControlClient(List<String> seeds, Duration timeout) {
    if (seeds.isEmpty() || seeds.size() > 16 || timeout.isNegative() || timeout.isZero())
      throw new IllegalArgumentException("invalid seeds or timeout");
    timeoutMs = timeout.toMillis();
    channels =
        seeds.stream()
            .map(ServiceSpecs::endpoint)
            .map(
                s ->
                    ManagedChannelBuilder.forTarget(s)
                        .usePlaintext()
                        .maxInboundMessageSize(8 * 1024 * 1024)
                        .build())
            .toList();
  }

  private ControlGrpc.ControlBlockingStub stub(ManagedChannel channel, long ms) {
    return ControlGrpc.newBlockingStub(channel)
        .withDeadlineAfter(Math.max(1, ms), TimeUnit.MILLISECONDS);
  }

  private ManagedChannel leader() {
    long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
    while (System.nanoTime() < end) {
      for (var channel : channels) {
        long remaining = TimeUnit.NANOSECONDS.toMillis(end - System.nanoTime());
        if (remaining <= 0) break;
        try {
          var s = stub(channel, Math.min(remaining, 500)).getStatus(Empty.getDefaultInstance());
          if (s.getRole().equals("LEADER")) {
            stub(channel, Math.min(remaining, 1000)).getConfig(Empty.getDefaultInstance());
            return channel;
          }
        } catch (StatusRuntimeException ignored) {
        }
      }
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw Status.CANCELLED.asRuntimeException();
      }
    }
    throw Status.UNAVAILABLE
        .withDescription("no read-validated leader within discovery deadline")
        .asRuntimeException();
  }

  public GetResponse get(String key) {
    return stub(leader(), timeoutMs).get(GetRequest.newBuilder().setKey(key).build());
  }

  public MutationResponse put(String key, byte[] value, long expected) {
    return stub(leader(), timeoutMs)
        .put(
            PutRequest.newBuilder()
                .setKey(key)
                .setValue(ByteString.copyFrom(value))
                .setExpectedRevision(expected)
                .build());
  }

  public MutationResponse delete(String key, long expected) {
    return stub(leader(), timeoutMs)
        .delete(DeleteRequest.newBuilder().setKey(key).setExpectedRevision(expected).build());
  }

  public ConfigSnapshot getConfig() {
    return stub(leader(), timeoutMs).getConfig(Empty.getDefaultInstance());
  }

  public List<StatusResponse> status() {
    var statuses = new ArrayList<StatusResponse>();
    for (var c : channels)
      try {
        statuses.add(stub(c, Math.min(timeoutMs, 1000)).getStatus(Empty.getDefaultInstance()));
      } catch (StatusRuntimeException ignored) {
      }
    return List.copyOf(statuses);
  }

  public Context.CancellableContext watch(StreamObserver<ConfigSnapshot> observer) {
    var c = leader();
    var context = Context.current().withCancellation();
    context.run(() -> ControlGrpc.newStub(c).watchConfig(Empty.getDefaultInstance(), observer));
    return context;
  }

  @Override
  public void close() {
    for (var c : channels) c.shutdownNow();
    for (var c : channels)
      try {
        c.awaitTermination(2, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
  }
}
