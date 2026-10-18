package dev.kvgateway.api;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;
class SettingsTest {
  @Test void parsesExplicitNodeSettings() {
    var s=Settings.from(new String[]{"--node-id","c1","--raft-peers","c0=a:6000,c1=b:6000,c2=c:6000","--grpc-peers","c0=a:50051,c1=b:50051,c2=c:50051","--data-dir","/data/test"},Map.of());
    var n=s.node();assertEquals("c1",n.id());assertEquals("b:6000",n.raftPeers().get("c1"));assertEquals(Path.of("/data/test"),n.dataDir());assertEquals(50051,n.grpcPort());
  }
  @Test void flagsOverrideEnvironmentAndInvalidNumbersFail() {
    var s=Settings.from(new String[]{"--port","8082"},Map.of("KV_PORT","8081"));assertEquals(8082,s.integer("port",8080,1,65535));
    assertThrows(IllegalArgumentException.class,()->Settings.from(new String[]{"--port","0"},Map.of()).integer("port",8080,1,65535));
    assertThrows(IllegalArgumentException.class,()->Settings.from(new String[]{"--port"},Map.of()));
  }
}
