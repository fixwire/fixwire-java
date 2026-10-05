package io.fixwire.internal.redact;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds one kind of sensitive value. A cheap literal prefilter skips the pattern on text that
 * cannot match, and a validator rejects look-alikes (Luhn, mod-97, checksums), so trace ids, hashes
 * and timestamps survive.
 */
final class Detector {
  /** A hand-written scanner returning {start, end} spans. */
  interface Scan {
    List<int[]> spans(Text t);
  }

  final String name;

  /**
   * Substrings one of which must appear (any case, unless caseSensitive); empty means always run.
   */
  private final String[] prefilter;

  private final boolean caseSensitive;

  /** A cheaper prefilter than literals, when there are none; null means always run. */
  private final Predicate<String> may;

  private final Pattern pattern;

  /** The group to mask; 0 is the whole match. */
  private final int group;

  /** Rejects look-alikes; null accepts every span. */
  final Predicate<String> validate;

  /** Replaces the pattern when set. */
  private final Scan scan;

  private Detector(
      String name,
      String[] prefilter,
      boolean caseSensitive,
      Predicate<String> may,
      Pattern pattern,
      int group,
      Predicate<String> validate,
      Scan scan) {
    this.name = name;
    this.prefilter = prefilter;
    this.caseSensitive = caseSensitive;
    this.may = may;
    this.pattern = pattern;
    this.group = group;
    this.validate = validate;
    this.scan = scan;
  }

  /** A detector built on a regular expression. */
  static Detector pattern(
      String name,
      boolean caseSensitive,
      String[] prefilter,
      String regex,
      int group,
      Predicate<String> validate) {
    return new Detector(
        name, prefilter, caseSensitive, null, Pattern.compile(regex), group, validate, null);
  }

  /** A detector built on a scanner. */
  static Detector scanner(
      String name,
      boolean caseSensitive,
      String[] prefilter,
      Scan scan,
      Predicate<String> validate) {
    return new Detector(name, prefilter, caseSensitive, null, null, 0, validate, scan);
  }

  /** The same detector behind a cheaper check than literals. */
  Detector onlyIf(Predicate<String> may) {
    return new Detector(name, prefilter, caseSensitive, may, pattern, group, validate, scan);
  }

  /** Whether the prefilters let t through to the pattern or scanner. */
  boolean mayMatch(Text t) {
    if (prefilter.length > 0) {
      String hay = caseSensitive ? t.s : t.lower();
      boolean hit = false;
      for (String p : prefilter) {
        if (hay.contains(p)) {
          hit = true;
          break;
        }
      }
      if (!hit) {
        return false;
      }
    }
    return may == null || may.test(t.s);
  }

  /** The candidate spans, from the scanner or the pattern (the configured group). */
  List<int[]> spans(Text t) {
    if (scan != null) {
      return scan.spans(t);
    }
    List<int[]> out = new ArrayList<>();
    Matcher m = pattern.matcher(t.s);
    while (m.find()) {
      if (group > 0 && m.start(group) >= 0) {
        out.add(new int[] {m.start(group), m.end(group)});
      } else {
        out.add(new int[] {m.start(), m.end()});
      }
    }
    return out;
  }

  /** One string being searched, with what several detectors share. */
  static final class Text {
    final String s;
    private String lower;
    private List<Detectors.NumberRun> numbers;

    Text(String s) {
      this.s = s;
    }

    /** The string in lower case, for the prefilters. */
    String lower() {
      if (lower == null) {
        lower = Detectors.lowerCase(s);
      }
      return lower;
    }

    /** The standalone runs of digits, for the card, SSN and TCKN detectors. */
    List<Detectors.NumberRun> numbers() {
      if (numbers == null) {
        numbers = Detectors.numberRuns(s);
      }
      return numbers;
    }
  }
}
