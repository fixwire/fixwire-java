package io.fixwire;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.zip.GZIPInputStream;

/** Records what the SDK sends (for the SDK's modules' tests). */
public final class FakeIngest implements AutoCloseable {
  public record Received(
      String path, String auth, String encoding, String agent, Map<String, Object> body) {}

  /** A status and headers for the n-th request. */
  public record Answer(int status, Map<String, String> headers) {}

  private static final ObjectMapper JSON = new ObjectMapper();

  private final HttpServer server;
  private final List<Received> received = new ArrayList<>();
  public volatile BiFunction<Integer, String, Answer> answer =
      (n, path) -> new Answer(200, Map.of());

  public FakeIngest() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        ex -> {
          try (InputStream raw = ex.getRequestBody()) {
            InputStream in =
                "gzip".equals(ex.getRequestHeaders().getFirst("Content-Encoding"))
                    ? new GZIPInputStream(raw)
                    : raw;
            @SuppressWarnings("unchecked")
            Map<String, Object> body = JSON.readValue(in, LinkedHashMap.class);
            int n;
            synchronized (received) {
              n = received.size();
              received.add(
                  new Received(
                      ex.getRequestURI().getRawPath(),
                      ex.getRequestHeaders().getFirst("Authorization"),
                      ex.getRequestHeaders().getFirst("Content-Encoding"),
                      ex.getRequestHeaders().getFirst("User-Agent"),
                      body));
            }
            Answer a = answer.apply(n, ex.getRequestURI().getRawPath());
            a.headers().forEach((k, v) -> ex.getResponseHeaders().add(k, v));
            byte[] out = "{}".getBytes();
            ex.sendResponseHeaders(a.status(), out.length);
            ex.getResponseBody().write(out);
          } finally {
            ex.close();
          }
        });
    server.start();
  }

  public String dsn() {
    return "http://publickey@127.0.0.1:" + server.getAddress().getPort();
  }

  public List<Received> requests(String path) {
    synchronized (received) {
      return received.stream().filter(r -> path.isEmpty() || r.path().equals(path)).toList();
    }
  }

  @Override
  public void close() {
    server.stop(0);
  }

  /** A hub with a client sending here; the options' DSN is set. */
  public Hub hub(Options o) {
    o.setDsn(dsn());
    if (o.getServiceName() == null) {
      o.setServiceName("shop");
    }
    o.setUncaughtExceptionHandler(false);
    return new Hub(new Client(o), null);
  }

  // OTLP readers.

  @SuppressWarnings("unchecked")
  public static List<Map<String, Object>> logRecords(List<Received> reqs) {
    List<Map<String, Object>> out = new ArrayList<>();
    for (Received r : reqs) {
      for (Object rl : (List<Object>) r.body().get("resourceLogs")) {
        for (Object sl : (List<Object>) ((Map<String, Object>) rl).get("scopeLogs")) {
          out.addAll((List<Map<String, Object>>) ((Map<String, Object>) sl).get("logRecords"));
        }
      }
    }
    return out;
  }

  @SuppressWarnings("unchecked")
  public static Map<String, Object> resource(Received r) {
    Map<String, Object> rl =
        ((List<Map<String, Object>>)
                r.body().getOrDefault("resourceLogs", r.body().get("resourceSpans")))
            .get(0);
    return kv(((Map<String, Object>) rl.get("resource")).get("attributes"));
  }

  @SuppressWarnings("unchecked")
  public static List<Map<String, Object>> spans(List<Received> reqs) {
    List<Map<String, Object>> out = new ArrayList<>();
    for (Received r : reqs) {
      for (Object rs : (List<Object>) r.body().get("resourceSpans")) {
        for (Object ss : (List<Object>) ((Map<String, Object>) rs).get("scopeSpans")) {
          out.addAll((List<Map<String, Object>>) ((Map<String, Object>) ss).get("spans"));
        }
      }
    }
    return out;
  }

  /** OTLP key-values as plain values. */
  @SuppressWarnings("unchecked")
  public static Map<String, Object> kv(Object list) {
    Map<String, Object> out = new LinkedHashMap<>();
    if (list instanceof List<?> items) {
      for (Object it : items) {
        Map<String, Object> m = (Map<String, Object>) it;
        out.put((String) m.get("key"), plain((Map<String, Object>) m.get("value")));
      }
    }
    return out;
  }

  @SuppressWarnings("unchecked")
  public static Object plain(Map<String, Object> v) {
    if (v.containsKey("stringValue")) {
      return v.get("stringValue");
    }
    if (v.containsKey("boolValue")) {
      return v.get("boolValue");
    }
    if (v.containsKey("intValue")) {
      return Long.parseLong((String) v.get("intValue"));
    }
    if (v.containsKey("doubleValue")) {
      return ((Number) v.get("doubleValue")).doubleValue();
    }
    if (v.containsKey("arrayValue")) {
      List<Object> out = new ArrayList<>();
      for (Object x :
          (List<Object>)
              ((Map<String, Object>) v.get("arrayValue")).getOrDefault("values", List.of())) {
        out.add(plain((Map<String, Object>) x));
      }
      return out;
    }
    if (v.containsKey("kvlistValue")) {
      return kv(((Map<String, Object>) v.get("kvlistValue")).get("values"));
    }
    return null;
  }
}
