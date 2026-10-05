package io.fixwire.jul;

import io.fixwire.Breadcrumb;
import io.fixwire.Event;
import io.fixwire.Fixwire;
import io.fixwire.Hub;
import io.fixwire.Level;
import java.text.MessageFormat;
import java.util.logging.Handler;
import java.util.logging.LogRecord;

/**
 * Sends {@code java.util.logging} records to Fixwire: {@code INFO} and above become breadcrumbs,
 * {@code SEVERE} and above events (as the record's exception when it has one). Log lines themselves
 * are not shipped.
 *
 * <pre>{@code
 * Logger.getLogger("").addHandler(new FixwireHandler());
 * }</pre>
 */
public final class FixwireHandler extends Handler {
  private final java.util.logging.Level breadcrumbLevel;
  private final java.util.logging.Level eventLevel;

  /** Breadcrumbs from {@code INFO}, events from {@code SEVERE}. */
  public FixwireHandler() {
    this(java.util.logging.Level.INFO, java.util.logging.Level.SEVERE);
  }

  /**
   * A handler with its own levels.
   *
   * @param breadcrumbLevel records at or above it become breadcrumbs
   * @param eventLevel records at or above it are sent as events
   */
  public FixwireHandler(
      java.util.logging.Level breadcrumbLevel, java.util.logging.Level eventLevel) {
    this.breadcrumbLevel = breadcrumbLevel;
    this.eventLevel = eventLevel;
    setLevel(breadcrumbLevel.intValue() < eventLevel.intValue() ? breadcrumbLevel : eventLevel);
  }

  @Override
  public void publish(LogRecord record) {
    if (record == null || !isLoggable(record) || !Fixwire.isEnabled()) {
      return;
    }
    String logger = record.getLoggerName() == null ? "" : record.getLoggerName();
    if (logger.startsWith("io.fixwire")) {
      return; // the SDK's own
    }
    try {
      Hub hub = Hub.current();
      String message = format(record);
      Level level = levelOf(record.getLevel());
      if (record.getLevel().intValue() >= eventLevel.intValue()) {
        Throwable thrown = record.getThrown();
        if (thrown != null && hub.getClient().isCaptured(thrown)) {
          return; // sent already, where it was caught
        }
        if (thrown != null) {
          hub.withScope(
              s -> {
                s.setExtra("logger", logger);
                s.setExtra("log.message", message);
                hub.captureException(thrown, "logging", true, level);
              });
        } else {
          Event e = new Event();
          e.setMessage(message);
          e.setLevel(level);
          e.getExtra().put("logger", logger);
          hub.captureEvent(e);
        }
      } else if (record.getLevel().intValue() >= breadcrumbLevel.intValue()) {
        Breadcrumb b = new Breadcrumb(logger, message);
        b.setType("log");
        b.setLevel(level);
        b.setTimestampMillis(record.getMillis());
        hub.addBreadcrumb(b);
      }
    } catch (RuntimeException e) {
      reportError(
          "fixwire: could not record a log record",
          e,
          java.util.logging.ErrorManager.WRITE_FAILURE);
    }
  }

  private static String format(LogRecord r) {
    String msg = r.getMessage();
    Object[] params = r.getParameters();
    if (msg == null || params == null || params.length == 0 || msg.indexOf('{') < 0) {
      return msg == null ? "" : msg;
    }
    try {
      return MessageFormat.format(msg, params);
    } catch (IllegalArgumentException e) {
      return msg;
    }
  }

  static Level levelOf(java.util.logging.Level l) {
    int v = l.intValue();
    if (v >= java.util.logging.Level.SEVERE.intValue()) {
      return Level.ERROR;
    }
    if (v >= java.util.logging.Level.WARNING.intValue()) {
      return Level.WARNING;
    }
    if (v >= java.util.logging.Level.INFO.intValue()) {
      return Level.INFO;
    }
    return Level.DEBUG;
  }

  @Override
  public void flush() {}

  @Override
  public void close() {}
}
