package dev.kvgateway.cli;

import static org.junit.jupiter.api.Assertions.*;

import dev.kvgateway.api.*;
import dev.kvgateway.control.ControlNode;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.net.ServerSocket;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ControlClientTest {
  @TempDir Path dir;

  int port() throws Exception {
    try (var s = new ServerSocket(0)) {
      return s.getLocalPort();
    }
  }

  @Test
  void discoversLeaderAndSurfacesCasConflictWithoutRetry() throws Exception {
    var raft = new TreeMap<String, String>();
    var grpc = new TreeMap<String, String>();
    var nodes = new ArrayList<ControlNode>();
    for (int i = 0; i < 3; i++) {
      raft.put("n" + i, "localhost:" + port());
      grpc.put("n" + i, "localhost:" + port());
    }
    try {
      for (int i = 0; i < 3; i++)
        nodes.add(
            ControlNode.start(
                new Settings.Node(
                    "n" + i,
                    Settings.DEFAULT_GROUP,
                    raft,
                    grpc,
                    dir.resolve("n" + i),
                    Integer.parseInt(grpc.get("n" + i).split(":")[1]),
                    Integer.parseInt(raft.get("n" + i).split(":")[1]),
                    0)));
      try (var client = new ControlClient(new ArrayList<>(grpc.values()), Duration.ofSeconds(5))) {
        assertEquals(3, client.status().size());
        var result =
            client.put("cli-key", "hello".getBytes(java.nio.charset.StandardCharsets.UTF_8), 0);
        assertTrue(result.getRevision() > 0);
        assertEquals("hello", client.get("cli-key").getEntry().getValue().toStringUtf8());
        var failure =
            assertThrows(
                StatusRuntimeException.class, () -> client.put("cli-key", new byte[] {1}, 0));
        assertEquals(Status.Code.ABORTED, failure.getStatus().getCode());
        assertEquals(result.getRevision(), client.get("cli-key").getEntry().getModRevision());
        assertEquals(Settings.DEFAULT_GROUP, client.getConfig().getGroupId());
      }
    } finally {
      for (var n : nodes) n.close();
    }
  }
}
