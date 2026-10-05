package io.fixwire;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A timed piece of work in a trace. A span without a parent in the process (a request, a job) is a
 * segment: it is sent with the spans under it when it finishes. Use it with try-with-resources:
 *
 * <pre>{@code
 * try (Span span = Fixwire.startSpan("SELECT carts", "db.query")) {
 *   ...
 * }
 * }</pre>
 *
 * <p>While open, the span is the current one of the scope it was started on: errors captured there
 * link to it, and spans started there are its children.
 */
public final class Span implements AutoCloseable {
  /** OpenTelemetry's span kinds. */
  public enum Kind {
    /** Work inside the process. */
    INTERNAL(1),
    /** Serving a request. */
    SERVER(2),
    /** Calling another service. */
    CLIENT(3),
    /** Sending a message. */
    PRODUCER(4),
    /** Handling a message. */
    CONSUMER(5);

    final int number;

    Kind(int number) {
      this.number = number;
    }
  }

  /** The spans a segment keeps until it is sent. */
  static final int MAX_CHILDREN = 1000;

  private final String traceId;
  private final String spanId;
  private final String parentSpanId;
  private volatile String name;
  private final String op;
  private final Kind kind;
  private final boolean sampled;
  private final boolean remoteParent;
  private final String tracestate;
  private final String baggage;
  private final long startNanos;
  private final long startMono;
  private long endNanos;
  private final Map<String, Object> attributes = new LinkedHashMap<>();
  private boolean failed;
  private String statusMessage;
  private final Span segment;
  private List<Span> children = new ArrayList<>();
  private boolean sent;
  private final Client client;
  private final Scope scope;
  private final Span previous;

  private Span(Builder b, Scope scope, Span parent) {
    this.name = b.name;
    this.op = b.op;
    this.client = b.hub.getClient();
    this.scope = scope;
    this.previous = scope == null ? null : scope.getSpan();
    this.spanId = Ids.newId(8);
    this.startNanos = System.currentTimeMillis() * 1_000_000L;
    this.startMono = System.nanoTime();
    this.attributes.putAll(b.attributes);
    String[] continued = b.traceparent == null ? null : parseTraceparent(b.traceparent);
    if (continued != null) {
      traceId = continued[0];
      parentSpanId = continued[1];
      sampled = "1".equals(continued[2]);
      remoteParent = true;
      tracestate = b.tracestate;
      baggage = b.baggage;
      segment = this;
    } else if (parent != null) {
      traceId = parent.traceId;
      parentSpanId = parent.spanId;
      sampled = parent.sampled;
      remoteParent = false;
      tracestate = parent.tracestate;
      baggage = parent.baggage;
      segment = parent.segment;
    } else {
      traceId = Ids.newId(16);
      parentSpanId = null;
      sampled = sample(traceId, client == null ? 0 : client.options().getTracesSampleRate());
      remoteParent = false;
      tracestate = null;
      baggage = null;
      segment = this;
    }
    this.kind = b.kind != null ? b.kind : kindOf(op);
  }

  /** Starts spans. */
  public static final class Builder {
    private final String name;
    private final Hub hub;
    private String op;
    private Kind kind;
    private final Map<String, Object> attributes = new LinkedHashMap<>();
    private Span parent;
    private boolean noParent;
    private String traceparent;
    private String tracestate;
    private String baggage;

    Builder(String name, Hub hub) {
      this.name = name;
      this.hub = hub;
    }

    /**
     * The span's operation: {@code http.server}, {@code db.query}, {@code task}, …
     *
     * @param op the operation
     * @return this builder
     */
    public Builder op(String op) {
      this.op = op;
      return this;
    }

    /**
     * The span's kind; by default, what the operation implies.
     *
     * @param kind the kind
     * @return this builder
     */
    public Builder kind(Kind kind) {
      this.kind = kind;
      return this;
    }

    /**
     * Sets an attribute (OpenTelemetry's semantic conventions).
     *
     * @param key the attribute
     * @param value its value
     * @return this builder
     */
    public Builder attribute(String key, Object value) {
      attributes.put(key, value);
      return this;
    }

    /**
     * Starts the span under this one rather than the scope's current span (for work handed to
     * another thread).
     *
     * @param parent the parent, or null to start a new trace
     * @return this builder
     */
    public Builder parent(Span parent) {
      this.parent = parent;
      this.noParent = parent == null;
      return this;
    }

    /**
     * Continues a caller's trace from its W3C headers; a malformed {@code traceparent} starts a new
     * trace. The caller's sampling decision holds.
     *
     * @param traceparent the {@code traceparent} header
     * @param tracestate the {@code tracestate} header, or null
     * @param baggage the {@code baggage} header, or null
     * @return this builder
     */
    public Builder continueTrace(String traceparent, String tracestate, String baggage) {
      this.traceparent = traceparent;
      this.tracestate = tracestate;
      this.baggage = baggage;
      return this;
    }

    /**
     * Starts the span and makes it the current one of the hub's scope until it is closed.
     *
     * @return the span
     */
    public Span start() {
      Scope scope = hub.getScope();
      Span p = noParent ? null : parent != null ? parent : scope.getSpan();
      Span s = new Span(this, scope, p);
      scope.setSpan(s);
      return s;
    }
  }

  static Kind kindOf(String op) {
    if (op == null) {
      return Kind.INTERNAL;
    }
    if (op.equals("http.server") || op.endsWith(".server")) {
      return Kind.SERVER;
    }
    if (op.equals("http.client") || op.startsWith("db") || op.endsWith(".client")) {
      return Kind.CLIENT;
    }
    if (op.endsWith(".publish")) {
      return Kind.PRODUCER;
    }
    if (op.endsWith(".process")) {
      return Kind.CONSUMER;
    }
    return Kind.INTERNAL;
  }

  /**
   * Decides a new trace the way every Fixwire SDK does: kept when its id's last 56 bits, as a
   * fraction of 2^56, are at least {@code 1 - rate}.
   */
  static boolean sample(String traceId, double rate) {
    if (rate <= 0) {
      return false;
    }
    if (rate >= 1) {
      return true;
    }
    if (traceId.length() < 14) {
      return false;
    }
    long n;
    try {
      n = Long.parseLong(traceId.substring(traceId.length() - 14), 16);
    } catch (NumberFormatException e) {
      return false;
    }
    return (double) n / (double) (1L << 56) >= 1 - rate;
  }

  /**
   * Reads {@code 00-<trace id>-<parent id>-<flags>}: the trace id, parent id and "1" or "0" for
   * sampled; null when malformed.
   */
  static String[] parseTraceparent(String h) {
    String[] p = h.trim().split("-", -1);
    if (p.length < 4
        || p[0].length() != 2
        || p[0].equalsIgnoreCase("ff")
        || p[1].length() != 32
        || p[2].length() != 16
        || p[3].length() != 2) {
      return null;
    }
    if (!hex(p[0])
        || !hex(p[1])
        || !hex(p[2])
        || !hex(p[3])
        || p[1].matches("0+")
        || p[2].matches("0+")) {
      return null;
    }
    int flags = Integer.parseInt(p[3], 16);
    return new String[] {
      p[1].toLowerCase(Locale.ROOT), p[2].toLowerCase(Locale.ROOT), (flags & 1) == 1 ? "1" : "0"
    };
  }

  private static boolean hex(String s) {
    for (int i = 0; i < s.length(); i++) {
      if (Character.digit(s.charAt(i), 16) < 0) {
        return false;
      }
    }
    return true;
  }

  public String getTraceId() {
    return traceId;
  }

  public String getSpanId() {
    return spanId;
  }

  /**
   * The parent's id, or null for the trace's first span.
   *
   * @return the parent's span id
   */
  public String getParentSpanId() {
    return parentSpanId;
  }

  public String getName() {
    return name;
  }

  /**
   * Renames the span, such as after the route a request matched.
   *
   * @param name the new name
   */
  public void setName(String name) {
    this.name = name;
  }

  public String getOp() {
    return op;
  }

  public Kind getKind() {
    return kind;
  }

  /**
   * Whether the trace is kept: an unsampled span still carries the trace to the services it calls.
   *
   * @return whether it is sampled
   */
  public boolean isSampled() {
    return sampled;
  }

  /**
   * The W3C {@code traceparent} header that continues this span's trace in a service it calls.
   *
   * @return the header's value
   */
  public String traceparent() {
    return "00-" + traceId + "-" + spanId + (sampled ? "-01" : "-00");
  }

  /**
   * The caller's {@code tracestate}, passed on.
   *
   * @return the header's value, or null
   */
  public String tracestate() {
    return tracestate;
  }

  /**
   * The caller's {@code baggage}, passed on.
   *
   * @return the header's value, or null
   */
  public String baggage() {
    return baggage;
  }

  /**
   * Sets an attribute.
   *
   * @param key the attribute
   * @param value its value
   */
  public synchronized void setAttribute(String key, Object value) {
    attributes.put(key, value);
  }

  /**
   * Marks the span failed.
   *
   * @param error what failed, or null
   */
  public synchronized void setError(Throwable error) {
    failed = true;
    if (error != null) {
      statusMessage = error.getMessage() == null ? error.getClass().getName() : error.getMessage();
      attributes.put("error.type", error.getClass().getName());
    }
  }

  /**
   * Marks the span failed.
   *
   * @param message what failed
   */
  public synchronized void setError(String message) {
    failed = true;
    statusMessage = message;
  }

  /**
   * Ends the span. A segment is sent with the spans finished under it; a span finishing after its
   * segment was sent goes alone.
   */
  public void finish() {
    synchronized (this) {
      if (endNanos != 0) {
        return;
      }
      endNanos = startNanos + Math.max(System.nanoTime() - startMono, 0);
    }
    if (!sampled || client == null || !client.isEnabled()) {
      return;
    }
    List<Span> send = null;
    synchronized (segment) {
      if (segment == this) {
        send = children;
        send.add(this);
        children = new ArrayList<>();
        sent = true;
      } else if (segment.sent) {
        send = new ArrayList<>();
        send.add(this);
      } else if (segment.children.size() < MAX_CHILDREN) {
        segment.children.add(this);
      }
    }
    if (send != null) {
      client.sendSpans(send);
    }
  }

  /** Finishes the span and makes the span before it current again. */
  @Override
  public void close() {
    finish();
    if (scope != null) {
      synchronized (scope) {
        if (scope.getSpan() == this) {
          scope.setSpan(previous);
        }
      }
    }
  }

  String segmentName() {
    return segment.name;
  }

  /** The span as the protocol sends it, attributes still plain. */
  synchronized Map<String, Object> record() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("traceId", traceId);
    m.put("spanId", spanId);
    if (parentSpanId != null) {
      m.put("parentSpanId", parentSpanId);
    }
    m.put("name", name);
    m.put("kind", kind.number);
    m.put("startTimeUnixNano", Long.toString(startNanos));
    m.put("endTimeUnixNano", Long.toString(endNanos));
    Map<String, Object> attrs = new LinkedHashMap<>(attributes);
    attrs.put("fixwire.op", op);
    m.put("attributes", attrs);
    Map<String, Object> status = new LinkedHashMap<>();
    if (failed) {
      status.put("code", 2);
      if (statusMessage != null) {
        status.put("message", statusMessage);
      }
    } else {
      status.put("code", 1);
    }
    m.put("status", status);
    m.put("flags", 0x100 | (remoteParent ? 0x200 : 0) | (sampled ? 1 : 0));
    return m;
  }

  /** Random ids in hex. */
  static final class Ids {
    private Ids() {}

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    static String newId(int bytes) {
      char[] out = new char[bytes * 2];
      ThreadLocalRandom r = ThreadLocalRandom.current();
      for (int i = 0; i < out.length; i++) {
        out[i] = HEX[r.nextInt(16)];
      }
      if (new String(out).matches("0+")) {
        out[out.length - 1] = '1'; // all zeros is not a valid id
      }
      return new String(out);
    }
  }
}
