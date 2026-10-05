package io.fixwire.okhttp;

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
import java.util.List;
import java.util.Map;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.Test;

class FixwireInterceptorTest {
  @Test
  void tracesRequestsAndPropagatesToTargetsOnly() throws Exception {
    try (FakeIngest ingest = new FakeIngest();
        Upstream ours = new Upstream();
        Upstream theirs = new Upstream()) {
      Options o = new Options();
      o.setTracesSampleRate(1);
      o.setTracePropagationTargets(List.of(ours.url()));
      Hub hub = ingest.hub(o);
      OkHttpClient client =
          new OkHttpClient.Builder().addInterceptor(new FixwireInterceptor()).build();
      String trace;
      try (Hub.Binding b = hub.bind();
          Span job = hub.spanBuilder("job").op("task").start()) {
        trace = job.getTraceId();
        for (String url :
            List.of(ours.url() + "/prices?sku=1", ours.url() + "/fail", theirs.url() + "/hook")) {
          try (Response r = client.newCall(new Request.Builder().url(url).build()).execute()) {
            assertTrue(r.code() == 200 || r.code() == 503);
          }
        }
      }
      hub.flush(5000);

      assertTrue(ours.calls().get(0).traceparent().startsWith("00-" + trace + "-"));
      assertNull(theirs.calls().get(0).traceparent(), "no trace headers for others");
      Map<String, Map<String, Object>> byName = new java.util.HashMap<>();
      for (Map<String, Object> s : spans(ingest.requests("/v1/traces"))) {
        byName.put((String) s.get("name"), s);
      }
      Map<String, Object> fail = byName.get("GET " + ours.url() + "/fail");
      assertEquals(503L, kv(fail.get("attributes")).get("http.response.status_code"));
      assertEquals(2, ((Map<?, ?>) fail.get("status")).get("code"));
      assertEquals(4, byName.size(), byName.keySet().toString());
    }
  }
}
