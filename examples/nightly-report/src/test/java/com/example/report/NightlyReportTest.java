package com.example.report;

import static io.fixwire.FakeIngest.kv;
import static io.fixwire.FakeIngest.logRecords;
import static io.fixwire.FakeIngest.spans;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.fixwire.FakeIngest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Runs the job against a fake ingest and checks what Fixwire receives. */
class NightlyReportTest {
  @Test
  void reportsTheRunAndTheFailedAccount() throws Exception {
    try (FakeIngest ingest = new FakeIngest()) {
      assertEquals(1, NightlyReport.run(ingest.dsn()), "one of three accounts fails");

      List<FakeIngest.Received> checkIns = ingest.requests("/v1/check-ins/nightly-report");
      assertEquals(2, checkIns.size());
      Map<String, Object> start = checkIns.get(0).body();
      Map<String, Object> end = checkIns.get(1).body();
      assertEquals("in_progress", start.get("status"));
      assertEquals("error", end.get("status"));
      assertEquals(start.get("check_in_id"), end.get("check_in_id"));
      assertEquals(
          Map.of("type", "crontab", "value", "0 3 * * *"),
          ((Map<?, ?>) start.get("monitor_config")).get("schedule"));

      List<Map<String, Object>> events = logRecords(ingest.requests("/v1/logs"));
      assertEquals(2, events.size(), events.toString());
      Map<String, Object> failure = kv(events.get(0).get("attributes"));
      assertEquals(
          "com.example.report.NightlyReport$NoInvoicesException", failure.get("exception.type"));
      assertEquals(Map.of("account", "globex"), failure.get("fixwire.tags"));
      assertEquals("the report for globex failed", failure.get("log.message"));
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> crumbs =
          (List<Map<String, Object>>) failure.get("fixwire.breadcrumbs");
      assertEquals("building the report for globex", crumbs.get(crumbs.size() - 1).get("message"));

      Map<String, Object> summary = events.get(1);
      assertEquals(13, summary.get("severityNumber"));
      assertNull(kv(summary.get("attributes")).get("fixwire.tags"), "the account's tag stayed");

      List<Map<String, Object>> spans = spans(ingest.requests("/v1/traces"));
      assertEquals(4, spans.size(), "the job and an account each");
      for (Map<String, Object> s : spans) {
        assertEquals(events.get(0).get("traceId"), s.get("traceId"));
      }
    }
  }
}
