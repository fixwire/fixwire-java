package io.fixwire;

import io.fixwire.internal.Json;
import io.fixwire.internal.redact.Redactor;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Sends to one project. Most programs use the one {@link Fixwire#init} sets up, through {@link
 * Fixwire}'s methods or a {@link Hub}.
 */
public final class Client {
  /** The SDK's name and version, as {@code telemetry.sdk.*} say it. */
  static final String SDK_NAME = "fixwire.java";

  static final String SDK_VERSION = "0.1.0";

  private final Options opts;
  private final boolean enabled;
  private final Transport transport;
  private final Sessions sessions;
  private final ScheduledExecutorService ticker;
  private final Budget budget;
  private final Redactor redactor;
  private final Map<Throwable, Boolean> captured =
      Collections.synchronizedMap(new WeakHashMap<Throwable, Boolean>());

  /**
   * A client for the options; without a DSN it is disabled and sends nothing.
   *
   * @param opts the options, defaults filled in from the environment
   * @throws IllegalArgumentException for a malformed DSN
   */
  public Client(Options opts) {
    opts.applyDefaults();
    this.opts = opts;
    this.budget = new Budget(opts.getErrorBudget());
    this.redactor = opts.isRedact() ? Redactor.create(opts.getSensitiveKeys()) : null;
    if (Options.empty(opts.getDsn())) {
      enabled = false;
      transport = null;
      sessions = null;
      ticker = null;
      return;
    }
    Dsn dsn = Dsn.parse(opts.getDsn());
    enabled = true;
    transport = new Transport(dsn, opts);
    if (opts.sessionsOn()) {
      sessions = new Sessions(this);
      ticker =
          Executors.newSingleThreadScheduledExecutor(
              r -> {
                Thread t = new Thread(r, "fixwire-sessions");
                t.setDaemon(true);
                return t;
              });
      long every = opts.getSessionIntervalMillis();
      ticker.scheduleAtFixedRate(sessions::send, every, every, TimeUnit.MILLISECONDS);
    } else {
      sessions = null;
      ticker = null;
    }
  }

  /**
   * Whether the client sends: false without a DSN.
   *
   * @return whether it is enabled
   */
  public boolean isEnabled() {
    return enabled;
  }

  /**
   * The client's options, defaults filled in.
   *
   * @return the options
   */
  public Options options() {
    return opts;
  }

  /**
   * Whether trace headers may go to a URL: it holds one of the trace propagation targets.
   *
   * @param url the request's URL
   * @return whether to send {@code traceparent}
   */
  public boolean shouldPropagate(String url) {
    for (String t : opts.getTracePropagationTargets()) {
      if (t != null && !t.isEmpty() && url.contains(t)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Whether a throwable was captured already, so that an integration that sees it again (a log line
   * of it) does not send it twice.
   *
   * @param t the throwable
   * @return whether it was captured
   */
  public boolean isCaptured(Throwable t) {
    return t != null && captured.containsKey(t);
  }

  /** Sends an event with what the scope knows: its id, or null when not sent. */
  String capture(Event e, Scope scope) {
    if (!enabled) {
      return null;
    }
    if (scope != null) {
      scope.applyTo(e);
      // The session counts the error whether or not it is sent.
      if (!e.getExceptions().isEmpty()) {
        scope.markSession(!e.getExceptions().get(0).isHandled());
      } else if (e.getLevel() == Level.ERROR || e.getLevel() == Level.FATAL) {
        scope.markSession(false);
      }
    }
    if (e.getThrowable() != null) {
      captured.put(e.getThrowable(), Boolean.TRUE);
    }
    int held = budget.allow(Budget.issueOf(e), System.currentTimeMillis());
    if (held < 0) {
      transport.log("dropped an event: over the error budget");
      return null;
    }
    if (opts.getSampleRate() < 1
        && ThreadLocalRandom.current().nextDouble() >= opts.getSampleRate()) {
      return null;
    }
    e.suppressed = held;
    if (e.getEventId() == null) {
      e.setEventId(Span.Ids.newId(16));
    }
    if (e.getTimestampMillis() == 0) {
      e.setTimestampMillis(System.currentTimeMillis());
    }
    if (e.getLevel() == null) {
      e.setLevel(e.getExceptions().isEmpty() ? Level.INFO : Level.ERROR);
    }
    if (!opts.isSendDefaultPii()) {
      if (e.getUser() != null) {
        e.getUser().setIpAddress(null);
      }
    } else if (e.getRequest() != null && e.getRequest().getClientAddress() != null) {
      if (e.getUser() == null) {
        e.setUser(new User());
      }
      if (e.getUser().getIpAddress() == null) {
        e.getUser().setIpAddress(e.getRequest().getClientAddress());
      }
    }
    if (opts.getBeforeSend() != null) {
      try {
        e = opts.getBeforeSend().execute(e);
      } catch (RuntimeException ex) {
        transport.log("beforeSend failed, sending the event as it is: %s", ex);
      }
      if (e == null) {
        return null;
      }
    }
    Map<String, Object> body;
    try {
      body = Otlp.logs(opts, Collections.singletonList(Otlp.eventRecord(e, redactor)));
    } catch (RuntimeException ex) {
      transport.log("encoding an event: %s", ex);
      return null;
    }
    if (!transport.send(
        "/v1/logs", Transport.ERROR, Json.write(body).getBytes(StandardCharsets.UTF_8))) {
      return null;
    }
    return e.getEventId();
  }

  /**
   * Reports a run of a scheduled job: {@code IN_PROGRESS} when it starts, then {@code OK} or {@code
   * ERROR} with the returned id.
   *
   * @param checkIn the check-in
   * @return its id, or null when it was not sent
   */
  public String captureCheckIn(CheckIn checkIn) {
    if (!enabled || checkIn.getMonitor() == null || checkIn.getMonitor().trim().isEmpty()) {
      return null;
    }
    String id = checkIn.getId() != null ? checkIn.getId() : Span.Ids.newId(16);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("sdk", sdk());
    body.put("check_in_id", id);
    body.put("status", checkIn.getStatus().wire);
    body.put("environment", opts.getEnvironment());
    if (checkIn.getDurationMillis() > 0) {
      body.put("duration", checkIn.getDurationMillis() / 1000.0);
    }
    if (checkIn.getConfig() != null) {
      body.put("monitor_config", checkIn.getConfig().toMap());
    }
    String path = "/v1/check-ins/" + CheckIn.pathSegment(checkIn.getMonitor());
    return sendJson(path, Transport.CHECK_IN, body) ? id : null;
  }

  /** Sends feedback; its id, or null when it was not sent. */
  String sendFeedback(Map<String, Object> body) {
    String id = (String) body.get("feedback_id");
    Map<String, Object> kept = new LinkedHashMap<>();
    for (String k :
        new String[] {
          "sdk",
          "feedback_id",
          "timestamp",
          "event_id",
          "trace_id",
          "release",
          "environment",
          "source"
        }) {
      if (body.containsKey(k)) {
        kept.put(k, body.remove(k));
      }
    }
    Map<String, Object> out = Otlp.scrub(Otlp.plainMap(body), redactor);
    out.putAll(kept);
    return sendJson("/v1/feedback", Transport.FEEDBACK, out) ? id : null;
  }

  void sendSpans(List<Span> spans) {
    if (!enabled) {
      return;
    }
    try {
      byte[] body = Json.write(Otlp.traces(opts, spans, redactor)).getBytes(StandardCharsets.UTF_8);
      transport.send("/v1/traces", Transport.SPAN, body);
    } catch (RuntimeException e) {
      transport.log("encoding spans: %s", e);
    }
  }

  boolean sendJson(String path, String category, Map<String, Object> body) {
    if (!enabled) {
      return false;
    }
    return transport.send(path, category, Json.write(body).getBytes(StandardCharsets.UTF_8));
  }

  Sessions sessions() {
    return sessions;
  }

  static Map<String, Object> sdk() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("name", SDK_NAME);
    m.put("version", SDK_VERSION);
    return m;
  }

  /**
   * Waits until what was captured is sent, or the timeout.
   *
   * @param timeoutMillis the longest wait
   * @return false when time ran out
   */
  public boolean flush(long timeoutMillis) {
    if (!enabled) {
      return true;
    }
    if (sessions != null) {
      sessions.send();
    }
    return transport.flush(timeoutMillis);
  }

  /**
   * Flushes and stops the client.
   *
   * @param timeoutMillis the longest wait for what is left to be sent
   */
  public void close(long timeoutMillis) {
    if (!enabled) {
      return;
    }
    if (ticker != null) {
      ticker.shutdownNow();
    }
    flush(timeoutMillis);
    transport.close();
  }

  Transport transport() {
    return transport;
  }

  Budget budget() {
    return budget;
  }
}
