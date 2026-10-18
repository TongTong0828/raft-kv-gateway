package dev.kvgateway.control;

import dev.kvgateway.api.*;
import org.apache.ratis.protocol.*;
import org.apache.ratis.server.RaftServer;
import org.apache.ratis.server.protocol.TermIndex;
import org.apache.ratis.server.storage.*;
import org.apache.ratis.statemachine.*;
import org.apache.ratis.statemachine.impl.*;
import org.apache.ratis.thirdparty.com.google.protobuf.ByteString;
import org.apache.ratis.util.*;
import java.io.*;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

public final class KvStateMachine extends BaseStateMachine {
  private final KvStore store=new KvStore();
  private final SimpleStateMachineStorage storage=new SimpleStateMachineStorage();
  private final AtomicLong installations=new AtomicLong();
  public KvStore store(){return store;}
  public long installations(){return installations.get();}
  @Override public synchronized void initialize(RaftServer server,RaftGroupId group,RaftStorage raftStorage)throws IOException {
    super.initialize(server,group,raftStorage);
    getLifeCycle().startAndTransition(()->{
      var dir=raftStorage.getStorageDir().getStateMachineDir().toPath();Files.createDirectories(dir);
      try(var files=Files.list(dir)){for(var p:files.filter(p->p.getFileName().toString().matches("snapshot\\.[0-9]+_[0-9]+")&&!Files.exists(Path.of(p+".md5"))).toList())Files.move(p,Path.of(p+".incomplete"),StandardCopyOption.REPLACE_EXISTING);}
      storage.init(raftStorage);loadSnapshot(storage.getLatestSnapshot());
    },IOException.class);
  }
  private synchronized void loadSnapshot(SingleFileSnapshotInfo info)throws IOException {
    if(info==null)return;
    var s=SnapshotFiles.read(info.getFile().getPath());
    if(s.getLastTerm()!=info.getTerm()||s.getLastIndex()!=info.getIndex())throw new IOException("snapshot filename and metadata differ");
    store.restore(s);setLastAppliedTermIndex(TermIndex.valueOf(s.getLastTerm(),s.getLastIndex()));
  }
  @Override public synchronized void pause(){if(getLifeCycleState()==LifeCycle.State.RUNNING){getLifeCycle().transition(LifeCycle.State.PAUSING);getLifeCycle().transition(LifeCycle.State.PAUSED);}}
  @Override public synchronized void reinitialize()throws IOException {
    getLifeCycle().startAndTransition(()->loadSnapshot(storage.loadLatestSnapshot()),IOException.class);installations.incrementAndGet();
  }
  @Override public StateMachineStorage getStateMachineStorage(){return storage;}
  @Override public synchronized void notifyTermIndexUpdated(long term,long index){super.notifyTermIndexUpdated(term,index);}
  @Override public synchronized CompletableFuture<Message> applyTransaction(TransactionContext trx){
    var e=trx.getLogEntry();CommandResult result;
    try {result=store.apply(Command.parseFrom(e.getStateMachineLogEntry().getLogData().toByteArray()),e.getIndex());}
    catch(com.google.protobuf.InvalidProtocolBufferException ex){result=CommandResult.newBuilder().setCode(CommandResult.Code.INVALID).setMessage("invalid command encoding").build();}
    updateLastAppliedTermIndex(e.getTerm(),e.getIndex());return CompletableFuture.completedFuture(message(result));
  }
  @Override public CompletableFuture<Message> query(Message request){
    try {return CompletableFuture.completedFuture(message(store.query(Command.parseFrom(request.getContent().toByteArray()))));}
    catch(IOException e){return CompletableFuture.failedFuture(e);}
  }
  private static Message message(CommandResult value){return Message.valueOf(ByteString.copyFrom(value.toByteArray()));}
  @Override public long takeSnapshot()throws IOException {
    StoreSnapshot copy;TermIndex last;
    synchronized(this){last=getLastAppliedTermIndex();if(last==null||last.getIndex()<0)return -1;copy=store.snapshot(last.getTerm(),last.getIndex());}
    var file=storage.getSnapshotFile(last.getTerm(),last.getIndex());SnapshotFiles.write(file.toPath(),copy);
    var md5=MD5FileUtil.computeMd5ForFile(file);storage.updateLatestSnapshot(new SingleFileSnapshotInfo(new FileInfo(file.toPath(),md5),last));return last.getIndex();
  }
  @Override public synchronized void close()throws IOException {getLifeCycle().checkStateAndClose(()->{});}
}
