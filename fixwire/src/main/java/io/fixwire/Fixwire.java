package io.fixwire;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

/**
 * The Fixwire SDK for Java and Kotlin: errors, traces, release health, cron check-ins and feedback,
 * sent over Fixwire protocol v1 (OpenTelemetry's OTLP plus a few Fixwire endpoints).
 *
 * <pre>{@code
 * Fixwire.init(o -> {
 *   o.setDsn("https://fw_pk_live_…@ingest.eu.fixwire.io");
 *   o.setRelease("api@1.4.0");
 * });
 * try {
 *   charge(order);
 * } catch (PaymentException e) {
 *   Fixwire.captureException(e);
 * }
 * }</pre>
 *
 * <p>Without a DSN (and without {@code FIXWIRE_DSN}) the SDK does nothing. Captures never block: a
 * daemon thread sends, and the JVM's shutdown waits briefly for what is left.
 */
public final class Fixwire {
  private Fixwire() {}

  private static Thread shutdownHook;
  private static UncaughtHandler uncaught;

  /**
   * Sets up the SDK from options.
   *
   * @param configure sets the options
   * @throws IllegalArgumentException for a malformed DSN
   */
  public static void init(Consumer<Options> configure) {
    Options o = new Options();
    configure.accept(o);
    init(o);
  }

  /**
   * Sets up the SDK: the main hub gets a client for the options. A second call replaces the first's
   * client.
   *
   * @param options the options
   * @throws IllegalArgumentException for a malformed DSN
   */
  public static synchronized void init(Options options) {
    Client client = new Client(options);
    Client before = Hub.main().getClient();
    Hub.main().bindClient(client);
    if (before != null) {
      before.close(options.getShutdownTimeoutMillis());
    }
    if (!client.isEnabled()) {
      return;
    }
    if (options.isUncaughtExceptionHandler() && uncaught == null) {
      uncaught = new UncaughtHandler(Thread.getDefaultUncaughtExceptionHandler());
      Thread.setDefaultUncaughtExceptionHandler(uncaught);
    }
    if (shutdownHook == null && options.getShutdownTimeoutMillis() > 0) {
      shutdownHook =
          new Thread(
              () -> {
                Client c = Hub.main().getClient();
                if (c != null) {
                  c.close(c.options().getShutdownTimeoutMillis());
                }
              },
              "fixwire-shutdown");
      try {
        Runtime.getRuntime().addShutdownHook(shutdownHook);
      } catch (IllegalStateException e) {
        shutdownHook = null; // already shutting down
      }
    }
  }

  /**
   * Whether the SDK sends (it was set up with a DSN).
   *
   * @return whether it is enabled
   */
  public static boolean isEnabled() {
    Client c = Hub.current().getClient();
    return c != null && c.isEnabled();
  }

  /**
   * Flushes and stops the SDK.
   *
   * @param timeoutMillis the longest wait for what is left to be sent
   */
  public static synchronized void close(long timeoutMillis) {
    Client c = Hub.main().getClient();
    Hub.main().bindClient(null);
    if (c != null) {
      c.close(timeoutMillis);
    }
    if (uncaught != null && Thread.getDefaultUncaughtExceptionHandler() == uncaught) {
      Thread.setDefaultUncaughtExceptionHandler(uncaught.previous);
    }
    uncaught = null;
    if (shutdownHook != null) {
      try {
        Runtime.getRuntime().removeShutdownHook(shutdownHook);
      } catch (IllegalStateException e) {
        // shutting down: the hook runs anyway
      }
      shutdownHook = null;
    }
  }

  /**
   * Waits until what was captured is sent, or the timeout. Call it before a short-lived program
   * exits on its own.
   *
   * @param timeoutMillis the longest wait
   * @return false when time ran out
   */
  public static boolean flush(long timeoutMillis) {
    return Hub.current().flush(timeoutMillis);
  }

  /**
   * Sends an exception and its causes.
   *
   * @param error the exception
   * @return the event's id, or null when it was not sent
   */
  public static String captureException(Throwable error) {
    return Hub.current().captureException(error);
  }

  /**
   * Sends a message at the scope's level (else {@code info}).
   *
   * @param message the message
   * @return the event's id, or null when it was not sent
   */
  public static String captureMessage(String message) {
    return Hub.current().captureMessage(message, null);
  }

  /**
   * Sends a message.
   *
   * @param message the message
   * @param level its level
   * @return the event's id, or null when it was not sent
   */
  public static String captureMessage(String message, Level level) {
    return Hub.current().captureMessage(message, level);
  }

  /**
   * Sends an event as it is, with what the scope knows.
   *
   * @param event the event
   * @return its id, or null when it was not sent
   */
  public static String captureEvent(Event event) {
    return Hub.current().captureEvent(event);
  }

  /**
   * The id of the last event sent, such as for a feedback form after a crash.
   *
   * @return the id, or null
   */
  public static String lastEventId() {
    return Hub.lastEventId();
  }

  /**
   * Records something that happened.
   *
   * @param breadcrumb the breadcrumb
   */
  public static void addBreadcrumb(Breadcrumb breadcrumb) {
    Hub.current().addBreadcrumb(breadcrumb);
  }

  /**
   * Records something that happened.
   *
   * @param category what it is about, such as {@code cart}
   * @param message what happened
   */
  public static void addBreadcrumb(String category, String message) {
    Hub.current().addBreadcrumb(new Breadcrumb(category, message));
  }

  /**
   * Changes the current scope.
   *
   * @param change what to change
   */
  public static void configureScope(Consumer<Scope> change) {
    Hub.current().configureScope(change);
  }

  /**
   * Runs something with a copy of the current scope.
   *
   * @param work what to run
   */
  public static void withScope(Consumer<Scope> work) {
    Hub.current().withScope(work);
  }

  /**
   * Sets who the work is for.
   *
   * @param user the user, or null
   */
  public static void setUser(User user) {
    Hub.current().getScope().setUser(user);
  }

  /**
   * Sets a searchable tag.
   *
   * @param key the tag
   * @param value its value
   */
  public static void setTag(String key, String value) {
    Hub.current().getScope().setTag(key, value);
  }

  /**
   * Sets a named group of details.
   *
   * @param name the group
   * @param values its details
   */
  public static void setContext(String name, Map<String, Object> values) {
    Hub.current().getScope().setContext(name, values);
  }

  /**
   * Sets a detail sent with events.
   *
   * @param key the detail
   * @param value its value
   */
  public static void setExtra(String key, Object value) {
    Hub.current().getScope().setExtra(key, value);
  }

  /**
   * Starts a span under the current one (or a new trace) and makes it current until it is closed.
   *
   * @param name the span's name
   * @param op its operation, such as {@code db.query}
   * @return the span
   */
  public static Span startSpan(String name, String op) {
    return Hub.current().spanBuilder(name).op(op).start();
  }

  /**
   * A span builder, for kinds, attributes, other parents and continued traces.
   *
   * @param name the span's name
   * @return the builder
   */
  public static Span.Builder spanBuilder(String name) {
    return Hub.current().spanBuilder(name);
  }

  /**
   * The current span.
   *
   * @return the span, or null
   */
  public static Span currentSpan() {
    return Hub.current().getScope().getSpan();
  }

  /**
   * Reports a run of a scheduled job by hand (see {@link #withMonitor}).
   *
   * @param checkIn the check-in
   * @return its id, or null when it was not sent
   */
  public static String captureCheckIn(CheckIn checkIn) {
    Client c = Hub.current().getClient();
    return c == null ? null : c.captureCheckIn(checkIn);
  }

  /**
   * Runs a job as a run of a monitor: in progress, then ok, or error when it throws (the exception
   * goes on).
   *
   * @param monitor the monitor's slug
   * @param config creates or updates the monitor; may be null
   * @param job the job
   * @param <T> what the job returns
   * @return what the job returned
   * @throws Exception what the job threw
   */
  public static <T> T withMonitor(String monitor, CheckIn.MonitorConfig config, Callable<T> job)
      throws Exception {
    CheckIn start = new CheckIn(monitor, CheckIn.Status.IN_PROGRESS);
    start.setConfig(config);
    String id = captureCheckIn(start);
    long began = System.nanoTime();
    CheckIn.Status status = CheckIn.Status.ERROR;
    try {
      T out = job.call();
      status = CheckIn.Status.OK;
      return out;
    } finally {
      if (id != null) {
        CheckIn end = new CheckIn(monitor, status);
        end.setId(id);
        end.setDurationMillis(Math.max((System.nanoTime() - began) / 1_000_000, 1));
        captureCheckIn(end);
      }
    }
  }

  /**
   * Sends what someone said about an error or an AI answer.
   *
   * @param feedback the feedback
   * @return its id, or null when it holds neither a message nor a score
   */
  public static String captureFeedback(Feedback feedback) {
    return Hub.current().captureFeedback(feedback);
  }

  /**
   * Wraps a task so that it runs with a copy of the current hub, on whichever thread runs it
   * (executors, thread pools).
   *
   * @param task the task
   * @return the wrapped task
   */
  public static Runnable wrap(Runnable task) {
    final Hub hub = Hub.current().copy();
    return () -> {
      try (Hub.Binding b = hub.bind()) {
        task.run();
      }
    };
  }

  /**
   * Wraps a task so that it runs with a copy of the current hub.
   *
   * @param task the task
   * @param <T> what the task returns
   * @return the wrapped task
   */
  public static <T> Callable<T> wrap(Callable<T> task) {
    final Hub hub = Hub.current().copy();
    return () -> {
      try (Hub.Binding b = hub.bind()) {
        return task.call();
      }
    };
  }

  /** Reports exceptions no code caught, then lets the handler before run. */
  static final class UncaughtHandler implements Thread.UncaughtExceptionHandler {
    final Thread.UncaughtExceptionHandler previous;

    UncaughtHandler(Thread.UncaughtExceptionHandler previous) {
      this.previous = previous;
    }

    @Override
    public void uncaughtException(Thread t, Throwable e) {
      try {
        Hub hub = Hub.current();
        hub.captureException(e, "UncaughtExceptionHandler", false, Level.FATAL);
        Client c = hub.getClient();
        if (c != null) {
          hub.flush(c.options().getShutdownTimeoutMillis());
        }
      } catch (RuntimeException ignored) {
        // reporting must never hide the crash
      }
      if (previous != null) {
        previous.uncaughtException(t, e);
      } else if (!(e instanceof ThreadDeath)) {
        System.err.print("Exception in thread \"" + t.getName() + "\" ");
        e.printStackTrace(System.err);
      }
    }
  }
}
