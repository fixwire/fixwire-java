package io.fixwire.spring;

import static io.fixwire.FakeIngest.kv;
import static io.fixwire.FakeIngest.logRecords;
import static io.fixwire.FakeIngest.spans;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.app.TestApp;
import io.fixwire.FakeIngest;
import io.fixwire.Fixwire;
import io.fixwire.Upstream;
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

/** The starter in a real app: only the dependency and fixwire.* properties. */
@SpringBootTest(classes = TestApp.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FixwireAutoConfigurationTest {
  private static FakeIngest ingest;
  private static Upstream payments;

  @LocalServerPort int port;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) throws Exception {
    ingest = new FakeIngest();
    payments = new Upstream();
    registry.add("fixwire.dsn", ingest::dsn);
    registry.add("fixwire.release", () -> "shop@2.0.0");
    registry.add("fixwire.traces-sample-rate", () -> "1.0");
    registry.add("fixwire.trace-propagation-targets", payments::url);
    registry.add("payments.url", payments::url);
  }

  @AfterAll
  static void stop() {
    ingest.close();
    payments.close();
  }

  private int get(String path) throws Exception {
    return HttpClient.newHttpClient()
        .send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).build(),
            HttpResponse.BodyHandlers.discarding())
        .statusCode();
  }

  @Test
  void setsEverythingUpFromProperties() throws Exception {
    assertEquals(200, get("/checkout/7"));
    assertEquals(500, get("/crash"));
    Fixwire.flush(5000);

    // Logback's ERROR record and the crash, both in their request's transaction.
    Map<String, Map<String, Object>> byTx = new HashMap<>();
    for (Map<String, Object> e : logRecords(ingest.requests("/v1/logs"))) {
      Map<String, Object> a = kv(e.get("attributes"));
      byTx.put((String) a.get("fixwire.transaction"), a);
    }
    assertEquals(2, byTx.size(), byTx.toString());
    Map<String, Object> logged = byTx.get("GET /checkout/{id}");
    assertEquals("com.example.app.TestApp$Checkout", logged.get("logger"));
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> crumbs =
        (List<Map<String, Object>>) logged.get("fixwire.breadcrumbs");
    assertTrue(
        crumbs.stream().anyMatch(c -> "checking out".equals(c.get("message"))), crumbs.toString());
    assertTrue(crumbs.stream().anyMatch(c -> "http".equals(c.get("category"))), crumbs.toString());

    Map<String, Object> crash = byTx.get("GET /crash");
    assertEquals(false, crash.get("fixwire.handled"));
    // The application's package is in-app without configuring it.
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> frames =
        (List<Map<String, Object>>)
            ((List<Map<String, Object>>) crash.get("fixwire.exceptions")).get(0).get("frames");
    Map<String, Object> newest = frames.get(frames.size() - 1);
    assertEquals("com.example.app.TestApp$Checkout", newest.get("module"));
    assertEquals(true, newest.get("in_app"));

    // The request's span, the RestClient call under it, and the trace carried to the service.
    Map<String, Map<String, Object>> byName = new HashMap<>();
    for (Map<String, Object> s : spans(ingest.requests("/v1/traces"))) {
      byName.put((String) s.get("name"), s);
    }
    Map<String, Object> server = byName.get("GET /checkout/{id}");
    Map<String, Object> client = byName.get("POST " + payments.url() + "/fail/charge");
    assertEquals(server.get("spanId"), client.get("parentSpanId"), byName.keySet().toString());
    assertEquals(503L, kv(client.get("attributes")).get("http.response.status_code"));
    assertTrue(
        payments.calls().get(0).traceparent().startsWith("00-" + server.get("traceId") + "-"));
    assertEquals(
        "shop@2.0.0",
        FakeIngest.resource(ingest.requests("/v1/traces").get(0)).get("service.version"));
  }
}
