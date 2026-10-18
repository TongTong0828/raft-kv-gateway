import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/** Preserve original dependency notices in ZIPs, including case-distinct paths on macOS. */
class LicenseBundle {
  public static void main(String[] args) throws Exception {
    Path dependencies = Path.of(args[0]), output = Path.of(args[1]);
    Files.createDirectories(output);
    var index =
        new StringBuilder("archive\toriginal_dependency\tdependency_sha256\tlicense_files\n");
    if (!Files.exists(dependencies)) return;
    try (var paths = Files.walk(dependencies)) {
      for (var source : paths.filter(p -> p.toString().endsWith(".jar")).sorted().toList()) {
        String relative = dependencies.relativize(source).toString().replace('\\', '/');
        String name = relative.replace('/', '_') + ".licenses.zip";
        int count = 0;
        try (var jar = new ZipFile(source.toFile());
            var bundle = new ZipOutputStream(Files.newOutputStream(output.resolve(name)))) {
          for (var entry :
              jar.stream()
                  .filter(e -> !e.isDirectory())
                  .sorted(Comparator.comparing(ZipEntry::getName))
                  .toList()) {
            String base =
                Path.of(entry.getName()).getFileName().toString().toLowerCase(Locale.ROOT);
            if ((base.contains("license") || base.contains("notice") || base.contains("copying"))
                && !base.endsWith(".class")
                && !base.endsWith(".jar")) {
              var copy = new ZipEntry(entry.getName());
              copy.setTime(0);
              bundle.putNextEntry(copy);
              try (var input = jar.getInputStream(entry)) {
                input.transferTo(bundle);
              }
              bundle.closeEntry();
              count++;
            }
          }
        }
        index
            .append(name)
            .append('\t')
            .append(relative)
            .append('\t')
            .append(
                HexFormat.of()
                    .formatHex(
                        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source))))
            .append('\t')
            .append(count)
            .append('\n');
      }
    }
    Files.writeString(output.resolve("index.tsv"), index);
  }
}
