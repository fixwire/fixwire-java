package io.fixwire;

import io.fixwire.internal.redact.Redactor;
import java.lang.reflect.Array;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/**
 * The bounds on what is sent, the same in every Fixwire SDK (fixwire-protocol §13): values the app
 * gives bounded in depth, breadth and size, and strings cut on a character boundary after they are
 * redacted.
 */
final class Limits {
  private Limits() {}

  /**
   * A value's containers are sent this many levels deep, with this many items each, and this many
   * in all.
   */
  static final int MAX_DEPTH = 10;

  static final int MAX_BREADTH = 100;
  static final int MAX_OBJECTS = 10_000;

  /**
   * The bytes past a string's cut that redaction still reads, so that a secret the cut goes through
   * (a private key, a JWT) is found and masked whole.
   */
  static final int REDACT_AHEAD = 16 * 1024;

  /** An error or a message is at most this much JSON; a request of spans this much. */
  static final int MAX_RECORD_BYTES = 1024 * 1024;

  static final int MAX_REQUEST_BYTES = 5 * 1024 * 1024;

  /** The spans a request holds. */
  static final int MAX_REQUEST_ITEMS = 100;

  static final String UNREADABLE = "[Unreadable]";

  /**
   * A value the app gave, as it is sent: maps (string keys), lists, strings, numbers and booleans.
   * Containers are at most {@link #MAX_DEPTH} levels deep (one deeper is {@code [Object]} or {@code
   * [Array]}), with their first {@link #MAX_BREADTH} items, and at most {@link #MAX_OBJECTS} of
   * them are walked (the rest are {@code [Object]} or {@code [Array]} too). A container inside
   * itself is {@code [Circular ~]}, one that can't be read (its toString or iterator throws) {@code
   * [Unreadable]}, NaN and the infinities the strings JSON has no numbers for.
   */
  static Object bounded(Object v) {
    return bounded(v, 0, new Walk());
  }

  /**
   * A map of values the app gave (extras, contexts): its first {@link #MAX_BREADTH} entries, each
   * value bounded on its own.
   */
  static Map<String, Object> boundedEach(Map<?, ?> m) {
    Map<String, Object> out = new LinkedHashMap<>();
    try {
      Iterator<? extends Map.Entry<?, ?>> it = m.entrySet().iterator();
      for (int i = 0; i < MAX_BREADTH && it.hasNext(); i++) {
        Map.Entry<?, ?> e = it.next();
        out.put(string(e.getKey()), bounded(e.getValue()));
      }
    } catch (RuntimeException e) {
      // changed while it was read: what was read goes
    }
    return out;
  }

  /** The containers around the one being copied, and how many more may be. */
  private static final class Walk {
    final Set<Object> open = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
    int left = MAX_OBJECTS;
  }

  private static Object bounded(Object v, int depth, Walk w) {
    if (v == null || v instanceof String || v instanceof Boolean) {
      return v;
    }
    if (v instanceof Number) {
      return number((Number) v);
    }
    if (v instanceof Map || v instanceof Collection || v.getClass().isArray()) {
      String marker = v instanceof Map ? "[Object]" : "[Array]";
      if (w.open.contains(v)) {
        return "[Circular ~]";
      }
      if (depth >= MAX_DEPTH || w.left <= 0) {
        return marker;
      }
      w.left--;
      w.open.add(v);
      try {
        return container(v, depth, w);
      } catch (RuntimeException e) {
        return UNREADABLE; // changed while it was read, or an iterator that throws
      } finally {
        w.open.remove(v);
      }
    }
    if (v instanceof Enum) {
      return ((Enum<?>) v).name();
    }
    if (v instanceof Date) {
      SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
      iso.setTimeZone(TimeZone.getTimeZone("UTC"));
      return iso.format((Date) v);
    }
    return string(v); // CharSequence, Instant, UUID, …
  }

  private static Object container(Object v, int depth, Walk w) {
    if (v instanceof Map) {
      Map<String, Object> out = new LinkedHashMap<>();
      Iterator<? extends Map.Entry<?, ?>> it = ((Map<?, ?>) v).entrySet().iterator();
      for (int i = 0; i < MAX_BREADTH && it.hasNext(); i++) {
        Map.Entry<?, ?> e = it.next();
        out.put(string(e.getKey()), bounded(e.getValue(), depth + 1, w));
      }
      return out;
    }
    List<Object> out = new ArrayList<>();
    if (v instanceof Collection) {
      Iterator<?> it = ((Collection<?>) v).iterator();
      for (int i = 0; i < MAX_BREADTH && it.hasNext(); i++) {
        out.add(bounded(it.next(), depth + 1, w));
      }
    } else {
      int n = Math.min(Array.getLength(v), MAX_BREADTH);
      for (int i = 0; i < n; i++) {
        out.add(bounded(Array.get(v, i), depth + 1, w));
      }
    }
    return out;
  }

  /**
   * A number, NaN and the infinities as {@code "NaN"}, {@code "Infinity"} and {@code "-Infinity"}.
   */
  static Object number(Number n) {
    if (n instanceof Double || n instanceof Float) {
      double d = n.doubleValue();
      if (Double.isNaN(d)) {
        return "NaN";
      }
      if (Double.isInfinite(d)) {
        return d > 0 ? "Infinity" : "-Infinity";
      }
    }
    return n;
  }

  /**
   * An object's string. One whose toString throws, or overflows the stack (entities that print each
   * other), is {@code [Unreadable]} instead of failing the capture.
   */
  static String string(Object v) {
    try {
      return String.valueOf(v);
    } catch (RuntimeException | LinkageError | StackOverflowError e) {
      return UNREADABLE;
    }
  }

  /**
   * The bytes of a string's UTF-8, as {@link io.fixwire.internal.Json} writes it (a lone surrogate
   * as U+FFFD), counted without encoding it.
   */
  static long utf8Length(CharSequence s) {
    long n = 0;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c < 0x80) {
        n++;
      } else if (c < 0x800) {
        n += 2;
      } else if (Character.isHighSurrogate(c)
          && i + 1 < s.length()
          && Character.isLowSurrogate(s.charAt(i + 1))) {
        n += 4;
        i++;
      } else {
        n += 3;
      }
    }
    return n;
  }

  /**
   * s in at most {@code max} bytes of UTF-8: a longer one is cut where a character ends (never
   * inside a surrogate pair) and ends in {@code ...}, within the limit. Only the part kept is read.
   */
  static String cut(String s, int max) {
    if (s == null || s.length() <= max / 3) {
      return s; // three bytes a char at most: it fits
    }
    int keep = Math.max(max - 3, 0);
    int kept = -1; // where the part kept ends, once a character goes past it
    long bytes = 0;
    for (int i = 0; i < s.length(); ) {
      char c = s.charAt(i);
      int chars = 1;
      int n;
      if (c < 0x80) {
        n = 1;
      } else if (c < 0x800) {
        n = 2;
      } else if (Character.isHighSurrogate(c)
          && i + 1 < s.length()
          && Character.isLowSurrogate(s.charAt(i + 1))) {
        n = 4;
        chars = 2;
      } else {
        n = 3;
      }
      if (kept < 0 && bytes + n > keep) {
        kept = i;
      }
      bytes += n;
      if (bytes > max) {
        return s.substring(0, kept) + "...".substring(0, Math.min(3, max));
      }
      i += chars;
    }
    return s;
  }

  /**
   * Every string in a plain value (maps with string keys, lists), keys included, cut to {@code max}
   * bytes. Maps and lists are changed in place; keys alike once cut keep the first one's value.
   */
  static Object cutAll(Object v, int max) {
    if (v instanceof String) {
      return cut((String) v, max);
    }
    if (v instanceof Map) {
      @SuppressWarnings("unchecked")
      Map<String, Object> m = (Map<String, Object>) v;
      boolean longKey = false;
      for (Map.Entry<String, Object> e : m.entrySet()) {
        longKey |= e.getKey() != null && cut(e.getKey(), max) != e.getKey();
        Object c = cutAll(e.getValue(), max);
        if (c != e.getValue()) {
          e.setValue(c);
        }
      }
      if (longKey) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : m.entrySet()) {
          String k = cut(e.getKey(), max);
          if (!out.containsKey(k)) {
            out.put(k, e.getValue());
          }
        }
        m.clear();
        m.putAll(out);
      }
      return m;
    }
    if (v instanceof List) {
      @SuppressWarnings("unchecked")
      List<Object> l = (List<Object>) v;
      for (int i = 0; i < l.size(); i++) {
        Object c = cutAll(l.get(i), max);
        if (c != l.get(i)) {
          l.set(i, c);
        }
      }
    }
    return v;
  }

  /**
   * A string as it is sent: masked, then cut to {@code max} bytes. Redaction reads the part kept
   * and the {@link #REDACT_AHEAD} bytes after it, so a secret the cut goes through is masked; a
   * string it fails on is {@code [Filtered]}.
   */
  static String text(String s, Redactor redactor, int max) {
    if (s == null || s.isEmpty()) {
      return s;
    }
    return cut(mask(cut(s, max + REDACT_AHEAD), redactor), max);
  }

  /** s masked; {@code [Filtered]}, never s, when redaction fails on it. */
  static String mask(String s, Redactor redactor) {
    if (redactor == null || s == null || s.isEmpty()) {
      return s;
    }
    try {
      return redactor.mask(s).text;
    } catch (RuntimeException | StackOverflowError e) {
      return Redactor.FILTERED;
    }
  }

  /**
   * A plain map (the attributes of a record, a JSON body) as it is sent: every string, keys
   * included, cut to what redaction reads, masked, then cut to {@code max} bytes. Where redaction
   * fails on a value, the value is {@code [Filtered]}.
   */
  static Map<String, Object> finish(Map<String, Object> m, Redactor redactor, int max) {
    cutAll(m, max + REDACT_AHEAD);
    Map<String, Object> out = scrub(m, redactor);
    cutAll(out, max);
    return out;
  }

  /** The map with its secrets masked and its sensitive values filtered (changed in place). */
  static Map<String, Object> scrub(Map<String, Object> m, Redactor redactor) {
    if (redactor == null) {
      return m;
    }
    try {
      return asMap(redactor.walk(m, new int[1]));
    } catch (RuntimeException | StackOverflowError e) {
      // One entry at a time: what fails again is filtered, never sent unmasked.
      Map<String, Object> out = new LinkedHashMap<>();
      for (Map.Entry<String, Object> x : m.entrySet()) {
        Map<String, Object> one = new LinkedHashMap<>();
        one.put(x.getKey(), x.getValue());
        try {
          out.putAll(asMap(redactor.walk(one, new int[1])));
        } catch (RuntimeException | StackOverflowError again) {
          out.put(mask(x.getKey(), redactor), Redactor.FILTERED);
        }
      }
      return out;
    }
  }

  private static Map<String, Object> asMap(Object v) {
    if (v instanceof Map) {
      @SuppressWarnings("unchecked")
      Map<String, Object> m = (Map<String, Object>) v;
      return m;
    }
    return new LinkedHashMap<>();
  }
}
