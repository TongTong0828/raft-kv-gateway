package dev.kvgateway.control;

import dev.kvgateway.api.StoreSnapshot;
import java.io.*;
import java.nio.file.*;
import org.apache.ratis.util.AtomicFileOutputStream;
import org.apache.ratis.util.MD5FileUtil;

public final class SnapshotFiles {
  private SnapshotFiles() {}

  public static void write(Path file, StoreSnapshot snapshot) throws IOException {
    Files.createDirectories(file.getParent());
    var stream = new AtomicFileOutputStream(file.toFile());
    try {
      snapshot.writeTo(stream);
      stream.close();
    } catch (Throwable e) {
      stream.abort();
      throw e;
    }
    MD5FileUtil.saveMD5File(file.toFile(), MD5FileUtil.computeMd5ForFile(file.toFile()));
    try (var directory =
        java.nio.channels.FileChannel.open(file.getParent(), StandardOpenOption.READ)) {
      directory.force(true);
    }
  }

  public static StoreSnapshot read(Path file) throws IOException {
    if (Files.size(file) > 5 * 1024 * 1024) throw new IOException("snapshot exceeds limit");
    MD5FileUtil.verifySavedMD5(file.toFile(), MD5FileUtil.computeMd5ForFile(file.toFile()));
    var value = StoreSnapshot.parseFrom(Files.readAllBytes(file));
    try {
      new KvStore().restore(value);
    } catch (RuntimeException e) {
      throw new IOException("invalid snapshot", e);
    }
    return value;
  }
}
