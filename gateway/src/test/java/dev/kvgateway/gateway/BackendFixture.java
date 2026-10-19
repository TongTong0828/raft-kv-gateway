package dev.kvgateway.gateway;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

final class BackendFixture implements AutoCloseable {
  final HttpServer server;
  final ExecutorService executor = Executors.newCachedThreadPool();
  final AtomicInteger requests = new AtomicInteger();
  final Set<Integer> businessPorts = ConcurrentHashMap.newKeySet();
  final Queue<String> businessPaths = new ConcurrentLinkedQueue<>();
  volatile CountDownLatch businessGate;
  volatile String lastBody, lastRemoved;

  BackendFixture() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 16);
    server.setExecutor(executor);
    server.createContext(
        "/",
        e -> {
          try {
            if (e.getRequestURI().getPath().equals("/health")) {
              e.sendResponseHeaders(200, 2);
              e.getResponseBody().write("ok".getBytes(StandardCharsets.US_ASCII));
              return;
            }
            requests.incrementAndGet();
            businessPaths.add(e.getRequestURI().getPath());
            businessPorts.add(e.getRemoteAddress().getPort());
            lastBody = new String(e.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            lastRemoved = e.getRequestHeaders().getFirst("X-Remove");
            var gate = businessGate;
            if (gate != null && !gate.await(10, TimeUnit.SECONDS))
              throw new IllegalStateException("test backend gate was not released");
            if (e.getRequestURI().getPath().contains("drop")) return;
            if (e.getRequestURI().getPath().contains("slow"))
              try {
                Thread.sleep(500);
              } catch (InterruptedException x) {
                Thread.currentThread().interrupt();
              }
            byte[] body = "payload".getBytes(StandardCharsets.US_ASCII);
            e.getResponseHeaders().set("X-Backend-Id", "test");
            e.getResponseHeaders().set("X-Gateway-Rejection", "backend-spoof");
            e.getResponseHeaders().set("Connection", "keep-alive, X-Backend-Hop");
            e.getResponseHeaders().set("X-Backend-Hop", "remove");
            if (e.getRequestMethod().equals("HEAD")) {
              e.getResponseHeaders().set("Content-Length", "7");
              e.sendResponseHeaders(200, -1);
            } else {
              e.sendResponseHeaders(200, body.length);
              e.getResponseBody().write(body);
            }
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
          } finally {
            e.close();
          }
        });
    server.start();
  }

  String endpoint() {
    return "127.0.0.1:" + server.getAddress().getPort();
  }

  public void close() {
    var gate = businessGate;
    if (gate != null) gate.countDown();
    server.stop(0);
    executor.shutdownNow();
  }
}
