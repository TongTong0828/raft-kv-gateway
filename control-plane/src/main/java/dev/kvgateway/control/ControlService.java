package dev.kvgateway.control;
import dev.kvgateway.api.*;
import com.google.protobuf.Empty;
import io.grpc.*;
import io.grpc.stub.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.function.Function;

final class ControlService extends ControlGrpc.ControlImplBase {
  private final ControlNode node;private final ConfigPublisher publisher;
  ControlService(ControlNode node,ConfigPublisher publisher){this.node=node;this.publisher=publisher;}
  private Duration timeout(){var d=Context.current().getDeadline();return Duration.ofMillis(d==null?3000:Math.max(1,Math.min(3000,d.timeRemaining(TimeUnit.MILLISECONDS))));}
  private static Status status(Throwable error){while((error instanceof CompletionException||error instanceof ExecutionException)&&error.getCause()!=null)error=error.getCause();if(error instanceof StatusRuntimeException s)return s.getStatus();if(error instanceof TimeoutException)return Status.DEADLINE_EXCEEDED.withDescription("operation timed out; a submitted mutation may have committed");return Status.UNAVAILABLE.withDescription("Raft request unavailable; a submitted mutation may have committed");}
  private static void check(CommandResult r){switch(r.getCode()){
    case OK->{}case NOT_FOUND->throw Status.NOT_FOUND.asRuntimeException();case CONFLICT->throw Status.ABORTED.withDescription("revision conflict; current_revision="+r.getCurrentRevision()).asRuntimeException();case INVALID->throw Status.INVALID_ARGUMENT.withDescription(r.getMessage()).asRuntimeException();case CAPACITY->throw Status.RESOURCE_EXHAUSTED.withDescription(r.getMessage()).asRuntimeException();default->throw Status.INTERNAL.asRuntimeException();}}
  private <T> void reply(CompletableFuture<CommandResult> future,StreamObserver<T> observer,Function<CommandResult,T> mapping){
    if(observer instanceof ServerCallStreamObserver<T> s)s.setOnCancelHandler(()->future.cancel(false));
    future.whenComplete((r,e)->{if(observer instanceof ServerCallStreamObserver<T> s&&s.isCancelled())return;try{if(e!=null){observer.onError(status(e).asRuntimeException());return;}check(r);observer.onNext(mapping.apply(r));observer.onCompleted();}catch(Throwable x){observer.onError(status(x).asRuntimeException());}});
  }
  @Override public void get(GetRequest q,StreamObserver<GetResponse> o){reply(node.read(Command.newBuilder().setGet(q).build(),timeout()),o,r->GetResponse.newBuilder().setEntry(r.getEntry()).setStateRevision(r.getStateRevision()).build());}
  @Override public void put(PutRequest q,StreamObserver<MutationResponse> o){reply(node.write(Command.newBuilder().setPut(q).build(),timeout()),o,r->MutationResponse.newBuilder().setEntry(r.getEntry()).setRevision(r.getStateRevision()).build());}
  @Override public void delete(DeleteRequest q,StreamObserver<MutationResponse> o){reply(node.write(Command.newBuilder().setDelete(q).build(),timeout()),o,r->MutationResponse.newBuilder().setRevision(r.getStateRevision()).build());}
  @Override public void getConfig(Empty q,StreamObserver<ConfigSnapshot> o){reply(node.read(Command.newBuilder().setGetConfig(true).build(),timeout()),o,r->r.getConfig().toBuilder().setGroupId(node.status().getGroupId()).build());}
  @Override public void getStatus(Empty q,StreamObserver<StatusResponse> o){o.onNext(node.status());o.onCompleted();}
  @Override public void watchConfig(Empty q,StreamObserver<ConfigSnapshot> o){var duration=timeout();publisher.subscribe((ServerCallStreamObserver<ConfigSnapshot>)o,()->node.read(Command.newBuilder().setGetConfig(true).build(),duration));}
}
