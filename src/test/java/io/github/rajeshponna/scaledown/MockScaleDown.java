package io.github.rajeshponna.scaledown;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** In-process fake of the ScaleDown API that records requests and replays canned responses. */
final class MockScaleDown implements AutoCloseable {

  record Recorded(String path, Map<String, List<String>> headers, JsonNode body) {
    String header(String name) {
      for (var e : headers.entrySet()) {
        if (e.getKey().equalsIgnoreCase(name)) {
          return e.getValue().get(0);
        }
      }
      return null;
    }
  }

  record Reply(int status, String body, Map<String, String> headers, long delayMs) {}

  private final HttpServer server;
  private final ObjectMapper mapper = new ObjectMapper();
  private final Map<String, Reply> replies = new ConcurrentHashMap<>();
  final List<Recorded> requests = new ArrayList<>();

  MockScaleDown() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      String path = exchange.getRequestURI().getPath();
      byte[] in = exchange.getRequestBody().readAllBytes();
      synchronized (requests) {
        requests.add(new Recorded(path, Map.copyOf(exchange.getRequestHeaders()), mapper.readTree(in)));
      }
      Reply reply = replies.getOrDefault(path, new Reply(404, "{\"detail\":\"not found\"}", Map.of(), 0));
      if (reply.delayMs() > 0) {
        try {
          Thread.sleep(reply.delayMs());
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
      reply.headers().forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      byte[] out = reply.body().getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(reply.status(), out.length);
      exchange.getResponseBody().write(out);
      exchange.close();
    });
    server.start();
  }

  String baseUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  void reply(String path, int status, String body) {
    replies.put(path, new Reply(status, body, Map.of(), 0));
  }

  void reply(String path, int status, String body, Map<String, String> headers) {
    replies.put(path, new Reply(status, body, headers, 0));
  }

  void replySlow(String path, String body, long delayMs) {
    replies.put(path, new Reply(200, body, Map.of(), delayMs));
  }

  Recorded last() {
    synchronized (requests) {
      return requests.get(requests.size() - 1);
    }
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
