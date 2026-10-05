package io.fixwire.httpclient;

import static io.fixwire.FakeIngest.kv;
import static io.fixwire.FakeIngest.spans;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.fixwire.FakeIngest;
import io.fixwire.Hub;
import io.fixwire.Options;
import io.fixwire.Span;
import io.fixwire.Upstream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FixwireHttpClientTest {
  @Test
  void tracesSendAndSendAsync() throws Exception {
    try (FakeIngest ingest = new FakeIngest();
        Upstream ours = new Upstream();
        Upstream theirs = new Upstream()) {
      Options o = new Options();
      o.setTracesSampleRate(1);
      o.setTracePropagationTargets(List.of(ours.url()));
      Hub hub = ingest.hub(o);
      HttpClient client = FixwireHttpClient.wrap(HttpClient.newHttpClient());
      String trace;
      try (Hub.Binding b = hub.bind();
          Span job = hub.spanBuilder("job").op("task").start()) {
        trace = job.getTraceId();
        HttpRequest prices =
            HttpRequest.newBuilder(URI.create(ours.url() + "/prices?sku=1"))
                .header("Accept", "application/json")
                .build();
        assertEquals(200, client.send(prices, HttpResponse.BodyHandlers.ofString()).statusCode());
        HttpRequest fail = HttpRequest.newBuilder(URI.create(ours.url() + "/fail")).build();
        assertEquals(
            503, client.sendAsync(fail, HttpResponse.BodyHandlers.discarding()).get().statusCode());
        HttpRequest hook =
            HttpRequest.newBuilder(URI.create(theirs.url() + "/hook"))
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();
        client.send(hook, HttpResponse.BodyHandlers.discarding());
      }
      hub.flush(5000);

      for (Upstream.Call c : ours.calls()) {
        assertTrue(c.traceparent().startsWith("00-" + trace + "-"), c.toString());
      }
      assertNull(theirs.calls().get(0).traceparent(), "no trace headers for others");
      Map<String, Map<String, Object>> byName = new java.util.HashMap<>();
      for (Map<String, Object> s : spans(ingest.requests("/v1/traces"))) {
        byName.put((String) s.get("name"), s);
      }
      assertEquals(4, byName.size(), byName.keySet().toString());
      Map<String, Object> fail = byName.get("GET " + ours.url() + "/fail");
      assertEquals(503L, kv(fail.get("attributes")).get("http.response.status_code"));
      assertEquals(
          "POST",
          kv(byName.get("POST " + theirs.url() + "/hook").get("attributes"))
              .get("http.request.method"));
    }
  }
}
