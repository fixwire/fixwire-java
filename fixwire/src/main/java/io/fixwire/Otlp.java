package io.fixwire;

import io.fixwire.internal.redact.Redactor;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Events and spans as OTLP JSON (sdks/PROTOCOL.md §3, §4), within the limits of §13: the app's
 * values bounded, every string redacted and then cut to {@link Options#getMaxValueLength}.
 */
final class Otlp {
  private Otlp() {}

  /** The resource every request carries: who sends, release, environment. */
  static Map<String, Object> resource(Options o) {
    Map<String, Object> a = new LinkedHashMap<>();
    a.put("service.name", o.getServiceName());
    a.put("service.version", o.getRelease());
    a.put("deployment.environment.name", o.getEnvironment());
    a.put("host.name", o.getServerName());
    a.put("telemetry.sdk.name", Client.SDK_NAME);
    a.put("telemetry.sdk.version", Client.SDK_VERSION);
    a.put("telemetry.sdk.language", "java");
    Limits.cutAll(a, o.getMaxValueLength());
    return Collections.<String, Object>singletonMap("attributes", attributes(a));
  }

  private static Map<String, Object> scope() {
    Map<String, Object> s = new LinkedHashMap<>();
    s.put("name", Client.SDK_NAME);
    s.put("version", Client.SDK_VERSION);
    return s;
  }

  /** An OTLP logs export of records. */
  static Map<String, Object> logs(Options o, List<Map<String, Object>> records) {
    Map<String, Object> sl = new LinkedHashMap<>();
    sl.put("scope", scope());
    sl.put("logRecords", records);
    Map<String, Object> rl = new LinkedHashMap<>();
    rl.put("resource", resource(o));
    rl.put("scopeLogs", Collections.singletonList(sl));
    return Collections.<String, Object>singletonMap("resourceLogs", Collections.singletonList(rl));
  }

  /** An OTLP traces export of span records. */
  static Map<String, Object> traces(Options o, List<Map<String, Object>> records) {
    Map<String, Object> ss = new LinkedHashMap<>();
    ss.put("scope", scope());
    ss.put("spans", records);
    Map<String, Object> rs = new LinkedHashMap<>();
    rs.put("resource", resource(o));
    rs.put("scopeSpans", Collections.singletonList(ss));
    return Collections.<String, Object>singletonMap("resourceSpans", Collections.singletonList(rs));
  }

  /** A span as OTLP sends it: its attributes bounded, redacted and cut, its name and status too. */
  static Map<String, Object> spanRecord(Span s, Redactor redactor, Options o) {
    int max = o.getMaxValueLength();
    Map<String, Object> m = s.record();
    @SuppressWarnings("unchecked")
    Map<String, Object> attrs = (Map<String, Object>) m.get("attributes");
    Object op = attrs.remove("fixwire.op");
    Map<String, Object> plainAttrs = new LinkedHashMap<>();
    for (Map.Entry<String, Object> a : attrs.entrySet()) {
      plainAttrs.put(Limits.string(a.getKey()), Limits.bounded(a.getValue()));
    }
    plainAttrs = Limits.finish(plainAttrs, redactor, max);
    plainAttrs.put("fixwire.op", Limits.text((String) op, redactor, max));
    m.put("attributes", attributes(plainAttrs));
    m.put("name", Limits.text(Limits.string(m.get("name")), redactor, max));
    @SuppressWarnings("unchecked")
    Map<String, Object> status = (Map<String, Object>) m.get("status");
    if (status.get("message") != null) {
      status.put("message", Limits.text((String) status.get("message"), redactor, max));
    }
    return m;
  }

  /** An error or a message as a log record (sdks/PROTOCOL.md §4), redacted. */
  static Map<String, Object> eventRecord(Event e, Redactor redactor, Options o) {
    int max = o.getMaxValueLength();
    Map<String, Object> a = new LinkedHashMap<>();
    a.put("fixwire.tags", Limits.bounded(e.getTags()));
    a.put("fixwire.transaction", e.getTransaction());
    a.put("fixwire.fingerprint", Limits.bounded(e.getFingerprint()));
    if (e.suppressed > 0) {
      a.put("fixwire.suppressed", e.suppressed);
    }
    User u = e.getUser();
    if (u != null) {
      a.put("user.id", u.getId());
      a.put("user.email", u.getEmail());
      a.put("user.name", u.getUsername());
      a.put("client.address", u.getIpAddress());
    }
    a.put("fixwire.contexts", Limits.boundedEach(e.getContexts()));
    for (Map.Entry<String, Object> x : Limits.boundedEach(e.getExtra()).entrySet()) {
      if (!a.containsKey(x.getKey())) {
        a.put(x.getKey(), x.getValue());
      }
    }
    if (!e.getBreadcrumbs().isEmpty()) {
      List<Object> crumbs = new ArrayList<>();
      for (Breadcrumb b : e.getBreadcrumbs()) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("timestamp", b.getTimestampMillis() / 1000.0);
        c.put("type", b.getType());
        c.put("category", b.getCategory());
        c.put("message", b.getMessage());
        c.put("level", b.getLevel() == null ? null : b.getLevel().wireName());
        c.put("data", Limits.bounded(b.getData()));
        crumbs.add(c);
      }
      a.put("fixwire.breadcrumbs", crumbs);
    }
    Request r = e.getRequest();
    if (r != null) {
      a.put("http.request.method", r.getMethod());
      a.put("url.full", r.getUrl());
      a.put("url.query", r.getQuery());
      a.put("http.route", r.getRoute());
      for (Map.Entry<String, Object> h : Limits.boundedEach(r.getHeaders()).entrySet()) {
        String name = h.getKey().toLowerCase(Locale.ROOT);
        a.put(
            name.equals("user-agent") ? "user_agent.original" : "http.request.header." + name,
            h.getValue());
      }
    }
    Map<String, Object> record = new LinkedHashMap<>();
    record.put("timeUnixNano", Long.toString(e.getTimestampMillis() * 1_000_000L));
    Level level = e.getLevel() == null ? Level.ERROR : e.getLevel();
    record.put("severityNumber", level.severityNumber());
    record.put("severityText", level.wireName().toUpperCase(Locale.ROOT));
    if (e.getTraceId() != null) {
      record.put("traceId", e.getTraceId());
      record.put("spanId", e.getSpanId());
    }
    if (e.getExceptions().isEmpty()) {
      record.put("eventName", "fixwire.message");
      record.put(
          "body", value(Limits.text(e.getMessage() == null ? "" : e.getMessage(), redactor, max)));
    } else {
      record.put("eventName", "exception");
      ExceptionValue outer = e.getExceptions().get(0);
      a.put("exception.type", outer.getType());
      a.put("exception.message", outer.getMessage());
      List<Object> chain = new ArrayList<>();
      boolean handled = true;
      for (ExceptionValue x : head(e.getExceptions(), Frames.MAX_CHAIN)) {
        List<Object> frames = new ArrayList<>();
        // The newest frames, where it was thrown, are the last.
        List<Frame> all = x.getFrames();
        for (Frame f : all.subList(Math.max(all.size() - o.getMaxStackFrames(), 0), all.size())) {
          Map<String, Object> fm = new LinkedHashMap<>();
          fm.put("function", f.getFunction());
          fm.put("module", f.getModule());
          fm.put("file", f.getFile());
          if (f.getLine() > 0) {
            fm.put("line", f.getLine());
          }
          fm.put("in_app", f.isInApp());
          frames.add(fm);
        }
        Map<String, Object> mech = new LinkedHashMap<>();
        mech.put("type", x.getMechanism());
        mech.put("handled", x.isHandled());
        Map<String, Object> xm = new LinkedHashMap<>();
        xm.put("type", x.getType());
        xm.put("message", x.getMessage());
        xm.put("module", x.getModule());
        xm.put("mechanism", mech);
        xm.put("frames", frames);
        chain.add(xm);
        handled &= x.isHandled();
      }
      a.put("fixwire.exceptions", chain);
      if (!handled) {
        a.put("fixwire.handled", false);
      }
      if (e.getMessage() != null && !e.getMessage().isEmpty()) {
        record.put("body", value(Limits.text(e.getMessage(), redactor, max)));
      }
    }
    Object id = e.getEventId();
    Map<String, Object> plain = Limits.finish(a, redactor, max);
    plain.put("fixwire.event_id", id);
    record.put("attributes", attributes(plain));
    return record;
  }

  private static <T> List<T> head(List<T> l, int n) {
    return l.size() <= n ? l : l.subList(0, n);
  }

  /**
   * Leaves an attribute out of a record; whether it was there. An error over the size limit sheds
   * its breadcrumbs, then its contexts.
   */
  static boolean drop(Map<String, Object> record, String key) {
    Object attrs = record.get("attributes");
    if (!(attrs instanceof List)) {
      return false;
    }
    for (Iterator<?> it = ((List<?>) attrs).iterator(); it.hasNext(); ) {
      Object kv = it.next();
      if (kv instanceof Map && key.equals(((Map<?, ?>) kv).get("key"))) {
        it.remove();
        return true;
      }
    }
    return false;
  }

  /** OTLP key-values, empty values left out. */
  static List<Object> attributes(Map<String, ?> m) {
    List<Object> out = new ArrayList<>(m.size());
    for (Map.Entry<String, ?> e : m.entrySet()) {
      if (empty(e.getValue())) {
        continue;
      }
      Map<String, Object> kv = new LinkedHashMap<>();
      kv.put("key", e.getKey());
      kv.put("value", value(e.getValue()));
      out.add(kv);
    }
    return out;
  }

  static boolean empty(Object v) {
    return v == null
        || v instanceof String && ((String) v).isEmpty()
        || v instanceof Collection && ((Collection<?>) v).isEmpty()
        || v instanceof Map && ((Map<?, ?>) v).isEmpty();
  }

  /**
   * A plain value (maps with string keys, lists, strings, numbers, booleans) as an OTLP AnyValue.
   */
  static Map<String, Object> value(Object p) {
    if (p == null) {
      return Collections.<String, Object>singletonMap("stringValue", "");
    }
    if (p instanceof String) {
      return Collections.<String, Object>singletonMap("stringValue", p);
    }
    if (p instanceof Boolean) {
      return Collections.<String, Object>singletonMap("boolValue", p);
    }
    if (p instanceof Integer || p instanceof Long || p instanceof Short || p instanceof Byte) {
      return Collections.<String, Object>singletonMap("intValue", p.toString());
    }
    if (p instanceof BigInteger) {
      BigInteger b = (BigInteger) p;
      return b.bitLength() < 64
          ? Collections.<String, Object>singletonMap("intValue", b.toString())
          : Collections.<String, Object>singletonMap("stringValue", b.toString());
    }
    if (p instanceof Number) {
      double d =
          p instanceof BigDecimal ? ((BigDecimal) p).doubleValue() : ((Number) p).doubleValue();
      if (Double.isNaN(d) || Double.isInfinite(d)) {
        return Collections.<String, Object>singletonMap("stringValue", Limits.number(d).toString());
      }
      return Collections.<String, Object>singletonMap("doubleValue", d);
    }
    if (p instanceof List) {
      List<Object> values = new ArrayList<>();
      for (Object e : (List<?>) p) {
        values.add(value(e));
      }
      return Collections.<String, Object>singletonMap(
          "arrayValue", Collections.<String, Object>singletonMap("values", values));
    }
    if (p instanceof Map) {
      @SuppressWarnings("unchecked")
      Map<String, Object> map = (Map<String, Object>) p;
      return Collections.<String, Object>singletonMap(
          "kvlistValue", Collections.<String, Object>singletonMap("values", attributes(map)));
    }
    return value(Limits.bounded(p)); // not plain: as a value the app gave
  }
}
