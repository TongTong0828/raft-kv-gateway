package dev.kvgateway.cli;

import com.google.protobuf.MessageOrBuilder;
import com.google.protobuf.util.JsonFormat;
import dev.kvgateway.api.*;
import io.grpc.StatusRuntimeException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

public final class CliMain {
  private static final String USAGE =
      "Usage: cli [--seeds host:port,...] [--timeout-ms 5000] status | kv get KEY | kv put KEY"
          + " VALUE --expected-revision N | kv delete KEY --expected-revision N | service get ID |"
          + " service put FILE.json --expected-revision N | service delete ID --expected-revision N"
          + " | watch";

  static String json(MessageOrBuilder message) throws Exception {
    return JsonFormat.printer()
        .includingDefaultValueFields()
        .omittingInsignificantWhitespace()
        .print(message);
  }

  public static void main(String[] args) {
    try {
      run(args);
    } catch (Exception e) {
      System.err.println(e instanceof StatusRuntimeException s ? s.getStatus() : e.getMessage());
      System.exit(2);
    }
  }

  static void run(String[] args) throws Exception {
    if (args.length == 0 || Arrays.asList(args).contains("--help")) {
      System.out.println(USAGE);
      return;
    }
    var options = new HashMap<String, String>();
    var positional = new ArrayList<String>();
    for (int i = 0; i < args.length; i++) {
      if (args[i].startsWith("--")) {
        if (!Set.of("--seeds", "--timeout-ms", "--expected-revision").contains(args[i])
            || i + 1 == args.length) throw new IllegalArgumentException(USAGE);
        options.put(args[i++], args[i]);
      } else positional.add(args[i]);
    }
    var seeds =
        List.of(
            options
                .getOrDefault(
                    "--seeds",
                    System.getenv()
                        .getOrDefault(
                            "KV_CONTROL_SEEDS", "localhost:15051,localhost:15052,localhost:15053"))
                .split(","));
    long expected = Long.parseLong(options.getOrDefault("--expected-revision", "0"));
    try (var client =
        new ControlClient(
            seeds,
            Duration.ofMillis(Long.parseLong(options.getOrDefault("--timeout-ms", "5000"))))) {
      if (positional.equals(List.of("status"))) {
        System.out.println(
            "["
                + String.join(
                    ",",
                    client.status().stream()
                        .map(
                            s -> {
                              try {
                                return json(s);
                              } catch (Exception e) {
                                throw new RuntimeException(e);
                              }
                            })
                        .toList())
                + "]");
        return;
      }
      if (positional.equals(List.of("watch"))) {
        var latch = new CountDownLatch(1);
        try (var context =
            client.watch(
                new io.grpc.stub.StreamObserver<ConfigSnapshot>() {
                  public void onNext(ConfigSnapshot c) {
                    try {
                      System.out.println(json(c));
                    } catch (Exception e) {
                      throw new RuntimeException(e);
                    }
                  }

                  public void onError(Throwable e) {
                    System.err.println(e.getMessage());
                    latch.countDown();
                  }

                  public void onCompleted() {
                    latch.countDown();
                  }
                })) {
          latch.await();
        }
        return;
      }
      if (positional.size() < 3) throw new IllegalArgumentException(USAGE);
      var kind = positional.get(0);
      var op = positional.get(1);
      var argument = positional.get(2);
      if (kind.equals("kv")) {
        switch (op) {
          case "get" -> System.out.println(json(client.get(argument)));
          case "put" -> {
            if (positional.size() != 4) throw new IllegalArgumentException(USAGE);
            System.out.println(
                json(
                    client.put(
                        argument, positional.get(3).getBytes(StandardCharsets.UTF_8), expected)));
          }
          case "delete" -> System.out.println(json(client.delete(argument, expected)));
          default -> throw new IllegalArgumentException(USAGE);
        }
        return;
      }
      if (kind.equals("service")) {
        switch (op) {
          case "put" -> {
            var b = ServiceSpec.newBuilder();
            JsonFormat.parser().merge(Files.readString(Path.of(argument)), b);
            var s = ServiceSpecs.normalize(b.build());
            System.out.println(
                json(client.put("/services/" + s.getServiceId(), s.toByteArray(), expected)));
          }
          case "get" -> {
            var r = client.get("/services/" + argument);
            System.out.println(
                "{\"revision\":\""
                    + r.getEntry().getModRevision()
                    + "\",\"service\":"
                    + json(ServiceSpec.parseFrom(r.getEntry().getValue()))
                    + "}");
          }
          case "delete" ->
              System.out.println(json(client.delete("/services/" + argument, expected)));
          default -> throw new IllegalArgumentException(USAGE);
        }
        return;
      }
      throw new IllegalArgumentException(USAGE);
    }
  }
}
