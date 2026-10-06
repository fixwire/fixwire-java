package io.fixwire.internal.redact;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.TreeMap;

/**
 * Masks secrets and personal data on the device before anything is sent, with the same output as
 * the Fixwire server's redaction (proven by the shared corpus pkg/redact/testdata/vectors.json).
 *
 * <p>Detectors run in a fixed order; a cheap prefilter skips each one on text that cannot match,
 * and validators (Luhn, mod-97, checksums) reject look-alikes so trace ids, hashes and timestamps
 * survive. Safe for concurrent use. Not part of the SDK's API.
 */
public final class Redactor {
  /** Replaces the value of a sensitive key. */
  public static final String FILTERED = "[Filtered]";

  /**
   * The detectors on by default, in the server's order: all but ipv4 (in error messages IP
   * addresses are usually servers worth seeing).
   */
  public static final List<String> DEFAULT_DETECTORS;

  /** Key fragments whose values are always filtered whole. */
  public static final List<String> DEFAULT_SENSITIVE_KEYS =
      Collections.unmodifiableList(
          Arrays.asList(
              "password",
              "passwd",
              "pwd",
              "secret",
              "apikey",
              "accesskey",
              "token",
              "credential",
              "privatekey",
              "authorization",
              "cookie",
              "sessionid",
              "csrf",
              "xsrf",
              "cvv",
              "cvc",
              "ssn",
              "creditcard",
              "cardnumber"));

  static {
    List<String> names = new ArrayList<>();
    for (Detector d : Detectors.REGISTRY) {
      if (!d.name.equals(Detectors.IPV4)) {
        names.add(d.name);
      }
    }
    DEFAULT_DETECTORS = Collections.unmodifiableList(names);
  }

  private static final Redactor DEFAULT = new Redactor(DEFAULT_DETECTORS, null);

  /** Json writes nothing deeper than this, so walk goes no deeper either. */
  private static final int MAX_DEPTH = 64;

  private final Detector[] detectors;
  private final String[] keys;

  /** A redactor with the named detectors; sensitiveKeys null means the defaults. */
  Redactor(List<String> detectorNames, List<String> sensitiveKeys) {
    List<Detector> ds = new ArrayList<>();
    for (String name : detectorNames) {
      Detector found = null;
      for (Detector d : Detectors.REGISTRY) {
        if (d.name.equals(name)) {
          found = d;
          break;
        }
      }
      if (found == null) {
        throw new IllegalArgumentException("redact: unknown detector \"" + name + "\"");
      }
      ds.add(found);
    }
    detectors = ds.toArray(new Detector[0]);
    List<String> ks = new ArrayList<>();
    if (sensitiveKeys == null) {
      ks.addAll(DEFAULT_SENSITIVE_KEYS);
    } else {
      for (String k : sensitiveKeys) {
        if (k != null) {
          ks.add(normalizeKey(k));
        }
      }
    }
    keys = ks.toArray(new String[0]);
  }

  /**
   * A redactor with the default detectors. Its sensitive keys replace the defaults, compared like
   * the server does (lower case, without "-", "_" and spaces); null keeps the defaults.
   *
   * @param sensitiveKeys key fragments whose values are filtered whole, or null
   * @return the redactor
   */
  public static Redactor create(List<String> sensitiveKeys) {
    return sensitiveKeys == null ? DEFAULT : new Redactor(DEFAULT_DETECTORS, sensitiveKeys);
  }

  /**
   * The redactor with the default detectors and sensitive keys.
   *
   * @return the shared redactor
   */
  public static Redactor defaults() {
    return DEFAULT;
  }

  /** One match: the detector and the span it covers. */
  static final class Finding {
    final String detector;
    final int start;
    final int end;

    Finding(String detector, int start, int end) {
      this.detector = detector;
      this.start = start;
      this.end = end;
    }
  }

  /**
   * The non-overlapping findings in s, leftmost first; when two overlap, the earlier detector wins.
   */
  List<Finding> find(String s) {
    if (s.isEmpty()) {
      return Collections.emptyList();
    }
    Detector.Text t = new Detector.Text(s);
    TreeMap<Integer, Finding> out = null; // by start
    for (Detector d : detectors) {
      if (!d.mayMatch(t)) {
        continue;
      }
      for (int[] span : d.spans(t)) {
        int start = span[0];
        int end = span[1];
        if (d.validate != null && !d.validate.test(s.substring(start, end))) {
          continue;
        }
        if (out == null) {
          out = new TreeMap<>();
        } else if (overlaps(out, start, end)) {
          continue;
        }
        out.put(start, new Finding(d.name, start, end));
      }
    }
    if (out == null) {
      return Collections.emptyList();
    }
    return new ArrayList<>(out.values());
  }

  /**
   * Whether a span overlaps a finding kept. Those don't overlap each other, so the last one to
   * start before the span ends also ends last: one lookup, where checking each finding made text
   * with many findings quadratic.
   */
  private static boolean overlaps(TreeMap<Integer, Finding> fs, int start, int end) {
    Map.Entry<Integer, Finding> before = fs.lowerEntry(end);
    return before != null && start < before.getValue().end;
  }

  /** s with each finding replaced by [REDACTED:detector]. */
  private static String replace(String s, List<Finding> fs) {
    StringBuilder b = new StringBuilder(s.length() + 24 * fs.size());
    int last = 0;
    for (Finding f : fs) {
      b.append(s, last, f.start).append("[REDACTED:").append(f.detector).append(']');
      last = f.end;
    }
    return b.append(s, last, s.length()).toString();
  }

  /**
   * Masks the findings in s: each becomes {@code [REDACTED:<detector>]}, as the server writes it.
   *
   * @param s the text; null gives null text and no findings
   * @return the masked text and the detector of each finding
   */
  public Masked mask(String s) {
    List<Finding> fs = s == null ? Collections.<Finding>emptyList() : find(s);
    if (fs.isEmpty()) {
      return new Masked(s, Collections.<String>emptyList());
    }
    List<String> names = new ArrayList<>(fs.size());
    for (Finding f : fs) {
      names.add(f.detector);
    }
    return new Masked(replace(s, fs), Collections.unmodifiableList(names));
  }

  /** A string with its findings masked. */
  public static final class Masked {
    /** The text with each finding replaced by {@code [REDACTED:<detector>]}. */
    public final String text;

    /** The detector of each finding, in the order they appear. */
    public final List<String> findings;

    Masked(String text, List<String> findings) {
      this.text = text;
      this.findings = findings;
    }
  }

  static String normalizeKey(String k) {
    String l = Detectors.lowerCase(k);
    StringBuilder b = null;
    for (int i = 0; i < l.length(); i++) {
      char c = l.charAt(i);
      if (c == '-' || c == '_' || c == ' ') {
        if (b == null) {
          b = new StringBuilder(l.length()).append(l, 0, i);
        }
      } else if (b != null) {
        b.append(c);
      }
    }
    return b == null ? l : b.toString();
  }

  /** Whether a key's value must be filtered whole. */
  boolean sensitive(String key) {
    String k = normalizeKey(key);
    if (k.equals("auth")) {
      return true;
    }
    for (String frag : keys) {
      if (k.contains(frag) && (!frag.equals("token") || !tokenCount(k))) {
        return true;
      }
    }
    return false;
  }

  /**
   * Keys that count model tokens rather than hold one: gen_ai.usage.input_tokens, max_tokens,
   * token_count.
   */
  private static boolean tokenCount(String k) {
    return k.endsWith("tokens") || k.contains("tokencount") || k.contains("usage");
  }

  /**
   * Masks every string in a JSON-like value and filters the values of sensitive keys, by the
   * server's rules: a typed attribute ({@code {"type": …, "value": …}}) keeps its shape, a list of
   * two holding a sensitive key and a value is a pair, keys that count tokens are not secrets, and
   * keys that hold data are masked too (keys that mask alike are numbered in key order: {@code
   * "[REDACTED:email] (2)"}).
   *
   * <p>Maps (string keys) and lists are changed in place and must be mutable where something is
   * masked. Numbers, booleans and null stay as they are. Anything else is masked as the text Json
   * would write for it: other collections and object arrays become lists, other objects their
   * string, only when something in them is masked. Values nested deeper than Json writes are left
   * alone.
   *
   * @param v the value
   * @param count {@code count[0]} grows by the number of values masked
   * @return the masked value: v itself, unless v had to be replaced
   */
  public Object walk(Object v, int[] count) {
    return walk(v, count, 0);
  }

  private Object walk(Object v, int[] n, int depth) {
    if (v == null || v instanceof Number || v instanceof Boolean || depth > MAX_DEPTH) {
      return v;
    }
    if (v instanceof Map) {
      return walkMap(asMap(v), n, depth);
    }
    if (v instanceof List) {
      return walkList(asList(v), n, depth);
    }
    if (v instanceof Collection || v instanceof Object[]) {
      List<Object> copy =
          v instanceof Collection
              ? new ArrayList<Object>((Collection<?>) v)
              : new ArrayList<Object>(Arrays.asList((Object[]) v));
      int before = n[0];
      walkList(copy, n, depth);
      return n[0] != before ? copy : v;
    }
    if (v.getClass().isArray()) {
      return v; // numbers, booleans or single characters
    }
    String s = v instanceof String ? (String) v : String.valueOf(v);
    List<Finding> fs = find(s);
    if (fs.isEmpty()) {
      return v;
    }
    n[0] += fs.size();
    return replace(s, fs);
  }

  private Map<Object, Object> walkMap(Map<Object, Object> map, int[] n, int depth) {
    List<Rename> renamed = null;
    for (Map.Entry<Object, Object> e : map.entrySet()) {
      String key = e.getKey() instanceof String ? (String) e.getKey() : null;
      Object val = e.getValue();
      if (key != null) {
        List<Finding> fs = find(key);
        if (!fs.isEmpty()) {
          if (renamed == null) {
            renamed = new ArrayList<>();
          }
          renamed.add(new Rename(key, replace(key, fs), fs.size()));
        }
        if (sensitive(key) && !empty(val)) {
          // A typed attribute ({"type": …, "value": …}) keeps its shape.
          Map<Object, Object> typed = typed(val);
          if (typed != null) {
            if (!filtered(typed.get("value"))) {
              typed.put("value", FILTERED);
              typed.put("type", "string");
              n[0]++;
            }
          } else if (!filtered(val)) {
            e.setValue(FILTERED);
            n[0]++;
          }
          continue;
        }
      }
      Object w = walk(val, n, depth + 1);
      if (w != val) {
        e.setValue(w);
      }
    }
    if (renamed != null) {
      // Keys hold data too ({"ada@example.com": 3}). Keys that mask alike
      // are numbered in key order: "[REDACTED:email] (2)". Each goes on from
      // the number the one before it got (those below are taken), so many
      // keys that mask alike don't each count up from 2.
      Collections.sort(renamed);
      Map<String, Integer> last = new HashMap<>();
      for (Rename r : renamed) {
        Integer from = last.get(r.masked);
        int i = from == null ? 1 : from;
        String key = i == 1 ? r.masked : r.masked + " (" + i + ")";
        while (map.containsKey(key)) {
          key = r.masked + " (" + ++i + ")";
        }
        last.put(r.masked, i);
        map.put(key, map.get(r.key));
        map.remove(r.key);
        n[0] += r.count;
      }
    }
    return map;
  }

  private List<Object> walkList(List<Object> list, int[] n, int depth) {
    // Some maps are sent as [key, value] pairs (headers, tags).
    if (list.size() == 2
        && list.get(0) instanceof String
        && sensitive((String) list.get(0))
        && !empty(list.get(1))) {
      list.set(1, FILTERED);
      n[0]++;
      return list;
    }
    for (ListIterator<Object> it = list.listIterator(); it.hasNext(); ) {
      Object item = it.next();
      Object w = walk(item, n, depth + 1);
      if (w != item) {
        it.set(w);
      }
    }
    return list;
  }

  /** A key that holds data, with its masked form. */
  private static final class Rename implements Comparable<Rename> {
    final String key;
    final String masked;
    final int count;

    Rename(String key, String masked, int count) {
      this.key = key;
      this.masked = masked;
      this.count = count;
    }

    /** By code point, as the server sorts (UTF-16 order differs past U+FFFF). */
    @Override
    public int compareTo(Rename o) {
      String a = key;
      String b = o.key;
      int n = Math.min(a.length(), b.length());
      for (int i = 0; i < n; i++) {
        int x = a.charAt(i);
        int y = b.charAt(i);
        if (x != y) {
          if (x >= 0xD800 && y >= 0xD800) {
            // Surrogates go above U+E000..U+FFFF.
            x = x >= 0xE000 ? x - 0x800 : x + 0x2000;
            y = y >= 0xE000 ? y - 0x800 : y + 0x2000;
          }
          return x - y;
        }
      }
      return a.length() - b.length();
    }
  }

  private static boolean empty(Object v) {
    return v == null || v instanceof CharSequence && ((CharSequence) v).length() == 0;
  }

  private static boolean filtered(Object v) {
    return v instanceof CharSequence && FILTERED.contentEquals((CharSequence) v);
  }

  /** The map of a typed attribute (a non-null "value"), or null. */
  private static Map<Object, Object> typed(Object v) {
    if (!(v instanceof Map)) {
      return null;
    }
    Map<Object, Object> m = asMap(v);
    try {
      return m.get("value") != null ? m : null;
    } catch (ClassCastException e) {
      return null; // a sorted map of other keys
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<Object, Object> asMap(Object v) {
    return (Map<Object, Object>) v;
  }

  @SuppressWarnings("unchecked")
  private static List<Object> asList(Object v) {
    return (List<Object>) v;
  }
}
