package io.fixwire;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.ToIntFunction;

/**
 * A service the app calls: notes the trace headers of each request and answers with a status (by
 * default 503 for paths under /fail, else 200).
 */
public final class Upstream implements AutoCloseable {
  /** A request it got: the path and its traceparent (null when it had none). */
  public record Call(String path, String traceparent) {}

  private final HttpServer server;
  private final List<Call> calls = new CopyOnWriteArrayList<>();

  public Upstream() throws IOException {
    this(uri -> uri.getPath().startsWith("/fail") ? 503 : 200);
  }

  public Upstream(ToIntFunction<URI> status) throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        ex -> {
          String path = ex.getRequestURI().getPath();
          calls.add(new Call(path, ex.getRequestHeaders().getFirst("traceparent")));
          int code = status.applyAsInt(ex.getRequestURI());
          byte[] body = "{}".getBytes();
          ex.sendResponseHeaders(code, body.length);
          ex.getResponseBody().write(body);
          ex.close();
        });
    server.start();
  }

  /** Its base URL, such as http://127.0.0.1:1234. */
  public String url() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  public List<Call> calls() {
    return calls;
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
