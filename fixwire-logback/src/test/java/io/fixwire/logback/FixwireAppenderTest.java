package io.fixwire.logback;

import static io.fixwire.FakeIngest.kv;
import static io.fixwire.FakeIngest.logRecords;
import static io.fixwire.FakeIngest.plain;
import static org.junit.jupiter.api.Assertions.assertEquals;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import io.fixwire.FakeIngest;
import io.fixwire.Fixwire;
import io.fixwire.Hub;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

class FixwireAppenderTest {
  @Test
  void recordsBecomeBreadcrumbsAndEvents() throws Exception {
    try (FakeIngest ingest = new FakeIngest()) {
      Fixwire.init(
          o -> {
            o.setDsn(ingest.dsn());
            o.setUncaughtExceptionHandler(false);
            o.setShutdownTimeoutMillis(0);
          });
      LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
      FixwireAppender appender = new FixwireAppender();
      appender.setContext(context);
      appender.start();
      Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);
      root.addAppender(appender);
      try {
        org.slf4j.Logger log = LoggerFactory.getLogger("com.example.billing");
        log.debug("ignored");
        log.info("charging {} cents", 500);
        MDC.put("request_id", "req_1");
        log.error("charge failed", new IllegalStateException("card declined"));
        MDC.clear();
        IllegalStateException twice = new IllegalStateException("twice");
        Hub.current().captureException(twice);
        log.error("logged after capture", twice); // sent once, where it was caught
        log.warn("slow");
        log.error("no exception");
      } finally {
        root.detachAppender(appender);
        Fixwire.close(5000);
      }
      List<Map<String, Object>> recs = logRecords(ingest.requests("/v1/logs"));
      assertEquals(3, recs.size(), recs.toString());
      Map<String, Object> a = kv(recs.get(0).get("attributes"));
      assertEquals("card declined", a.get("exception.message"));
      assertEquals("com.example.billing", a.get("logger"));
      assertEquals("charge failed", a.get("log.message"));
      assertEquals(Map.of("mdc", Map.of("request_id", "req_1")), a.get("fixwire.contexts"));
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> crumbs = (List<Map<String, Object>>) a.get("fixwire.breadcrumbs");
      assertEquals("charging 500 cents", crumbs.get(crumbs.size() - 1).get("message"));
      assertEquals("twice", kv(recs.get(1).get("attributes")).get("exception.message"));
      @SuppressWarnings("unchecked")
      Map<String, Object> body = (Map<String, Object>) recs.get(2).get("body");
      assertEquals("no exception", plain(body));
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> later =
          (List<Map<String, Object>>) kv(recs.get(2).get("attributes")).get("fixwire.breadcrumbs");
      assertEquals("warning", later.get(later.size() - 1).get("level"));
    }
  }
}
