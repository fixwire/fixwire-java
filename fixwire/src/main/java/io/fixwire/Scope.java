package io.fixwire;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What is known about the work under way (the user, tags, contexts, breadcrumbs, the request, the
 * span), added to every event captured with it. A request gets its own copy (see {@link Hub#copy}),
 * so what it sets stays with it. Safe for use from several threads.
 */
public final class Scope {
  private User user;
  private final Map<String, String> tags = new LinkedHashMap<>();
  private final Map<String, Map<String, Object>> contexts = new LinkedHashMap<>();
  private final Map<String, Object> extra = new LinkedHashMap<>();
  private final ArrayDeque<Breadcrumb> breadcrumbs = new ArrayDeque<>();
  private Level level;
  private List<String> fingerprint = new ArrayList<>();
  private String transaction;
  private Request request;
  private Span span;
  Sessions.RequestSession session;

  /** An empty scope. */
  public Scope() {}

  /**
   * A copy, for work that runs apart.
   *
   * @return the copy
   */
  public synchronized Scope copy() {
    Scope s = new Scope();
    s.user = user == null ? null : user.copy();
    s.tags.putAll(tags);
    for (Map.Entry<String, Map<String, Object>> e : contexts.entrySet()) {
      s.contexts.put(e.getKey(), new LinkedHashMap<>(e.getValue()));
    }
    s.extra.putAll(extra);
    s.breadcrumbs.addAll(breadcrumbs);
    s.level = level;
    s.fingerprint = new ArrayList<>(fingerprint);
    s.transaction = transaction;
    s.request = request;
    s.span = span;
    s.session = session;
    return s;
  }

  /**
   * Sets who the work is for.
   *
   * @param user the user, or null to forget them
   */
  public synchronized void setUser(User user) {
    this.user = user == null ? null : user.copy();
  }

  /**
   * Who the work is for.
   *
   * @return a copy of the user, or null
   */
  public synchronized User getUser() {
    return user == null ? null : user.copy();
  }

  /**
   * Sets a searchable tag.
   *
   * @param key the tag
   * @param value its value; null removes it
   */
  public synchronized void setTag(String key, String value) {
    if (value == null) {
      tags.remove(key);
    } else {
      tags.put(key, value);
    }
  }

  /**
   * Removes a tag.
   *
   * @param key the tag
   */
  public synchronized void removeTag(String key) {
    tags.remove(key);
  }

  /**
   * Sets a named group of details, such as an order's id and items.
   *
   * @param name the group
   * @param values its details; null removes it
   */
  public synchronized void setContext(String name, Map<String, Object> values) {
    if (values == null) {
      contexts.remove(name);
    } else {
      contexts.put(name, new LinkedHashMap<>(values));
    }
  }

  /**
   * Sets a detail sent with events.
   *
   * @param key the detail
   * @param value its value; null removes it
   */
  public synchronized void setExtra(String key, Object value) {
    if (value == null) {
      extra.remove(key);
    } else {
      extra.put(key, value);
    }
  }

  /**
   * Sets the level of the events captured with the scope.
   *
   * @param level the level, or null for the default
   */
  public synchronized void setLevel(Level level) {
    this.level = level;
  }

  /**
   * Groups the events captured with the scope by these strings; {@code {{ default }}} stands for
   * Fixwire's own grouping.
   *
   * @param fingerprint the fingerprint
   */
  public synchronized void setFingerprint(String... fingerprint) {
    this.fingerprint = new ArrayList<>(Arrays.asList(fingerprint));
  }

  /**
   * Names the route or task the work is for.
   *
   * @param transaction such as {@code GET /items/{id}}
   */
  public synchronized void setTransaction(String transaction) {
    this.transaction = transaction;
  }

  /**
   * The route or task the work is for.
   *
   * @return the transaction, or null
   */
  public synchronized String getTransaction() {
    return transaction;
  }

  /**
   * Sets the HTTP request the work serves.
   *
   * @param request the request
   */
  public synchronized void setRequest(Request request) {
    this.request = request;
  }

  public synchronized Request getRequest() {
    return request;
  }

  /**
   * The span events captured with the scope belong to; spans started with it are its children.
   *
   * @return the span, or null
   */
  public synchronized Span getSpan() {
    return span;
  }

  public synchronized void setSpan(Span span) {
    this.span = span;
  }

  /**
   * Records something that happened; past {@code max}, the oldest go.
   *
   * @param b the breadcrumb
   * @param max the breadcrumbs kept
   */
  public synchronized void addBreadcrumb(Breadcrumb b, int max) {
    if (max <= 0) {
      return;
    }
    if (b.getTimestampMillis() == 0) {
      b.setTimestampMillis(System.currentTimeMillis());
    }
    breadcrumbs.addLast(b);
    while (breadcrumbs.size() > max) {
      breadcrumbs.removeFirst();
    }
  }

  /** Forgets the breadcrumbs. */
  public synchronized void clearBreadcrumbs() {
    breadcrumbs.clear();
  }

  /** Forgets everything. */
  public synchronized void clear() {
    user = null;
    tags.clear();
    contexts.clear();
    extra.clear();
    breadcrumbs.clear();
    level = null;
    fingerprint = new ArrayList<>();
    transaction = null;
    request = null;
    span = null;
  }

  /** Adds what the scope knows to an event; the event's own details win. */
  synchronized void applyTo(Event e) {
    if (e.getUser() == null && user != null) {
      e.setUser(user.copy());
    }
    if (!tags.isEmpty()) {
      Map<String, String> t = new LinkedHashMap<>(tags);
      t.putAll(e.getTags());
      e.setTags(t);
    }
    if (!contexts.isEmpty()) {
      Map<String, Map<String, Object>> c = new LinkedHashMap<>(contexts);
      c.putAll(e.getContexts());
      e.setContexts(c);
    }
    if (!extra.isEmpty()) {
      Map<String, Object> x = new LinkedHashMap<>(extra);
      x.putAll(e.getExtra());
      e.setExtra(x);
    }
    if (e.getBreadcrumbs().isEmpty()) {
      e.setBreadcrumbs(new ArrayList<>(breadcrumbs));
    }
    if (e.getLevel() == null) {
      e.setLevel(level);
    }
    if (e.getFingerprint().isEmpty()) {
      e.setFingerprint(new ArrayList<>(fingerprint));
    }
    if (e.getRequest() == null) {
      e.setRequest(request);
    }
    if (e.getTransaction() == null) {
      e.setTransaction(transaction);
    }
    if (e.getTransaction() == null && e.getRequest() != null && e.getRequest().getRoute() != null) {
      Request r = e.getRequest();
      e.setTransaction((r.getMethod() == null ? "" : r.getMethod() + " ") + r.getRoute());
    }
    if (e.getTransaction() == null && span != null) {
      e.setTransaction(span.segmentName());
    }
    if (e.getTraceId() == null && span != null) {
      e.setTraceId(span.getTraceId());
      e.setSpanId(span.getSpanId());
    }
  }

  /** Marks the request's session errored, or crashed. */
  void markSession(boolean crashed) {
    Sessions.RequestSession rs;
    synchronized (this) {
      rs = session;
    }
    if (rs != null) {
      rs.mark(crashed);
    }
  }
}
