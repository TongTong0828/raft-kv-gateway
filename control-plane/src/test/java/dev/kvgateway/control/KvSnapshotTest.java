package dev.kvgateway.control;

import static org.junit.jupiter.api.Assertions.*;

import dev.kvgateway.api.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KvSnapshotTest {
  @TempDir Path dir;

  @Test
  void snapshotRestoresValuesAndRevisions() throws Exception {
    var a = new KvStore();
    a.apply(KvStoreTest.put("x", "saved", 0), 10);
    var file = dir.resolve("snapshot.2_20");
    SnapshotFiles.write(file, a.snapshot(2, 20));
    var b = new KvStore();
    b.restore(SnapshotFiles.read(file));
    assertEquals("saved", b.query(KvStoreTest.get("x")).getEntry().getValue().toStringUtf8());
    assertEquals(10, b.query(KvStoreTest.get("x")).getEntry().getModRevision());
    assertEquals(10, b.configuration("g").getRevision());
    assertEquals(20, SnapshotFiles.read(file).getLastIndex());
  }

  @Test
  void corruptOrPartialSnapshotCannotReplaceValidState() throws Exception {
    var a = new KvStore();
    a.apply(KvStoreTest.put("x", "good", 0), 10);
    var file = dir.resolve("snapshot.1_10");
    SnapshotFiles.write(file, a.snapshot(1, 10));
    Files.write(file, new byte[] {1, 2, 3});
    assertThrows(java.io.IOException.class, () -> SnapshotFiles.read(file));
    assertThrows(
        IllegalArgumentException.class,
        () -> a.restore(StoreSnapshot.newBuilder().setFormatVersion(99).build()));
    assertEquals("good", a.query(KvStoreTest.get("x")).getEntry().getValue().toStringUtf8());
  }
}
