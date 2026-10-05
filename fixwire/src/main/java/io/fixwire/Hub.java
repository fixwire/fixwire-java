package io.fixwire;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Pairs a client with a stack of scopes. {@link Fixwire}'s methods use the current thread's hub:
 * the main hub, unless a request or a task bound a copy of its own ({@link #copy}, {@link #bind}).
 */
public final class Hub {
  private static volatile Hub main = new Hub(null, new Scope());
  private static final ThreadLocal<Hub> CURRENT = new ThreadLocal<>();

  private volatile Client client;
  private final Deque<Scope> scopes = new ArrayDeque<>();

  /**
   * A hub for a client and a scope.
   *
   * @param client the client, or null
   * @param scope the scope, or null for an empty one
   */
  public Hub(Client client, Scope scope) {
    this.client = client;
    this.scopes.push(scope == null ? new Scope() : scope);
  }

  /**
   * The current thread's hub: the one bound to it, else the main hub.
   *
   * @return the hub
   */
  public static Hub current() {
    Hub h = CURRENT.get();
    return h != null ? h : main;
  }

  /**
   * The hub threads use when none is bound to them.
   *
   * @return the main hub
   */
  public static Hub main() {
    return main;
  }

  /**
   * The hub's client.
   *
   * @return the client, or null before {@link Fixwire#init}
   */
  public Client getClient() {
    return client;
  }

  /**
   * Makes a client the hub's.
   *
   * @param client the client
   */
  public void bindClient(Client client) {
    this.client = client;
  }

  /**
   * The hub's current scope.
   *
   * @return the scope
   */
  public Scope getScope() {
    synchronized (scopes) {
      return scopes.peek();
    }
  }

  /**
   * A hub with the same client and a copy of the current scope, for work that runs apart (a
   * request, a task on another thread).
   *
   * @return the copy
   */
  public Hub copy() {
    return new Hub(client, getScope().copy());
  }

  /**
   * Makes this the current thread's hub until the binding is closed:
   *
   * <pre>{@code
   * try (Hub.Binding b = Hub.current().copy().bind()) {
   *   handle(request);
   * }
   * }</pre>
   *
   * @return the binding
   */
  public Binding bind() {
    Hub previous = CURRENT.get();
    CURRENT.set(this);
    return new Binding(previous);
  }

  /** A hub bound to a thread; closing it restores the hub before. */
  public static final class Binding implements AutoCloseable {
    private final Hub previous;

    private Binding(Hub previous) {
      this.previous = previous;
    }

    @Override
    public void close() {
      if (previous == null) {
        CURRENT.remove();
      } else {
        CURRENT.set(previous);
      }
    }
  }

  /**
   * Changes the current scope.
   *
   * @param change what to change
   */
  public void configureScope(Consumer<Scope> change) {
    change.accept(getScope());
  }

  /**
   * Runs something with a copy of the current scope: what it sets there is gone afterwards.
   *
   * @param work what to run
   */
  public void withScope(Consumer<Scope> work) {
    Scope s = getScope().copy();
    synchronized (scopes) {
      scopes.push(s);
    }
    try {
      work.accept(s);
    } finally {
      synchronized (scopes) {
        scopes.remove(s);
      }
    }
  }

  /**
   * Sends an exception and its causes.
   *
   * @param error the exception
   * @return the event's id, or null when it was not sent
   */
  public String captureException(Throwable error) {
    return captureException(error, "generic", true, null);
  }

  /**
   * Sends an exception as an integration caught it.
   *
   * @param error the exception
   * @param mechanism how it was caught, such as {@code servlet}
   * @param handled false for a crash: nothing else handled it
   * @param level the level, or null for {@code error} ({@code fatal} when not handled)
   * @return the event's id, or null when it was not sent
   */
  public String captureException(Throwable error, String mechanism, boolean handled, Level level) {
    Client c = client;
    if (error == null || c == null || !c.isEnabled()) {
      return null;
    }
    Event e = new Event();
    e.setThrowable(error);
    e.setExceptions(Frames.chain(error, mechanism, handled, c.options()));
    e.setLevel(level != null ? level : handled ? null : Level.FATAL);
    return remember(c.capture(e, getScope()));
  }

  /**
   * Sends a message.
   *
   * @param message the message
   * @param level its level, or null for the scope's (else {@code info})
   * @return the event's id, or null when it was not sent
   */
  public String captureMessage(String message, Level level) {
    Client c = client;
    if (c == null || !c.isEnabled()) {
      return null;
    }
    Event e = new Event();
    e.setMessage(message);
    e.setLevel(level);
    return remember(c.capture(e, getScope()));
  }

  /**
   * Sends an event as it is, with what the scope knows.
   *
   * @param event the event
   * @return its id, or null when it was not sent
   */
  public String captureEvent(Event event) {
    Client c = client;
    if (event == null || c == null || !c.isEnabled()) {
      return null;
    }
    return remember(c.capture(event, getScope()));
  }

  private static volatile String lastEventId;

  private static String remember(String id) {
    if (id != null) {
      lastEventId = id;
    }
    return id;
  }

  static String lastEventId() {
    return lastEventId;
  }

  /**
   * Records something that happened on the current scope.
   *
   * @param breadcrumb the breadcrumb
   */
  public void addBreadcrumb(Breadcrumb breadcrumb) {
    Client c = client;
    int max = 100;
    if (c != null) {
      max = c.options().getMaxBreadcrumbs();
      Options.BeforeBreadcrumb before = c.options().getBeforeBreadcrumb();
      if (before != null) {
        try {
          breadcrumb = before.execute(breadcrumb);
        } catch (RuntimeException e) {
          // keep the breadcrumb as it was
        }
        if (breadcrumb == null) {
          return;
        }
      }
    }
    getScope().addBreadcrumb(breadcrumb, max);
  }

  /**
   * Sends feedback.
   *
   * @param f the feedback
   * @return its id, or null when it holds neither a message nor a score
   */
  public String captureFeedback(Feedback f) {
    Client c = client;
    if (c == null || !c.isEnabled()) {
      return null;
    }
    String message = f.getMessage() == null ? "" : f.getMessage().trim();
    double score = Double.isNaN(f.getScore()) || Double.isInfinite(f.getScore()) ? 0 : f.getScore();
    score = Math.max(-1, Math.min(1, score));
    if (message.isEmpty() && score == 0) {
      return null;
    }
    Scope scope = getScope();
    User user = scope.getUser();
    Span span = scope.getSpan();
    String id = Span.Ids.newId(16);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("sdk", Client.sdk());
    body.put("feedback_id", id);
    body.put("timestamp", System.currentTimeMillis() / 1000.0);
    body.put("source", f.getSource() == null ? "api" : f.getSource());
    body.put("environment", c.options().getEnvironment());
    put(body, "message", message);
    if (score != 0) {
      body.put("score", score);
    }
    put(
        body,
        "trace_id",
        f.getTraceId() != null ? f.getTraceId() : span == null ? null : span.getTraceId());
    put(body, "event_id", f.getEventId());
    put(body, "name", f.getName() != null ? f.getName() : user == null ? null : user.getUsername());
    put(body, "email", f.getEmail() != null ? f.getEmail() : user == null ? null : user.getEmail());
    put(body, "url", f.getUrl());
    put(body, "release", c.options().getRelease());
    return c.sendFeedback(body);
  }

  private static void put(Map<String, Object> m, String key, String value) {
    if (value != null && !value.isEmpty()) {
      m.put(key, value);
    }
  }

  /**
   * Starts the session of the request the current scope serves, for release health; run the
   * returned action when the request ends. HTTP integrations do this.
   *
   * @return what ends the session
   */
  public Runnable startRequestSession() {
    final Client c = client;
    if (c == null || c.sessions() == null) {
      return () -> {};
    }
    final Sessions.RequestSession rs = new Sessions.RequestSession();
    final Scope scope = getScope();
    synchronized (scope) {
      scope.session = rs;
    }
    return new Runnable() {
      private boolean ended;

      @Override
      public synchronized void run() {
        if (!ended) {
          ended = true;
          c.sessions()
              .record(rs.status(), Sessions.deviceId(scope.getUser()), System.currentTimeMillis());
        }
      }
    };
  }

  /**
   * A span builder whose span starts under this hub's current span.
   *
   * @param name the span's name
   * @return the builder
   */
  public Span.Builder spanBuilder(String name) {
    return new Span.Builder(name, this);
  }

  /**
   * Waits until what was captured is sent, or the timeout.
   *
   * @param timeoutMillis the longest wait
   * @return false when time ran out
   */
  public boolean flush(long timeoutMillis) {
    Client c = client;
    return c == null || c.flush(timeoutMillis);
  }

  static void setMain(Hub hub) {
    main = hub;
  }
}
