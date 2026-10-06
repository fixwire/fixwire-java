package io.fixwire;

import static io.fixwire.FakeIngest.kv;
import static io.fixwire.FakeIngest.logRecords;
import static io.fixwire.FakeIngest.plain;
import static io.fixwire.FakeIngest.spans;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.fixwire.internal.Json;
import io.fixwire.jul.FixwireHandler;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FixwireTest {
  private FakeIngest ingest;

  @BeforeEach
  void start() throws Exception {
    ingest = new FakeIngest();
  }

  @AfterEach
  void stop() {
    ingest.close();
  }

  static final class CartException extends RuntimeException {
    CartException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  static void chargeCard(int amount) {
    if (amount > 100) {
      throw new IllegalArgumentException("amount " + amount + " exceeds the limit");
    }
  }

  @Test
  void parsesDsns() {
    Dsn d = Dsn.parse("https://fw_pk_live_abc@ingest.fixwire.io");
    assertEquals("fw_pk_live_abc", d.key());
    assertEquals("https://ingest.fixwire.io", d.baseUrl());
    assertEquals(
        "http://127.0.0.1:9000/v1/logs", Dsn.parse("http://k@127.0.0.1:9000/").url("/v1/logs"));
    assertEquals(
        "https://self.example.com/fixwire",
        Dsn.parse(" https://k@self.example.com/fixwire ").baseUrl());
    for (String bad :
        new String[] {
          "", "ingest.fixwire.io", "https://ingest.fixwire.io", "ftp://k@host", "https://@host"
        }) {
      assertThrows(IllegalArgumentException.class, () -> Dsn.parse(bad), bad);
    }
  }

  @Test
  void doesNothingWithoutADsn() {
    Options o = new Options();
    o.setDsn("");
    Client c = new Client(o);
    assertFalse(c.isEnabled() && System.getenv("FIXWIRE_DSN") == null);
    assertNull(new Hub(c, null).captureException(new RuntimeException("x")));
  }

  @Test
  void capturesExceptionsWithTheirCauses() {
    Options o = new Options();
    o.setRelease("shop@1.2.0");
    o.setEnvironment("staging");
    o.setServerName("web-1");
    o.setInAppIncludes(List.of("io.fixwire.FixwireTest"));
    Hub hub = ingest.hub(o);
    User user = new User("user-1");
    user.setUsername("ada");
    hub.getScope().setUser(user);
    hub.getScope().setTag("plan", "team");
    hub.getScope().setContext("order", Map.of("id", 42));
    hub.addBreadcrumb(new Breadcrumb("cart", "checkout started"));

    String id;
    try {
      try {
        chargeCard(500);
        throw new AssertionError();
      } catch (IllegalArgumentException e) {
        throw new CartException("checkout failed", e);
      }
    } catch (CartException e) {
      id = hub.captureException(e);
    }
    assertEquals(32, id.length());
    assertTrue(hub.flush(5000));

    List<FakeIngest.Received> reqs = ingest.requests("/v1/logs");
    assertEquals(1, reqs.size());
    assertEquals("Bearer publickey", reqs.get(0).auth());
    assertEquals("gzip", reqs.get(0).encoding());
    assertEquals("fixwire.java/" + Client.SDK_VERSION, reqs.get(0).agent());
    Map<String, Object> res = FakeIngest.resource(reqs.get(0));
    assertEquals("shop", res.get("service.name"));
    assertEquals("shop@1.2.0", res.get("service.version"));
    assertEquals("staging", res.get("deployment.environment.name"));
    assertEquals("web-1", res.get("host.name"));
    assertEquals("java", res.get("telemetry.sdk.language"));

    Map<String, Object> rec = logRecords(reqs).get(0);
    assertEquals("exception", rec.get("eventName"));
    assertEquals(17, rec.get("severityNumber"));
    Map<String, Object> a = kv(rec.get("attributes"));
    assertEquals(id, a.get("fixwire.event_id"));
    assertEquals(CartException.class.getName(), a.get("exception.type"));
    assertEquals("checkout failed", a.get("exception.message"));
    assertEquals("user-1", a.get("user.id"));
    assertEquals("ada", a.get("user.name"));
    assertEquals(Map.of("plan", "team"), a.get("fixwire.tags"));
    assertEquals(Map.of("order", Map.of("id", 42L)), a.get("fixwire.contexts"));
    assertFalse(a.containsKey("fixwire.handled"));
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> crumbs = (List<Map<String, Object>>) a.get("fixwire.breadcrumbs");
    assertEquals("checkout started", crumbs.get(0).get("message"));

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> chain = (List<Map<String, Object>>) a.get("fixwire.exceptions");
    assertEquals(2, chain.size());
    assertEquals("generic", ((Map<?, ?>) chain.get(0).get("mechanism")).get("type"));
    assertEquals("chained", ((Map<?, ?>) chain.get(1).get("mechanism")).get("type"));
    assertEquals("java.lang.IllegalArgumentException", chain.get(1).get("type"));
    assertEquals("java.lang", chain.get(1).get("module"));
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> frames = (List<Map<String, Object>>) chain.get(1).get("frames");
    Map<String, Object> newest = frames.get(frames.size() - 1);
    assertEquals("chargeCard", newest.get("function"));
    assertEquals("io.fixwire.FixwireTest", newest.get("module"));
    assertEquals("FixwireTest.java", newest.get("file"));
    assertEquals(true, newest.get("in_app"));
    assertTrue((Long) newest.get("line") > 0);
    for (Map<String, Object> f : frames) {
      String module = (String) f.get("module");
      if (module.startsWith("java.") || module.startsWith("org.junit.")) {
        assertEquals(false, f.get("in_app"), module);
      }
    }
  }

  @Test
  void capturesMessagesAtTheirLevel() {
    Hub hub = ingest.hub(new Options());
    hub.captureMessage("disk almost full", null);
    hub.withScope(
        s -> {
          s.setLevel(Level.WARNING);
          hub.captureMessage("slow query", null);
        });
    hub.captureMessage("on fire", Level.FATAL);
    hub.flush(5000);
    List<Map<String, Object>> recs = logRecords(ingest.requests("/v1/logs"));
    assertEquals(3, recs.size());
    assertEquals("fixwire.message", recs.get(0).get("eventName"));
    assertEquals("disk almost full", plain(cast(recs.get(0).get("body"))));
    assertEquals(9, recs.get(0).get("severityNumber"));
    assertEquals(13, recs.get(1).get("severityNumber"));
    assertEquals(21, recs.get(2).get("severityNumber"));
  }

  @Test
  void beforeSendChangesOrDrops() {
    Options o = new Options();
    o.setBeforeSend(
        e -> {
          if (e.getMessage().contains("noise")) {
            return null;
          }
          e.getTags().put("seen", "yes");
          return e;
        });
    Hub hub = ingest.hub(o);
    assertNull(hub.captureMessage("noise", null));
    assertNotNull(hub.captureMessage("signal", null));
    hub.flush(5000);
    List<Map<String, Object>> recs = logRecords(ingest.requests("/v1/logs"));
    assertEquals(1, recs.size());
    assertEquals(Map.of("seen", "yes"), kv(recs.get(0).get("attributes")).get("fixwire.tags"));
  }

  @Test
  void reportsUncaughtExceptionsThenTheHandlerBefore() throws Exception {
    Options o = new Options();
    Hub hub = ingest.hub(o);
    Hub.setMain(hub);
    AtomicReference<Throwable> before = new AtomicReference<>();
    try {
      Fixwire.UncaughtHandler h = new Fixwire.UncaughtHandler((t, e) -> before.set(e));
      IllegalStateException boom = new IllegalStateException("boom");
      Thread t = new Thread(() -> h.uncaughtException(Thread.currentThread(), boom));
      t.start();
      t.join();
      assertEquals(boom, before.get());
    } finally {
      Hub.setMain(new Hub(null, null));
    }
    Map<String, Object> rec = logRecords(ingest.requests("/v1/logs")).get(0);
    Map<String, Object> a = kv(rec.get("attributes"));
    assertEquals(21, rec.get("severityNumber"));
    assertEquals(false, a.get("fixwire.handled"));
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> chain = (List<Map<String, Object>>) a.get("fixwire.exceptions");
    assertEquals(
        "UncaughtExceptionHandler", ((Map<?, ?>) chain.get(0).get("mechanism")).get("type"));
  }

  @Test
  void masksSecretsAndPersonalDataOnTheDevice() throws Exception {
    Hub hub = ingest.hub(new Options());
    hub.getScope().setExtra("password", "hunter2hunter2");
    hub.getScope().setExtra("note", "card 4111 1111 1111 1111 declined");
    hub.captureException(new IllegalStateException("mail to ada@example.com bounced"));
    Feedback f = new Feedback("call me at ada@example.com");
    hub.captureFeedback(f);
    hub.flush(5000);
    Map<String, Object> a = kv(logRecords(ingest.requests("/v1/logs")).get(0).get("attributes"));
    assertEquals("[Filtered]", a.get("password"));
    assertEquals("card [REDACTED:credit_card] declined", a.get("note"));
    assertEquals("mail to [REDACTED:email] bounced", a.get("exception.message"));
    assertEquals(
        "call me at [REDACTED:email]",
        ingest.requests("/v1/feedback").get(0).body().get("message"));

    try (FakeIngest raw = new FakeIngest()) {
      Options o = new Options();
      o.setRedact(false);
      Hub off = raw.hub(o);
      off.captureException(new IllegalStateException("mail to ada@example.com bounced"));
      off.flush(5000);
      assertEquals(
          "mail to ada@example.com bounced",
          kv(logRecords(raw.requests("/v1/logs")).get(0).get("attributes"))
              .get("exception.message"));
    }
  }

  @Test
  void tracesSegmentsWithTheirSpans() {
    Options o = new Options();
    o.setTracesSampleRate(1);
    Hub hub = ingest.hub(o);
    Span root =
        hub.spanBuilder("POST /checkout")
            .op("http.server")
            .attribute("http.request.method", "POST")
            .start();
    assertEquals(root, hub.getScope().getSpan());
    Span child = hub.spanBuilder("SELECT carts").op("db.query").start();
    assertEquals(root.getTraceId(), child.getTraceId());
    assertEquals(root.getSpanId(), child.getParentSpanId());
    child.setError(new IllegalStateException("deadlock"));
    child.close();
    assertEquals(root, hub.getScope().getSpan());
    assertTrue(ingest.requests("/v1/traces").isEmpty(), "a child was sent before its segment");

    String linked = hub.captureMessage("linked", null);
    assertNotNull(linked);
    root.close();
    assertNull(hub.getScope().getSpan());
    Span late = hub.spanBuilder("after").parent(root).op("task").start();
    late.close();
    hub.flush(5000);

    List<FakeIngest.Received> reqs = ingest.requests("/v1/traces");
    assertEquals(2, reqs.size(), "the segment's request and the late span's");
    Map<String, Map<String, Object>> byName = new java.util.HashMap<>();
    for (Map<String, Object> s : spans(reqs)) {
      byName.put((String) s.get("name"), s);
    }
    Map<String, Object> r = byName.get("POST /checkout");
    assertEquals(2, r.get("kind"));
    assertEquals(0x101, r.get("flags"));
    assertNull(r.get("parentSpanId"));
    assertEquals("http.server", kv(r.get("attributes")).get("fixwire.op"));
    assertEquals("POST", kv(r.get("attributes")).get("http.request.method"));
    Map<String, Object> c = byName.get("SELECT carts");
    assertEquals(3, c.get("kind"));
    assertEquals(root.getSpanId(), c.get("parentSpanId"));
    assertEquals(Map.of("code", 2, "message", "deadlock"), c.get("status"));
    assertEquals(root.getSpanId(), byName.get("after").get("parentSpanId"));

    Map<String, Object> rec = logRecords(ingest.requests("/v1/logs")).get(0);
    assertEquals(root.getTraceId(), rec.get("traceId"));
    assertEquals(root.getSpanId(), rec.get("spanId"));
    assertEquals("POST /checkout", kv(rec.get("attributes")).get("fixwire.transaction"));
  }

  @Test
  void tracesOutgoingRequests() {
    Options o = new Options();
    o.setTracesSampleRate(1);
    o.setTracePropagationTargets(List.of("api.internal"));
    Hub hub = ingest.hub(o);
    Map<String, String> internal = new java.util.HashMap<>();
    Map<String, String> partner = new java.util.HashMap<>();
    try (Span job = hub.spanBuilder("job").op("task").start()) {
      OutgoingRequest a =
          OutgoingRequest.start(hub, "get", "https://api.internal/prices?sku=1", internal::put);
      a.end(503);
      OutgoingRequest b =
          OutgoingRequest.start(hub, "POST", "https://partner.example.com/hook", partner::put);
      b.fail(new java.io.IOException("connection refused"));
      assertEquals(job, hub.getScope().getSpan(), "client spans don't become current");
      assertTrue(internal.get("traceparent").startsWith("00-" + job.getTraceId() + "-"));
      assertFalse(internal.get("traceparent").contains(job.getSpanId()), "the client span's id");
      assertTrue(partner.isEmpty(), "no trace headers for a service that is no target");
    }
    hub.captureMessage("after", null);
    hub.flush(5000);
    Map<String, Map<String, Object>> byName = new java.util.HashMap<>();
    for (Map<String, Object> s : spans(ingest.requests("/v1/traces"))) {
      byName.put((String) s.get("name"), s);
    }
    Map<String, Object> prices = byName.get("GET https://api.internal/prices");
    assertEquals(3, prices.get("kind"));
    assertEquals(503L, kv(prices.get("attributes")).get("http.response.status_code"));
    assertEquals("api.internal", kv(prices.get("attributes")).get("server.address"));
    assertEquals(2, ((Map<?, ?>) prices.get("status")).get("code"));
    assertEquals(
        "connection refused",
        ((Map<?, ?>) byName.get("POST https://partner.example.com/hook").get("status"))
            .get("message"));
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> crumbs =
        (List<Map<String, Object>>)
            kv(logRecords(ingest.requests("/v1/logs")).get(0).get("attributes"))
                .get("fixwire.breadcrumbs");
    assertEquals(2, crumbs.size());
    assertEquals("error", crumbs.get(0).get("level"));
    assertEquals(
        Map.of("method", "GET", "url", "https://api.internal/prices", "status_code", 503L),
        crumbs.get(0).get("data"));
  }

  @Test
  void continuesCallersTraces() {
    Options o = new Options();
    o.setTracesSampleRate(0);
    Hub hub = ingest.hub(o);
    Span s =
        hub.spanBuilder("GET /")
            .continueTrace(
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01", "fw=1", "user=1")
            .start();
    assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", s.getTraceId());
    assertEquals("00f067aa0ba902b7", s.getParentSpanId());
    assertTrue(s.isSampled(), "the caller's decision holds");
    assertEquals("00-4bf92f3577b34da6a3ce929d0e0e4736-" + s.getSpanId() + "-01", s.traceparent());
    assertEquals("fw=1", s.tracestate());
    assertEquals("user=1", s.baggage());
    s.close();
    hub.flush(5000);
    assertEquals(0x301, spans(ingest.requests("/v1/traces")).get(0).get("flags"));

    Span unsampled =
        hub.spanBuilder("GET /")
            .continueTrace("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-00", null, null)
            .start();
    assertFalse(unsampled.isSampled());
    unsampled.close();
    for (String bad :
        new String[] {
          "",
          "00-xyz-00f067aa0ba902b7-01",
          "00-00000000000000000000000000000000-00f067aa0ba902b7-01",
          "ff-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
          "00-4bf92f3577b34da6a3ce929d0e0e4736-0000000000000000-01"
        }) {
      assertNull(Span.parseTraceparent(bad), bad);
    }
  }

  @Test
  void samplesTracesByTheSharedRule() {
    assertTrue(Span.sample("4bf92f3577b34da6ffffffffffffffff", 0.01));
    assertFalse(Span.sample("4bf92f3577b34da6a000000000000000", 0.5));
    assertTrue(Span.sample("4bf92f3577b34da6a080000000000000", 0.5));
    assertFalse(Span.sample("4bf92f3577b34da6a07ffffffffff000", 0.5));
    assertTrue(Span.sample("4bf92f3577b34da6a000000000000000", 1));
    assertFalse(Span.sample("4bf92f3577b34da6ffffffffffffffff", 0));
  }

  @Test
  void countsRequestSessions() {
    Options o = new Options();
    o.setRelease("shop@1.2.0");
    Hub hub = ingest.hub(o);
    String[] outcomes = {"ok", "ok", "handled", "crash"};
    for (int i = 0; i < outcomes.length; i++) {
      Hub request = hub.copy();
      request.getScope().setUser(new User("user-" + (i % 2)));
      Runnable end = request.startRequestSession();
      if (outcomes[i].equals("handled")) {
        request.captureException(new RuntimeException("x"));
      } else if (outcomes[i].equals("crash")) {
        request.captureException(new RuntimeException("y"), "servlet", false, null);
      }
      end.run();
      end.run(); // once
    }
    hub.flush(5000);
    List<FakeIngest.Received> reqs = ingest.requests("/v1/sessions");
    assertEquals(1, reqs.size());
    Map<String, Object> body = reqs.get(0).body();
    assertEquals("shop@1.2.0", body.get("release"));
    assertEquals("production", body.get("environment"));
    assertEquals("fixwire.java", ((Map<?, ?>) body.get("sdk")).get("name"));
    int exited = 0;
    int errored = 0;
    int crashed = 0;
    java.util.Set<Object> dids = new java.util.HashSet<>();
    for (Object x : (List<?>) body.get("aggregates")) {
      Map<?, ?> a = (Map<?, ?>) x;
      exited += (Integer) a.get("exited");
      errored += (Integer) a.get("errored");
      crashed += (Integer) a.get("crashed");
      dids.add(a.get("did"));
    }
    assertEquals(List.of(2, 1, 1), List.of(exited, errored, crashed));
    assertEquals(2, dids.size());
    assertTrue(dids.contains(Sessions.deviceId(new User("user-0"))));
    assertEquals(32, Sessions.deviceId(new User("user-0")).length());

    assertNull(ingest.hub(new Options()).getClient().sessions(), "no release, no sessions");
  }

  @Test
  void sendsCheckInsAndFeedback() throws Exception {
    Options o = new Options();
    o.setRelease("shop@1.2.0");
    Hub hub = ingest.hub(o);
    Hub.setMain(hub);
    try {
      CheckIn.MonitorConfig config =
          CheckIn.MonitorConfig.crontab("0 3 * * *").checkInMargin(5).timezone("Europe/Berlin");
      assertThrows(
          IllegalStateException.class,
          () ->
              Fixwire.withMonitor(
                  "nightly report",
                  config,
                  () -> {
                    throw new IllegalStateException("no data");
                  }));
      Feedback f = new Feedback("The refund was wrong");
      f.setScore(-3);
      f.setTraceId("4bf92f3577b34da6a3ce929d0e0e4736");
      assertNotNull(Fixwire.captureFeedback(f));
      assertNull(Fixwire.captureFeedback(new Feedback("  ")));
    } finally {
      Hub.setMain(new Hub(null, null));
    }
    hub.flush(5000);

    List<FakeIngest.Received> checkIns = ingest.requests("/v1/check-ins/nightly%20report");
    assertEquals(2, checkIns.size());
    Map<String, Object> start = checkIns.get(0).body();
    Map<String, Object> end = checkIns.get(1).body();
    assertEquals("in_progress", start.get("status"));
    assertEquals(
        Map.of(
            "schedule",
            Map.of("type", "crontab", "value", "0 3 * * *"),
            "checkin_margin",
            5,
            "timezone",
            "Europe/Berlin"),
        start.get("monitor_config"));
    assertEquals("error", end.get("status"));
    assertEquals(start.get("check_in_id"), end.get("check_in_id"));
    assertNotNull(end.get("duration"));
    assertNull(end.get("monitor_config"));

    List<FakeIngest.Received> fb = ingest.requests("/v1/feedback");
    assertEquals(1, fb.size());
    Map<String, Object> b = fb.get(0).body();
    assertEquals(-1.0, ((Number) b.get("score")).doubleValue());
    assertEquals("The refund was wrong", b.get("message"));
    assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", b.get("trace_id"));
    assertEquals("api", b.get("source"));
    assertEquals("shop@1.2.0", b.get("release"));
  }

  @Test
  void retriesAndHonoursRateLimits() {
    Transport.backoffUnitMillis = 10;
    try {
      Hub hub = ingest.hub(new Options());
      ingest.answer =
          (n, path) ->
              switch (n) {
                case 0 -> new FakeIngest.Answer(503, Map.of()); // retried
                case 1 -> new FakeIngest.Answer(200, Map.of("Fixwire-Rate-Limits", "3600:error"));
                default -> new FakeIngest.Answer(200, Map.of());
              };
      hub.captureMessage("first", null);
      hub.flush(5000);
      assertEquals(2, ingest.requests("/v1/logs").size(), "a retry");
      // Errors are paused for an hour (past the longest wait: dropped); feedback isn't.
      hub.captureMessage("dropped", null);
      Feedback f = new Feedback();
      f.setScore(1);
      hub.captureFeedback(f);
      hub.flush(5000);
      assertEquals(2, ingest.requests("/v1/logs").size());
      assertEquals(1, ingest.requests("/v1/feedback").size());
    } finally {
      Transport.backoffUnitMillis = 1000;
    }
  }

  @Test
  void dropsRefusedRequests() {
    Hub hub = ingest.hub(new Options());
    ingest.answer = (n, path) -> new FakeIngest.Answer(400, Map.of());
    hub.captureMessage("bad", null);
    hub.flush(5000);
    assertEquals(1, ingest.requests("").size());
  }

  @Test
  void budgetsCrashLoops() {
    Options o = new Options();
    o.getErrorBudget().setPerIssueBurst(3);
    Hub hub = ingest.hub(o);
    int sent = 0;
    for (int i = 0; i < 20; i++) {
      if (hub.captureMessage("order " + (1000 + i) + " failed", null) != null) {
        sent++;
      }
    }
    assertEquals(3, sent, "a crash loop sends its burst");
    assertNotNull(hub.captureMessage("another issue", null));
    Event probe = new Event();
    probe.setMessage("order 1 failed");
    hub.getClient().budget().age(Budget.issueOf(probe), 60_000);
    assertNotNull(hub.captureMessage("order 2000 failed", null), "a token after a minute");
    hub.flush(5000);
    List<Map<String, Object>> recs = logRecords(ingest.requests("/v1/logs"));
    assertEquals(17L, kv(recs.get(recs.size() - 1).get("attributes")).get("fixwire.suppressed"));

    Event a = new Event();
    a.getExceptions().add(exception("x", "a"));
    Event b = new Event();
    b.getExceptions().add(exception("x", "b"));
    assertNotEquals(Budget.issueOf(a), Budget.issueOf(b), "two call paths are one issue");
    Event m1 = new Event();
    m1.setMessage("user ada@example.com: 3 retries");
    Event m2 = new Event();
    m2.setMessage("user bob@example.org: 12 retries");
    assertEquals(Budget.issueOf(m1), Budget.issueOf(m2));
  }

  @Test
  void budgetFingerprintsTakeLinearTime() {
    // A backtracking \S+@\S+ took seconds on a few kilobytes of "a@a@…".
    for (String s : List.of("a@".repeat(100_000), "a".repeat(200_000), "a@b.".repeat(50_000))) {
      Event e = new Event();
      e.setMessage(s);
      assertTimeout(Duration.ofSeconds(1), () -> Budget.issueOf(e));
    }
  }

  @Test
  void capturesValuesThatHoldThemselves() {
    Hub hub = ingest.hub(new Options());
    Map<String, Object> order = new java.util.LinkedHashMap<>();
    List<Object> items = new ArrayList<>();
    order.put("items", items);
    items.add(order); // each holds the other
    List<Object> self = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      self.add(self);
    }
    List<Object> deep = new ArrayList<>();
    List<Object> cur = deep;
    for (int i = 0; i < 100_000; i++) {
      List<Object> next = new ArrayList<>();
      cur.add(next);
      cur = next;
    }
    Object broken =
        new Object() {
          @Override
          public String toString() {
            throw new IllegalStateException("no string");
          }
        };
    Object endless =
        new Object() {
          @Override
          public String toString() {
            return "x" + this; // entities that print each other
          }
        };
    hub.getScope().setExtra("order", order);
    hub.getScope().setExtra("self", self);
    hub.getScope().setExtra("deep", deep);
    hub.getScope().setExtra("broken", broken);
    hub.getScope().setExtra("endless", endless);
    assertTimeout(
        Duration.ofSeconds(5), () -> assertNotNull(hub.captureMessage("checkout failed", null)));
    hub.flush(5000);
    Map<String, Object> a = kv(logRecords(ingest.requests("/v1/logs")).get(0).get("attributes"));
    assertEquals(Map.of("items", List.of("[Circular ~]")), a.get("order"));
    assertEquals(List.of("[Circular ~]"), ((List<?>) a.get("self")).subList(0, 1));
    assertTrue(String.valueOf(a.get("deep")).contains("[Array]"), "deep nesting is cut");
    assertTrue(String.valueOf(a.get("broken")).startsWith("[io.fixwire.FixwireTest$"));
    assertTrue(String.valueOf(a.get("endless")).startsWith("[io.fixwire.FixwireTest$"));
  }

  @Test
  void capturesExceptionsWhoseMessageThrows() {
    Hub hub = ingest.hub(new Options());
    RuntimeException odd =
        new RuntimeException() {
          @Override
          public String getMessage() {
            throw new IllegalStateException("no message");
          }
        };
    assertNotNull(hub.captureException(odd));
  }

  @Test
  void refusesRedirects() throws Exception {
    Transport.backoffUnitMillis = 10;
    try (FakeIngest elsewhere = new FakeIngest()) {
      Hub hub = ingest.hub(new Options());
      String to = elsewhere.dsn().replace("publickey@", "") + "/v1/logs";
      ingest.answer = (n, path) -> new FakeIngest.Answer(307, Map.of("Location", to));
      hub.captureMessage("moved", null);
      assertTrue(hub.flush(5000));
      assertEquals(1, ingest.requests("").size(), "refused, not retried");
      assertTrue(elsewhere.requests("").isEmpty(), "the key goes to the DSN's host only");
    } finally {
      Transport.backoffUnitMillis = 1000;
    }
  }

  @Test
  void hugePausesDoNotOverflow() {
    Hub hub = ingest.hub(new Options());
    hub.getClient()
        .transport()
        .limit(Long.MAX_VALUE + ":error;made_up", System.currentTimeMillis());
    hub.captureMessage("paused", null);
    hub.flush(5000);
    assertTrue(ingest.requests("/v1/logs").isEmpty(), "paused past the longest wait: dropped");
  }

  @Test
  void spansCapAttributesAndCallersHeaders() {
    Options o = new Options();
    o.setTracesSampleRate(1);
    Hub hub = ingest.hub(o);
    String parent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
    Span big =
        hub.spanBuilder("GET /")
            .continueTrace(parent, "k=" + "v".repeat(600), "k=" + "v".repeat(9000))
            .start();
    assertNull(big.tracestate());
    assertNull(big.baggage());
    for (int i = 0; i < 1000; i++) {
      big.setAttribute("key." + i, i);
    }
    big.close();
    Span small = hub.spanBuilder("GET /").continueTrace(parent, "k=v", "user=1").startDetached();
    assertEquals("k=v", small.tracestate());
    assertEquals("user=1", small.baggage());
    hub.flush(5000);
    Map<String, Object> attrs = kv(spans(ingest.requests("/v1/traces")).get(0).get("attributes"));
    assertEquals(Span.MAX_ATTRIBUTES, attrs.size());
  }

  private static ExceptionValue exception(String type, String function) {
    ExceptionValue x = new ExceptionValue();
    x.setType(type);
    Frame f = new Frame();
    f.setModule("com.example.App");
    f.setFunction(function);
    f.setInApp(true);
    x.getFrames().add(f);
    return x;
  }

  @Test
  void julRecordsBecomeBreadcrumbsAndEvents() {
    Hub hub = ingest.hub(new Options());
    Hub.setMain(hub);
    Logger logger = Logger.getLogger("com.example.billing");
    logger.setUseParentHandlers(false);
    FixwireHandler handler = new FixwireHandler();
    logger.addHandler(handler);
    try {
      logger.fine("ignored");
      logger.log(java.util.logging.Level.INFO, "charging {0}", 500);
      IllegalStateException e = new IllegalStateException("card declined");
      logger.log(java.util.logging.Level.SEVERE, "charge failed", e);
      // Captured where it was caught, then logged: sent once.
      IllegalStateException twice = new IllegalStateException("twice");
      hub.captureException(twice);
      logger.log(java.util.logging.Level.SEVERE, "logged after capture", twice);
      logger.severe("no exception");
    } finally {
      logger.removeHandler(handler);
      Hub.setMain(new Hub(null, null));
    }
    hub.flush(5000);
    List<Map<String, Object>> recs = logRecords(ingest.requests("/v1/logs"));
    assertEquals(3, recs.size());
    Map<String, Object> a = kv(recs.get(0).get("attributes"));
    assertEquals("card declined", a.get("exception.message"));
    assertEquals("com.example.billing", a.get("logger"));
    assertEquals("charge failed", a.get("log.message"));
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> crumbs = (List<Map<String, Object>>) a.get("fixwire.breadcrumbs");
    assertEquals(1, crumbs.size());
    assertEquals("charging 500", crumbs.get(0).get("message"));
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> chain = (List<Map<String, Object>>) a.get("fixwire.exceptions");
    assertEquals("logging", ((Map<?, ?>) chain.get(0).get("mechanism")).get("type"));
    assertEquals("twice", kv(recs.get(1).get("attributes")).get("exception.message"));
    assertEquals("no exception", plain(cast(recs.get(2).get("body"))));
  }

  @Test
  void julHandlerSkipsWhatItsOwnCaptureLogs() {
    Logger logger = Logger.getLogger("com.example.loop");
    logger.setUseParentHandlers(false);
    FixwireHandler handler = new FixwireHandler();
    logger.addHandler(handler);
    Options o = new Options();
    o.getErrorBudget().setEnabled(false);
    o.setBeforeSend(
        e -> {
          logger.severe("sending " + e.getEventId()); // without a guard: until the stack overflows
          return e;
        });
    Hub hub = ingest.hub(o);
    Hub.setMain(hub);
    try {
      logger.severe("first");
    } finally {
      logger.removeHandler(handler);
      Hub.setMain(new Hub(null, null));
    }
    hub.flush(5000);
    assertEquals(1, logRecords(ingest.requests("/v1/logs")).size());
  }

  @Test
  void wrappedTasksCarryTheHub() throws Exception {
    Hub hub = ingest.hub(new Options());
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try (Hub.Binding b = hub.bind()) {
      Fixwire.setTag("request", "r1");
      pool.submit(Fixwire.wrap(() -> Fixwire.captureMessage("from the pool"))).get();
    } finally {
      pool.shutdown();
    }
    assertEquals(Hub.main(), Hub.current(), "the binding was undone");
    hub.flush(5000);
    Map<String, Object> a = kv(logRecords(ingest.requests("/v1/logs")).get(0).get("attributes"));
    assertEquals(Map.of("request", "r1"), a.get("fixwire.tags"));
  }

  @Test
  void normalizesGeneratedClassNames() {
    assertEquals(
        "com.example.Shop$$Lambda",
        Frames.normalize("com.example.Shop$$Lambda$123/0x0000000800c0b440"));
    assertEquals(
        "com.example.Shop$$Lambda",
        Frames.normalize("com.example.Shop$$Lambda/0x000001f2c0123456"));
    assertEquals(
        "com.example.Cart$$SpringCGLIB", Frames.normalize("com.example.Cart$$SpringCGLIB$$0"));
    assertEquals("jdk.proxy2.$Proxy", Frames.normalize("jdk.proxy2.$Proxy12"));
    assertEquals("com.example.Cart$Item", Frames.normalize("com.example.Cart$Item"));
    Options o = new Options();
    assertFalse(Frames.inApp("java.util.ArrayList", o));
    assertFalse(Frames.inApp("org.springframework.web.Servlet", o));
    assertTrue(Frames.inApp("com.example.shop.Cart", o));
    o.setInAppExcludes(List.of("com.example.shop.generated"));
    assertFalse(Frames.inApp("com.example.shop.generated.Api", o));
  }

  @Test
  void writesJson() {
    Map<String, Object> m = new java.util.LinkedHashMap<>();
    m.put("s", "quote \" slash \\ newline \n tab \t nul \u0000 sep   é 😀");
    m.put("n", List.of(1, 2.5, 3.0, Double.NaN, true));
    m.put("null", null);
    m.put("nested", Map.of("a", new int[] {1, 2}));
    assertEquals(
        "{\"s\":\"quote \\\" slash \\\\ newline \\n tab \\t nul \\u0000 sep \\u2028 é 😀\",\"n\":[1,2.5,3,null,true],"
            + "\"null\":null,\"nested\":{\"a\":[1,2]}}",
        Json.write(m));
    List<Object> deep = new ArrayList<>();
    List<Object> cur = deep;
    for (int i = 0; i < 100; i++) {
      List<Object> next = new ArrayList<>();
      cur.add(next);
      cur = next;
    }
    assertTrue(Json.write(deep).contains("null"), "deep nesting is cut");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> cast(Object o) {
    return (Map<String, Object>) o;
  }
}
