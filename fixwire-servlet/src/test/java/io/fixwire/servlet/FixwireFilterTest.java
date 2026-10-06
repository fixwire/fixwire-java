package io.fixwire.servlet;

import static io.fixwire.FakeIngest.kv;
import static io.fixwire.FakeIngest.logRecords;
import static io.fixwire.FakeIngest.spans;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.fixwire.FakeIngest;
import io.fixwire.Fixwire;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.EnumSet;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.jetty.ee10.servlet.FilterHolder;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FixwireFilterTest {
  private FakeIngest ingest;
  private Server server;
  private String base;

  @BeforeEach
  void start() throws Exception {
    ingest = new FakeIngest();
    Fixwire.init(
        o -> {
          o.setDsn(ingest.dsn());
          o.setRelease("shop@1.2.0");
          o.setTracesSampleRate(1);
          o.setUncaughtExceptionHandler(false);
          o.setShutdownTimeoutMillis(0);
        });
    ServletContextHandler ctx = new ServletContextHandler();
    ctx.addServlet(
        new ServletHolder(
            new HttpServlet() {
              @Override
              protected void doGet(HttpServletRequest req, HttpServletResponse res) {
                Fixwire.setTag("item", req.getPathInfo());
                Fixwire.captureException(new IllegalStateException("price missing"));
                res.setStatus(202);
              }
            }),
        "/items/*");
    ctx.addServlet(
        new ServletHolder(
            new HttpServlet() {
              @Override
              protected void doPost(HttpServletRequest req, HttpServletResponse res) {
                throw new IllegalStateException("out of stock");
              }
            }),
        "/checkout");
    ctx.addServlet(
        new ServletHolder(
            new HttpServlet() {
              @Override
              protected void doGet(HttpServletRequest req, HttpServletResponse res) {
                // What Spring MVC does once it matched a handler.
                req.setAttribute(FixwireFilter.SPRING_ROUTE, "/orders/{id}");
                Fixwire.captureMessage("order looked up");
              }
            }),
        "/");
    // A container that hides the headers (getHeaderNames may return null).
    ctx.addFilter(
        new FilterHolder(
            (Filter)
                (req, res, chain) ->
                    chain.doFilter(
                        new HttpServletRequestWrapper((HttpServletRequest) req) {
                          @Override
                          public Enumeration<String> getHeaderNames() {
                            return null;
                          }
                        },
                        res)),
        "/hidden/*",
        EnumSet.allOf(DispatcherType.class));
    ctx.addFilter(FixwireFilter.class, "/*", EnumSet.allOf(DispatcherType.class));
    server = new Server(0);
    server.setHandler(ctx);
    server.start();
    base = "http://127.0.0.1:" + ((ServerConnector) server.getConnectors()[0]).getLocalPort();
  }

  @AfterEach
  void stop() throws Exception {
    server.stop();
    Fixwire.close(1000);
    ingest.close();
  }

  private int send(String method, String path, Map<String, String> headers) throws Exception {
    HttpRequest.Builder b =
        HttpRequest.newBuilder(URI.create(base + path))
            .method(method, HttpRequest.BodyPublishers.noBody());
    headers.forEach(b::header);
    return HttpClient.newHttpClient()
        .send(b.build(), HttpResponse.BodyHandlers.discarding())
        .statusCode();
  }

  @Test
  void reportsRequestsTheirErrorsAndSpans() throws Exception {
    assertEquals(
        202,
        send(
            "GET",
            "/items/42?ref=mail",
            Map.of(
                "traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
                "Authorization", "Bearer secret")));
    assertEquals(500, send("POST", "/checkout", Map.of()));
    assertEquals(200, send("GET", "/orders/7", Map.of()));
    Fixwire.flush(5000);

    List<Map<String, Object>> recs = logRecords(ingest.requests("/v1/logs"));
    assertEquals(3, recs.size());
    Map<String, Map<String, Object>> byTx = new HashMap<>();
    for (Map<String, Object> r : recs) {
      Map<String, Object> a = kv(r.get("attributes"));
      byTx.put((String) a.get("fixwire.transaction"), a);
    }
    Map<String, Object> item = byTx.get("GET /items/*");
    assertNotNull(item, byTx.keySet().toString());
    assertEquals(base + "/items/42", item.get("url.full"));
    assertEquals("ref=mail", item.get("url.query"));
    assertEquals(Map.of("item", "/42"), item.get("fixwire.tags"));
    assertFalse(item.containsKey("http.request.header.authorization"));
    assertEquals(
        "4bf92f3577b34da6a3ce929d0e0e4736",
        recs.stream()
            .filter(r -> "price missing".equals(kv(r.get("attributes")).get("exception.message")))
            .findFirst()
            .orElseThrow()
            .get("traceId"));

    Map<String, Object> checkout = byTx.get("POST /checkout");
    assertEquals(false, checkout.get("fixwire.handled"));
    assertEquals("out of stock", checkout.get("exception.message"));
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> chain =
        (List<Map<String, Object>>) checkout.get("fixwire.exceptions");
    assertEquals("servlet", ((Map<?, ?>) chain.get(0).get("mechanism")).get("type"));

    assertNotNull(byTx.get("GET /orders/{id}"), "Spring's route names the transaction");

    Map<String, Map<String, Object>> spansByName = new HashMap<>();
    for (Map<String, Object> s : spans(ingest.requests("/v1/traces"))) {
      spansByName.put((String) s.get("name"), s);
    }
    Map<String, Object> itemSpan = spansByName.get("GET /items/*");
    assertNotNull(itemSpan, spansByName.keySet().toString());
    assertEquals("00f067aa0ba902b7", itemSpan.get("parentSpanId"));
    assertEquals(202L, kv(itemSpan.get("attributes")).get("http.response.status_code"));
    assertEquals("/items/*", kv(itemSpan.get("attributes")).get("http.route"));
    Map<String, Object> checkoutSpan = spansByName.get("POST /checkout");
    assertEquals(2, ((Map<?, ?>) checkoutSpan.get("status")).get("code"));
    assertNotNull(spansByName.get("GET /orders/{id}"));

    Map<String, Object> sessions = ingest.requests("/v1/sessions").get(0).body();
    int exited = 0;
    int errored = 0;
    int crashed = 0;
    for (Object x : (List<?>) sessions.get("aggregates")) {
      Map<?, ?> a = (Map<?, ?>) x;
      exited += (Integer) a.get("exited");
      errored += (Integer) a.get("errored");
      crashed += (Integer) a.get("crashed");
    }
    assertEquals(List.of(1, 1, 1), List.of(exited, errored, crashed));
  }

  @Test
  void neverBreaksTheRequest() throws Exception {
    assertEquals(200, send("GET", "/hidden/1", Map.of()));
    Fixwire.flush(5000);
    assertEquals(1, logRecords(ingest.requests("/v1/logs")).size());
  }
}
