package io.fixwire.internal;

import java.lang.reflect.Array;
import java.util.Collection;
import java.util.Map;

/**
 * Writes JSON: maps (string keys), collections and arrays, strings, numbers, booleans and null.
 * Other values are written as their string. Not part of the SDK's API.
 */
public final class Json {
  private Json() {}

  /** The JSON text of a value. */
  public static String write(Object value) {
    StringBuilder b = new StringBuilder(256);
    write(b, value, 0);
    return b.toString();
  }

  private static final int MAX_DEPTH = 64;

  private static void write(StringBuilder b, Object v, int depth) {
    if (depth > MAX_DEPTH) {
      b.append("null");
    } else if (v == null) {
      b.append("null");
    } else if (v instanceof CharSequence) {
      string(b, v.toString());
    } else if (v instanceof Boolean) {
      b.append(((Boolean) v).booleanValue());
    } else if (v instanceof Double || v instanceof Float) {
      double d = ((Number) v).doubleValue();
      if (Double.isNaN(d) || Double.isInfinite(d)) {
        b.append("null");
      } else if (d == Math.rint(d) && Math.abs(d) < 1e15) {
        b.append((long) d);
      } else {
        b.append(d);
      }
    } else if (v instanceof Number) {
      b.append(v.toString());
    } else if (v instanceof Map) {
      b.append('{');
      boolean first = true;
      for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
        if (!first) {
          b.append(',');
        }
        first = false;
        string(b, String.valueOf(e.getKey()));
        b.append(':');
        write(b, e.getValue(), depth + 1);
      }
      b.append('}');
    } else if (v instanceof Collection) {
      b.append('[');
      boolean first = true;
      for (Object e : (Collection<?>) v) {
        if (!first) {
          b.append(',');
        }
        first = false;
        write(b, e, depth + 1);
      }
      b.append(']');
    } else if (v.getClass().isArray()) {
      b.append('[');
      int n = Array.getLength(v);
      for (int i = 0; i < n; i++) {
        if (i > 0) {
          b.append(',');
        }
        write(b, Array.get(v, i), depth + 1);
      }
      b.append(']');
    } else {
      string(b, String.valueOf(v));
    }
  }

  private static final char[] HEX = "0123456789abcdef".toCharArray();

  private static void string(StringBuilder b, String s) {
    b.append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"':
          b.append("\\\"");
          break;
        case '\\':
          b.append("\\\\");
          break;
        case '\n':
          b.append("\\n");
          break;
        case '\r':
          b.append("\\r");
          break;
        case '\t':
          b.append("\\t");
          break;
        default:
          if (c < 0x20) {
            b.append("\\u00").append(HEX[(c >> 4) & 0xf]).append(HEX[c & 0xf]);
          } else if (c == 0x2028 || c == 0x2029) {
            b.append(c == 0x2028 ? "\\u2028" : "\\u2029");
          } else if (Character.isSurrogate(c)
              && !(Character.isHighSurrogate(c)
                  && i + 1 < s.length()
                  && Character.isLowSurrogate(s.charAt(i + 1)))
              && !(Character.isLowSurrogate(c)
                  && i > 0
                  && Character.isHighSurrogate(s.charAt(i - 1)))) {
            b.append('�'); // a lone surrogate is not valid UTF-8
          } else {
            b.append(c);
          }
      }
    }
    b.append('"');
  }
}
