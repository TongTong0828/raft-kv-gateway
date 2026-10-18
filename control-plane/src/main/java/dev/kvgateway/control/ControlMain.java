package dev.kvgateway.control;

import dev.kvgateway.api.Settings;

public final class ControlMain {
  public static void main(String[] args) throws Exception {
    var node = ControlNode.start(Settings.from(args).node());
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  try {
                    node.close();
                  } catch (Exception ignored) {
                  }
                }));
    new java.util.concurrent.CountDownLatch(1).await();
  }
}
