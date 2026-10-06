package io.fixwire;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The error budget at work. The fingerprint is cheap and only drives the budget; the server's
 * grouping is the real one.
 */
final class Budget {
  private static final int MAX_ISSUES = 1024;
  private static final int TOP_FRAMES = 5;

  /**
   * Parts of a message that change between occurrences. An address is tried only where its run of
   * characters starts, and its parts never backtrack: messages take linear time (with \S+@\S+ a few
   * kilobytes of "a@a@…" took seconds).
   */
  private static final Pattern VARIABLE =
      Pattern.compile(
          "\\b0x[0-9a-fA-F]+\\b|\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b|"
              + "\\b[0-9a-fA-F]{16,}\\b|\\d+(?:\\.\\d+)?|(?<![\\w.%+-])[\\w.%+-]++@[\\w-]++\\.[\\w.-]++");

  private static final class Bucket {
    double tokens;
    long updated;
    int suppressed;

    Bucket(double tokens, long now) {
      this.tokens = tokens;
      this.updated = now;
    }

    boolean take(double burst, double perMinute, long now) {
      tokens = Math.min(burst, tokens + (now - updated) / 60_000.0 * perMinute);
      updated = now;
      if (tokens >= 1) {
        tokens--;
        return true;
      }
      return false;
    }
  }

  private final ErrorBudget opts;
  private final Bucket all;
  // Least recently seen first.
  private final LinkedHashMap<String, Bucket> issues =
      new LinkedHashMap<String, Bucket>(64, 0.75f, true) {
        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Bucket> eldest) {
          return size() > MAX_ISSUES;
        }
      };

  Budget(ErrorBudget opts) {
    this.opts = opts;
    this.all = new Bucket(Math.max(opts.getPerMinute(), 1), System.currentTimeMillis());
  }

  /**
   * Whether an event of the issue may be sent: -1 when not, else the occurrences held back since
   * the last one sent.
   */
  synchronized int allow(String issue, long now) {
    if (!opts.isEnabled()) {
      return 0;
    }
    double burst = Math.max(opts.getPerIssueBurst(), 1);
    Bucket b = issues.get(issue);
    if (b == null) {
      b = new Bucket(burst, now);
      issues.put(issue, b);
    }
    if (b.take(burst, Math.max(opts.getPerIssuePerMinute(), 0), now)
        && all.take(Math.max(opts.getPerMinute(), 1), Math.max(opts.getPerMinute(), 1), now)) {
      int held = b.suppressed;
      b.suppressed = 0;
      return held;
    }
    b.suppressed++;
    return -1;
  }

  /** For tests: makes the issue's bucket a minute older. */
  synchronized void age(String issue, long millis) {
    Bucket b = issues.get(issue);
    if (b != null) {
      b.updated -= millis;
    }
  }

  /**
   * The event's fingerprint for the budget: its exception types and top in-app frames (or its
   * message without the parts that vary), and its custom fingerprint.
   */
  static String issueOf(Event e) {
    List<String> parts = new ArrayList<>();
    if (!e.getExceptions().isEmpty()) {
      for (ExceptionValue x : e.getExceptions()) {
        parts.add(String.valueOf(x.getType()));
      }
      // The deepest cause threw: its frames say where.
      ExceptionValue thrower = e.getExceptions().get(e.getExceptions().size() - 1);
      List<Frame> frames =
          thrower.getFrames().isEmpty()
              ? e.getExceptions().get(0).getFrames()
              : thrower.getFrames();
      List<Frame> app = new ArrayList<>();
      for (Frame f : frames) {
        if (f.isInApp()) {
          app.add(f);
        }
      }
      if (app.isEmpty()) {
        app = frames;
      }
      for (Frame f : app.subList(Math.max(0, app.size() - TOP_FRAMES), app.size())) {
        parts.add(f.getModule() + "|" + f.getFunction());
      }
      if (frames.isEmpty()) {
        parts.add(template(e.getExceptions().get(0).getMessage()));
      }
    } else {
      parts.add(template(e.getMessage()));
    }
    if (!e.getFingerprint().isEmpty()) {
      parts.add(String.join("\u001f", e.getFingerprint()));
    }
    return fnv1a(String.join("\u001e", parts));
  }

  /** The read of a message: its first 1,024 characters, without the parts that vary. */
  static final int MESSAGE_CHARS = 1024;

  private static String template(String message) {
    String m = String.valueOf(message);
    if (m.length() > MESSAGE_CHARS) {
      int end = MESSAGE_CHARS;
      if (Character.isHighSurrogate(m.charAt(end - 1))) {
        end--; // not half a character
      }
      m = m.substring(0, end);
    }
    return VARIABLE.matcher(m).replaceAll("<*>");
  }

  private static String fnv1a(String s) {
    long h = 0xcbf29ce484222325L;
    for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
      h ^= b & 0xff;
      h *= 0x100000001b3L;
    }
    return Long.toHexString(h);
  }
}
