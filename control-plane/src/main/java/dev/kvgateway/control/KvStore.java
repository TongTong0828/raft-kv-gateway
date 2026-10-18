package dev.kvgateway.control;
import dev.kvgateway.api.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
public final class KvStore {
  private NavigableMap<String,Entry> entries=new TreeMap<>();
  private long revision;
  private Runnable onChange=()->{};
  public synchronized void onChange(Runnable listener) {onChange=Objects.requireNonNull(listener);}
  private CommandResult.Builder result(CommandResult.Code code) {return CommandResult.newBuilder().setCode(code).setStateRevision(revision);}
  private static void key(String key) {if(key.isEmpty()||key.getBytes(StandardCharsets.UTF_8).length>256)throw new IllegalArgumentException("key must have 1..256 UTF-8 bytes");}
  private static List<ServiceSpec> services(Map<String,Entry> map) {
    var list=new ArrayList<ServiceSpec>();
    try { for(var e:map.values())if(e.getKey().startsWith("/services/")) {
      var s=ServiceSpecs.normalize(ServiceSpec.parseFrom(e.getValue()));
      if(!e.getKey().equals("/services/"+s.getServiceId()))throw new IllegalArgumentException("service key and ID differ");list.add(s);
    }} catch(com.google.protobuf.InvalidProtocolBufferException e) {throw new IllegalArgumentException("invalid ServiceSpec encoding");}
    ServiceSpecs.validateAll(list);return list;
  }
  private static void capacity(Map<String,Entry> map) {
    if(map.size()>1024)throw new CapacityException();
    long bytes=0;for(var e:map.values())bytes+=e.getKey().getBytes(StandardCharsets.UTF_8).length+e.getValue().size();
    if(bytes>4*1024*1024)throw new CapacityException();
  }
  private static final class CapacityException extends RuntimeException {}
  public synchronized CommandResult apply(Command c,long index) {
    try {
      boolean put=c.hasPut();if(!put&&!c.hasDelete())return result(CommandResult.Code.INVALID).setMessage("mutation required").build();
      String k=put?c.getPut().getKey():c.getDelete().getKey();key(k);
      long expected=put?c.getPut().getExpectedRevision():c.getDelete().getExpectedRevision();
      if(expected<0||(!put&&expected==0)||index<=0)throw new IllegalArgumentException("invalid revision");
      var old=entries.get(k);long actual=old==null?0:old.getModRevision();
      if(actual!=expected)return result(CommandResult.Code.CONFLICT).setCurrentRevision(actual).setMessage("revision conflict").build();
      if(!put&&old==null)return result(CommandResult.Code.NOT_FOUND).build();
      var candidate=new TreeMap<>(entries);Entry changed=null;
      if(put) {
        var value=c.getPut().getValue();if(value.size()>65536)throw new IllegalArgumentException("value exceeds 64 KiB");
        if(k.startsWith("/services/")) {try {value=ServiceSpecs.normalize(ServiceSpec.parseFrom(value)).toByteString();}catch(com.google.protobuf.InvalidProtocolBufferException e){throw new IllegalArgumentException("invalid ServiceSpec encoding");}}
        changed=Entry.newBuilder().setKey(k).setValue(value).setModRevision(index).build();candidate.put(k,changed);
      }else candidate.remove(k);
      capacity(candidate);var specs=services(candidate);
      if(ConfigSnapshot.newBuilder().setGroupId(Settings.DEFAULT_GROUP).setRevision(index).addAllServices(specs).build().getSerializedSize()>4*1024*1024)throw new CapacityException();
      entries=candidate;revision=index;onChange.run();
      var out=result(CommandResult.Code.OK);if(changed!=null)out.setEntry(changed);return out.build();
    }catch(CapacityException e){return result(CommandResult.Code.CAPACITY).setMessage("KV capacity exceeded").build();}
    catch(IllegalArgumentException e){return result(CommandResult.Code.INVALID).setMessage(e.getMessage()).build();}
  }
  public synchronized CommandResult query(Command c) {
    if(c.hasGet()) {try {key(c.getGet().getKey());}catch(IllegalArgumentException e){return result(CommandResult.Code.INVALID).setMessage(e.getMessage()).build();}
      var e=entries.get(c.getGet().getKey());return e==null?result(CommandResult.Code.NOT_FOUND).build():result(CommandResult.Code.OK).setEntry(e).build();}
    if(c.hasGetConfig())return result(CommandResult.Code.OK).setConfig(configuration(Settings.DEFAULT_GROUP)).build();
    return result(CommandResult.Code.INVALID).setMessage("query required").build();
  }
  public synchronized StoreSnapshot snapshot(long term,long index) {return StoreSnapshot.newBuilder().setFormatVersion(1).setLastTerm(term).setLastIndex(index).setStateRevision(revision).addAllEntries(entries.values()).build();}
  public synchronized void restore(StoreSnapshot s) {
    if(s.getFormatVersion()!=1||s.getLastIndex()<0||s.getStateRevision()<0||s.getStateRevision()>s.getLastIndex())throw new IllegalArgumentException("invalid snapshot metadata");
    var map=new TreeMap<String,Entry>();
    for(var e:s.getEntriesList()) {key(e.getKey());if(e.getValue().size()>65536||e.getModRevision()<=0||e.getModRevision()>s.getStateRevision()||map.put(e.getKey(),e)!=null)throw new IllegalArgumentException("invalid snapshot entry");}
    capacity(map);services(map);entries=map;revision=s.getStateRevision();onChange.run();
  }
  public synchronized ConfigSnapshot configuration(String group) {return ConfigSnapshot.newBuilder().setGroupId(group).setRevision(revision).addAllServices(services(entries)).build();}
}
