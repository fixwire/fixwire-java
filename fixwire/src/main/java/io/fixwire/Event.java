package io.fixwire;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An error or a message, as it is sent. {@link Options#setBeforeSend} sees it after the scope's
 * details are added.
 */
public final class Event {
  private String eventId;
  private long timestampMillis;
  private Level level;
  private String message;
  private List<ExceptionValue> exceptions = new ArrayList<>();
  private Map<String, String> tags = new LinkedHashMap<>();
  private Map<String, Map<String, Object>> contexts = new LinkedHashMap<>();
  private Map<String, Object> extra = new LinkedHashMap<>();
  private User user;
  private List<Breadcrumb> breadcrumbs = new ArrayList<>();
  private List<String> fingerprint = new ArrayList<>();
  private String transaction;
  private Request request;
  private String traceId;
  private String spanId;
  private Throwable throwable;
  int suppressed; // occurrences the error budget held back

  /** An empty event. */
  public Event() {}

  /**
   * The event's id: 32 hex characters, made when it is captured.
   *
   * @return the id, or null before it is captured
   */
  public String getEventId() {
    return eventId;
  }

  public void setEventId(String eventId) {
    this.eventId = eventId;
  }

  public long getTimestampMillis() {
    return timestampMillis;
  }

  public void setTimestampMillis(long timestampMillis) {
    this.timestampMillis = timestampMillis;
  }

  public Level getLevel() {
    return level;
  }

  public void setLevel(Level level) {
    this.level = level;
  }

  public String getMessage() {
    return message;
  }

  public void setMessage(String message) {
    this.message = message;
  }

  /**
   * The chain of exceptions, the outermost first.
   *
   * @return the exceptions; empty for a message
   */
  public List<ExceptionValue> getExceptions() {
    return exceptions;
  }

  public void setExceptions(List<ExceptionValue> exceptions) {
    this.exceptions = exceptions == null ? new ArrayList<ExceptionValue>() : exceptions;
  }

  public Map<String, String> getTags() {
    return tags;
  }

  public void setTags(Map<String, String> tags) {
    this.tags = tags == null ? new LinkedHashMap<String, String>() : tags;
  }

  public Map<String, Map<String, Object>> getContexts() {
    return contexts;
  }

  public void setContexts(Map<String, Map<String, Object>> contexts) {
    this.contexts = contexts == null ? new LinkedHashMap<String, Map<String, Object>>() : contexts;
  }

  public Map<String, Object> getExtra() {
    return extra;
  }

  public void setExtra(Map<String, Object> extra) {
    this.extra = extra == null ? new LinkedHashMap<String, Object>() : extra;
  }

  public User getUser() {
    return user;
  }

  public void setUser(User user) {
    this.user = user;
  }

  public List<Breadcrumb> getBreadcrumbs() {
    return breadcrumbs;
  }

  public void setBreadcrumbs(List<Breadcrumb> breadcrumbs) {
    this.breadcrumbs = breadcrumbs == null ? new ArrayList<Breadcrumb>() : breadcrumbs;
  }

  /**
   * A custom grouping; {@code {{ default }}} stands for Fixwire's own.
   *
   * @return the fingerprint; empty for Fixwire's grouping
   */
  public List<String> getFingerprint() {
    return fingerprint;
  }

  public void setFingerprint(List<String> fingerprint) {
    this.fingerprint = fingerprint == null ? new ArrayList<String>() : fingerprint;
  }

  /**
   * The route or task the event happened in.
   *
   * @return the transaction, or null
   */
  public String getTransaction() {
    return transaction;
  }

  public void setTransaction(String transaction) {
    this.transaction = transaction;
  }

  public Request getRequest() {
    return request;
  }

  public void setRequest(Request request) {
    this.request = request;
  }

  public String getTraceId() {
    return traceId;
  }

  public void setTraceId(String traceId) {
    this.traceId = traceId;
  }

  public String getSpanId() {
    return spanId;
  }

  public void setSpanId(String spanId) {
    this.spanId = spanId;
  }

  /**
   * The throwable the event was made from, for {@link Options#setBeforeSend}.
   *
   * @return the throwable, or null
   */
  public Throwable getThrowable() {
    return throwable;
  }

  void setThrowable(Throwable throwable) {
    this.throwable = throwable;
  }
}
