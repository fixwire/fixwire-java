package io.fixwire.logback;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxy;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import io.fixwire.Breadcrumb;
import io.fixwire.Client;
import io.fixwire.Event;
import io.fixwire.Fixwire;
import io.fixwire.Hub;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Sends Logback records to Fixwire: {@code INFO} and above become breadcrumbs, {@code ERROR} events
 * (as the record's exception when it has one, once if the app captured it already). The MDC goes
 * with them. Log lines themselves are not shipped.
 *
 * <pre>{@code
 * <appender name="FIXWIRE" class="io.fixwire.logback.FixwireAppender">
 *   <breadcrumbLevel>INFO</breadcrumbLevel>
 *   <eventLevel>ERROR</eventLevel>
 * </appender>
 * <root level="INFO"><appender-ref ref="FIXWIRE"/></root>
 * }</pre>
 */
public final class FixwireAppender extends UnsynchronizedAppenderBase<ILoggingEvent> {
  private Level breadcrumbLevel = Level.INFO;
  private Level eventLevel = Level.ERROR;

  /**
   * Records at or above this level become breadcrumbs (default {@code INFO}).
   *
   * @param level such as {@code DEBUG}
   */
  public void setBreadcrumbLevel(String level) {
    this.breadcrumbLevel = Level.toLevel(level, Level.INFO);
  }

  /**
   * Records at or above this level are sent as events (default {@code ERROR}).
   *
   * @param level such as {@code WARN}
   */
  public void setEventLevel(String level) {
    this.eventLevel = Level.toLevel(level, Level.ERROR);
  }

  @Override
  protected void append(ILoggingEvent record) {
    String logger = record.getLoggerName() == null ? "" : record.getLoggerName();
    if (logger.startsWith("io.fixwire") || Hub.isCapturing() || !Fixwire.isEnabled()) {
      // The SDK's own, logged while it captures (from beforeSend: it would be captured again,
      // or recurse), or nothing to send to.
      return;
    }
    Level level = record.getLevel();
    if (!level.isGreaterOrEqual(breadcrumbLevel) && !level.isGreaterOrEqual(eventLevel)) {
      return;
    }
    Hub hub = Hub.current();
    String message = record.getFormattedMessage();
    if (level.isGreaterOrEqual(eventLevel)) {
      Throwable thrown = throwableOf(record.getThrowableProxy());
      Client client = hub.getClient();
      if (thrown != null && client != null && client.isCaptured(thrown)) {
        return; // sent already, where it was caught
      }
      Map<String, String> mdc = record.getMDCPropertyMap();
      hub.withScope(
          s -> {
            s.setExtra("logger", logger);
            if (mdc != null && !mdc.isEmpty()) {
              s.setContext("mdc", new LinkedHashMap<String, Object>(mdc));
            }
            if (thrown != null) {
              s.setExtra("log.message", message);
              hub.captureException(thrown, "logging", true, levelOf(level));
            } else {
              Event e = new Event();
              e.setMessage(message);
              e.setLevel(levelOf(level));
              hub.captureEvent(e);
            }
          });
    } else {
      Breadcrumb b = new Breadcrumb(logger, message);
      b.setType("log");
      b.setLevel(levelOf(level));
      b.setTimestampMillis(record.getTimeStamp());
      hub.addBreadcrumb(b);
    }
  }

  private static Throwable throwableOf(IThrowableProxy proxy) {
    return proxy instanceof ThrowableProxy ? ((ThrowableProxy) proxy).getThrowable() : null;
  }

  static io.fixwire.Level levelOf(Level level) {
    if (level.isGreaterOrEqual(Level.ERROR)) {
      return io.fixwire.Level.ERROR;
    }
    if (level.isGreaterOrEqual(Level.WARN)) {
      return io.fixwire.Level.WARNING;
    }
    if (level.isGreaterOrEqual(Level.INFO)) {
      return io.fixwire.Level.INFO;
    }
    return io.fixwire.Level.DEBUG;
  }
}
