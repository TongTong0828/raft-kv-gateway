package dev.kvgateway.gateway;

import dev.kvgateway.api.Settings;
import java.util.concurrent.CountDownLatch;

public final class GatewayMain {
  public static void main(String[] args) throws Exception {
    var server = GatewayServer.start(Settings.from(args).gateway());
    Runtime.getRuntime().addShutdownHook(new Thread(server::close));
    System.out.println("Gateway listening on " + server.port());
    new CountDownLatch(1).await();
  }
}
