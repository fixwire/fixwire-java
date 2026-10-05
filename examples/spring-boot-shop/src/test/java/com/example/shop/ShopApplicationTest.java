package com.example.shop;

import static io.fixwire.FakeIngest.kv;
import static io.fixwire.FakeIngest.logRecords;
import static io.fixwire.FakeIngest.spans;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.fixwire.FakeIngest;
import io.fixwire.Fixwire;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Runs the app on a free port against a fake ingest and checks what Fixwire receives. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ShopApplicationTest {
  private static FakeIngest ingest;

  @LocalServerPort int port;

  @DynamicPropertySource
  static void fixwire(DynamicPropertyRegistry registry) throws Exception {
    ingest = new FakeIngest();
    registry.add("fixwire.dsn", ingest::dsn);
  }

  @AfterAll
  static void stop() {
    ingest.close();
  }

  private int call(String method, String path, String user, String json) throws Exception {
    HttpRequest.Builder b =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .header("Content-Type", "application/json")
            .method(
                method,
                json == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(json));
    if (user != null) {
      b.header("X-User-Id", user);
    }
    return HttpClient.newHttpClient()
        .send(b.build(), HttpResponse.BodyHandlers.discarding())
        .statusCode();
  }

  @Test
  void reportsWhatHappensInRequests() throws Exception {
    assertEquals(200, call("GET", "/products/sku_1", null, null));
    assertEquals(404, call("GET", "/products/nope", null, null));
    assertEquals(
        201,
        call("POST", "/orders", "user-1", "{\"sku\":\"sku_1\",\"card\":\"4242424242424242\"}"));
    assertEquals(
        402,
        call("POST", "/orders", "user-2", "{\"sku\":\"sku_1\",\"card\":\"4000000000000002\"}"));
    assertEquals(500, call("GET", "/admin/report", null, null));
    Fixwire.flush(5000);

    // Two errors: the declined payment (handled) and the crash. No 404.
    List<Map<String, Object>> events = logRecords(ingest.requests("/v1/logs"));
    assertEquals(2, events.size(), events.toString());
    Map<String, Map<String, Object>> byTx = new HashMap<>();
    for (Map<String, Object> e : events) {
      Map<String, Object> a = kv(e.get("attributes"));
      byTx.put((String) a.get("fixwire.transaction"), a);
    }
    Map<String, Object> payment = byTx.get("POST /orders");
    assertEquals("user-2", payment.get("user.id"));
    assertEquals(Map.of("sku", "sku_1"), payment.get("fixwire.tags"));
    assertEquals("charging order ord_2", payment.get("exception.message"));
    assertFalse(payment.containsKey("fixwire.handled"));
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> chain = (List<Map<String, Object>>) payment.get("fixwire.exceptions");
    assertEquals("com.example.shop.ShopController$PaymentException", chain.get(1).get("type"));
    @SuppressWarnings("unchecked")
    Map<String, Object> order =
        (Map<String, Object>) ((Map<String, Object>) payment.get("fixwire.contexts")).get("order");
    assertEquals("ord_2", order.get("id"));

    Map<String, Object> crash = byTx.get("GET /admin/report");
    assertEquals(false, crash.get("fixwire.handled"));
    assertEquals("java.lang.ArithmeticException", crash.get("exception.type"));

    // A server span per request, named after its route; the lookups under them.
    Map<String, Integer> names = new HashMap<>();
    for (Map<String, Object> s : spans(ingest.requests("/v1/traces"))) {
      names.merge((String) s.get("name"), 1, Integer::sum);
    }
    assertEquals(2, names.get("GET /products/{id}"), names.toString());
    assertEquals(2, names.get("POST /orders"), names.toString());
    assertEquals(1, names.get("GET /admin/report"), names.toString());
    assertEquals(2, names.get("SELECT products"), names.toString());

    // Release health: 3 requests ended well, 1 with an error, 1 crashed.
    int exited = 0;
    int errored = 0;
    int crashed = 0;
    for (FakeIngest.Received r : ingest.requests("/v1/sessions")) {
      for (Object x : (List<?>) r.body().get("aggregates")) {
        Map<?, ?> a = (Map<?, ?>) x;
        exited += (Integer) a.get("exited");
        errored += (Integer) a.get("errored");
        crashed += (Integer) a.get("crashed");
      }
    }
    assertEquals(List.of(3, 1, 1), List.of(exited, errored, crashed));
    assertTrue(Fixwire.isEnabled());
  }
}
