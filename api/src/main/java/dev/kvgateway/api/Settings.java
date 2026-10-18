package dev.kvgateway.api;
import java.nio.file.Path;
import java.util.*;
public final class Settings {
  public static final String DEFAULT_GROUP="11111111-1111-1111-1111-111111111111";
  public record Node(String id,String groupId,Map<String,String> raftPeers,Map<String,String> grpcPeers,Path dataDir,int grpcPort,int raftPort,int adminPort) {}
  public record Gateway(String groupId,List<String> seeds,int port,int adminPort) {}
  public static Settings from(String[] args) { return from(args,System.getenv()); }
  private final Map<String,String> values;
  private Settings(Map<String,String> values) { this.values=Map.copyOf(values); }
  public static Settings from(String[] args,Map<String,String> env) {
    var map=new HashMap<String,String>();
    env.forEach((k,v)->{if(k.startsWith("KV_"))map.put(k.substring(3).toLowerCase(Locale.ROOT).replace('_','-'),v);});
    for(int i=0;i<args.length;i+=2) { if(!args[i].startsWith("--")||i+1==args.length||args[i+1].startsWith("--")) throw new IllegalArgumentException("expected --name value");map.put(args[i].substring(2),args[i+1]); }
    return new Settings(map);
  }
  public String get(String key,String fallback) { return values.getOrDefault(key,fallback); }
  public int integer(String key,int fallback,int min,int max) {
    try { int n=Integer.parseInt(get(key,Integer.toString(fallback))); if(n<min||n>max)throw new NumberFormatException();return n; }
    catch(NumberFormatException e) {throw new IllegalArgumentException(key+" must be between "+min+" and "+max);}
  }
  private Map<String,String> peers(String key) {
    var map=new TreeMap<String,String>();
    for(var part:get(key,"").split(",")) {var p=part.split("=",2);if(p.length!=2||p[0].isBlank()||map.put(p[0],ServiceSpecs.endpoint(p[1]))!=null)throw new IllegalArgumentException("invalid "+key);}
    if(map.size()!=3)throw new IllegalArgumentException("exactly three peers required");return Map.copyOf(map);
  }
  private String group() { return UUID.fromString(get("group-id",DEFAULT_GROUP)).toString(); }
  public Node node() {
    var raft=peers("raft-peers");var grpc=peers("grpc-peers");var id=get("node-id","");
    if(!raft.containsKey(id)||!raft.keySet().equals(grpc.keySet()))throw new IllegalArgumentException("peer IDs must agree and contain node-id");
    return new Node(id,group(),raft,grpc,Path.of(get("data-dir","data/"+id)),integer("grpc-port",50051,1,65535),integer("raft-port",6000,1,65535),integer("admin-port",9101,1,65535));
  }
  public Gateway gateway() {
    var seeds=Arrays.stream(get("control-seeds","localhost:15051,localhost:15052,localhost:15053").split(",")).map(ServiceSpecs::endpoint).toList();
    if(seeds.isEmpty())throw new IllegalArgumentException("control seeds required");
    return new Gateway(group(),seeds,integer("port",8080,1,65535),integer("admin-port",9090,1,65535));
  }
}
