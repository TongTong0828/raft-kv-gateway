package dev.kvgateway.control;
import com.google.protobuf.ByteString;
import dev.kvgateway.api.*;
import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
class KvStoreTest {
  @org.junit.jupiter.api.Test void notificationFailureCannotChangeCommittedOutcome(){
    var store=new KvStore();store.onChange(()->{throw new IllegalArgumentException("subscriber failed");});
    var command=Command.newBuilder().setPut(PutRequest.newBuilder().setKey("x").setValue(com.google.protobuf.ByteString.copyFromUtf8("v"))).build();
    assertEquals(CommandResult.Code.OK,store.apply(command,10).getCode());assertEquals(10,store.query(Command.newBuilder().setGet(GetRequest.newBuilder().setKey("x")).build()).getEntry().getModRevision());
  }
  static Command put(String key,String value,long expected) {return Command.newBuilder().setPut(PutRequest.newBuilder().setKey(key).setValue(ByteString.copyFromUtf8(value)).setExpectedRevision(expected)).build();}
  static Command get(String key) {return Command.newBuilder().setGet(GetRequest.newBuilder().setKey(key)).build();}
  @Test void concurrentCasHasOneWinner() throws Exception {
    var s=new KvStore(); assertEquals(10,s.apply(put("a","old",0),10).getEntry().getModRevision());
    try(var threads=Executors.newFixedThreadPool(2)) {
      var a=threads.submit(()->s.apply(put("a","one",10),20));var b=threads.submit(()->s.apply(put("a","two",10),30));
      assertEquals(1,java.util.stream.Stream.of(a.get(),b.get()).filter(r->r.getCode()==CommandResult.Code.OK).count());
    }
  }
  @Test void deleteRecreateRejectsOldRevision() {
    var s=new KvStore();s.apply(put("a","old",0),10);
    assertEquals(CommandResult.Code.OK,s.apply(Command.newBuilder().setDelete(DeleteRequest.newBuilder().setKey("a").setExpectedRevision(10)).build(),20).getCode());
    assertEquals(30,s.apply(put("a","new",0),30).getEntry().getModRevision());
    assertEquals(CommandResult.Code.CONFLICT,s.apply(put("a","stale",10),40).getCode());
    assertEquals("new",s.query(get("a")).getEntry().getValue().toStringUtf8());assertEquals(30,s.query(get("a")).getStateRevision());
  }
  @Test void invalidAndOverBudgetMutationDoesNotChangeState() {
    var s=new KvStore();s.apply(put("a","good",0),10);
    assertEquals(CommandResult.Code.INVALID,s.apply(put("a","x".repeat(65537),10),20).getCode());
    assertEquals("good",s.query(get("a")).getEntry().getValue().toStringUtf8());assertEquals(10,s.query(get("a")).getStateRevision());
    for(int i=0;i<63;i++)assertEquals(CommandResult.Code.OK,s.apply(put("b"+i,"x".repeat(65536),0),30+i).getCode());
    assertEquals(CommandResult.Code.CAPACITY,s.apply(put("overflow","x".repeat(65536),0),100).getCode());
    assertEquals(CommandResult.Code.NOT_FOUND,s.query(get("overflow")).getCode());
  }
  @Test void servicePrefixConflictIsAtomic() {
    var s=new KvStore();var a=ServiceSpec.newBuilder().setServiceId("one").setPathPrefix("/same").addEndpoints("a:80").build();
    var b=a.toBuilder().setServiceId("two").build();
    s.apply(Command.newBuilder().setPut(PutRequest.newBuilder().setKey("/services/one").setValue(a.toByteString())).build(),10);
    var r=s.apply(Command.newBuilder().setPut(PutRequest.newBuilder().setKey("/services/two").setValue(b.toByteString())).build(),20);
    assertEquals(CommandResult.Code.INVALID,r.getCode());assertEquals(1,s.configuration("group").getServicesCount());assertEquals(10,s.configuration("group").getRevision());
  }
}
