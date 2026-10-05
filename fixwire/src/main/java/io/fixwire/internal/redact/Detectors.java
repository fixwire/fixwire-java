package io.fixwire.internal.redact;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The detectors of the Fixwire server's redaction, in its order, with the same patterns,
 * prefilters, validators and scanners.
 *
 * <p>Patterns are ASCII-only like the server's. Its word boundary is spelled out as lookarounds on
 * ASCII word characters (Java's own boundary treats other scripts as letters before Java 19),
 * digits are [0-9] and whitespace is [\t\n\f\r ] (Java's whitespace class also has \v). Where the
 * server ignores case, its "k" also matches the Kelvin sign and its "s" the long s, so those two
 * are listed by hand next to Java's ASCII-only case folding. Possessive quantifiers stand where the
 * next token cannot match what they took: the same matches without backtracking.
 *
 * <p>Two of the server's patterns are scanners here (private keys, URL credentials): they find the
 * same matches, but a backtracking engine would take quadratic time on some text (seconds on 100
 * KB).
 */
final class Detectors {
  private Detectors() {}

  private static final String WORD = "[0-9A-Za-z_]";

  /** The server's word boundary before a word character. */
  private static final String START = "(?<!" + WORD + ")";

  /** The server's word boundary after a word character. */
  private static final String END = "(?!" + WORD + ")";

  /** The server's word boundary where either side may be a word character. */
  private static final String EDGE =
      "(?:(?<=" + WORD + ")(?!" + WORD + ")|(?<!" + WORD + ")(?=" + WORD + "))";

  private static final String WS = "[\\t\\n\\f\\r ]";

  /** Case-insensitive "s" and "k" as the server folds them. */
  private static final String S = "[s\\u017F]";

  private static final String K = "[k\\u212A]";

  /** The letters the server's case-insensitive classes add to [A-Za-z]. */
  private static final String FOLDED = "\\u017F\\u212A";

  /** Whether a prefilter literal must appear in the same case. */
  private static final boolean EXACT_CASE = true;

  private static final boolean ANY_CASE = false;

  static final String IPV4 = "ipv4";

  /** Every detector, in the server's order. */
  static final List<Detector> REGISTRY =
      Collections.unmodifiableList(
          Arrays.asList(
              Detector.scanner(
                  "private_key",
                  EXACT_CASE,
                  literals("PRIVATE KEY-----"),
                  Detectors::privateKeySpans,
                  null),
              Detector.pattern(
                  "aws_access_key",
                  EXACT_CASE,
                  literals("AKIA", "ASIA", "ABIA", "ACCA"),
                  START + "(?:AKIA|ASIA|ABIA|ACCA)[0-9A-Z]{16}" + END,
                  0,
                  null),
              Detector.pattern(
                  "gcp_api_key",
                  EXACT_CASE,
                  literals("AIza"),
                  START + "AIza[0-9A-Za-z_\\-]{35}",
                  0,
                  null),
              Detector.pattern(
                  "azure_storage_key",
                  ANY_CASE,
                  literals("accountkey="),
                  "(?i)Account" + K + "ey=([A-Za-z0-9+/" + FOLDED + "]{86}==)",
                  1,
                  null),
              Detector.pattern(
                  "github_token",
                  EXACT_CASE,
                  literals("ghp_", "gho_", "ghu_", "ghs_", "ghr_", "github_pat_"),
                  START
                      + "(?:gh[pousr]_[A-Za-z0-9]{36,255}+|github_pat_[A-Za-z0-9_]{60,255}+)"
                      + END,
                  0,
                  null),
              Detector.pattern(
                  "stripe_key",
                  EXACT_CASE,
                  literals("sk_live_", "sk_test_", "rk_live_", "rk_test_", "whsec_"),
                  START
                      + "(?:(?:sk|rk)_(?:live|test)_[0-9A-Za-z]{16,247}|whsec_[A-Za-z0-9+/=]{24,})",
                  0,
                  null),
              Detector.pattern(
                  "slack_token",
                  EXACT_CASE,
                  literals("xox"),
                  START + "xox[abposr]-[0-9A-Za-z-]{10,250}" + EDGE,
                  0,
                  null),
              Detector.pattern(
                  "slack_webhook",
                  EXACT_CASE,
                  literals("hooks.slack.com/services/"),
                  "https://hooks\\.slack\\.com/services/T[A-Z0-9]++/B[A-Z0-9]++/[A-Za-z0-9]+",
                  0,
                  null),
              Detector.pattern(
                  "anthropic_key",
                  EXACT_CASE,
                  literals("sk-ant-"),
                  START + "sk-ant-(?:api|admin)[0-9]{2}-[A-Za-z0-9_\\-]{80,}",
                  0,
                  null),
              Detector.pattern(
                  "openai_key",
                  EXACT_CASE,
                  literals("sk-"),
                  START
                      + "sk-(?:(?:proj|svcacct|admin)-[A-Za-z0-9_\\-]{40,}"
                      + "|[A-Za-z0-9]{20}T3BlbkFJ[A-Za-z0-9]{20})",
                  0,
                  null),
              Detector.pattern(
                  "jwt",
                  EXACT_CASE,
                  literals("eyJ"),
                  START + "eyJ[A-Za-z0-9_-]{8,}+\\.eyJ[A-Za-z0-9_-]{8,}+\\.[A-Za-z0-9_-]{8,}",
                  0,
                  null),
              Detector.pattern(
                  "fixwire_secret_key",
                  EXACT_CASE,
                  literals("_sk_live_", "_sk_test_"),
                  START + "[a-z]{2,4}_sk_(?:live|test)_[0-9A-Za-z]{38}" + END,
                  0,
                  null),
              // The password in scheme://user:password@host (the user stays).
              Detector.scanner(
                  "url_credentials",
                  EXACT_CASE,
                  literals("://"),
                  Detectors::urlCredentialSpans,
                  Detectors::unmasked),
              // Bearer and Basic credentials outside a header (messages, breadcrumbs).
              Detector.pattern(
                  "http_auth",
                  ANY_CASE,
                  literals("bearer", "basic"),
                  "(?i)"
                      + START
                      + "(?:bearer|ba"
                      + S
                      + "ic)"
                      + WS
                      + "++([A-Za-z0-9._~+/\\-"
                      + FOLDED
                      + "]{12,}+=*)",
                  1,
                  Detectors::credentialLike),
              Detector.pattern(
                  "secret_assignment",
                  ANY_CASE,
                  literals("pass", "secret", "token", "api_key", "apikey", "api-key", "pwd"),
                  "(?i)"
                      + EDGE
                      + "(?:pa"
                      + S
                      + S
                      + "word|pa"
                      + S
                      + S
                      + "wd|pwd|"
                      + S
                      + "ecret|to"
                      + K
                      + "en"
                      + "|api[_-]?"
                      + K
                      + "ey|acce"
                      + S
                      + S
                      + "[_-]?"
                      + K
                      + "ey)"
                      + "[\"']?+"
                      + WS
                      + "*+[:=]"
                      + WS
                      + "*+[\"']?+([^\\t\\n\\f\\r \"',;&]{6,})",
                  1,
                  Detectors::unmasked),
              Detector.scanner("email", EXACT_CASE, literals("@"), Detectors::emailSpans, null),
              Detector.scanner("credit_card", ANY_CASE, literals(), Detectors::cardSpans, null),
              Detector.pattern(
                      "iban",
                      ANY_CASE,
                      literals(),
                      START + "[A-Z]{2}[0-9]{2}(?: ?[A-Z0-9]{4}){2,7}(?: ?[A-Z0-9]{1,3})?" + END,
                      0,
                      Detectors::validIban)
                  .onlyIf(Detectors::mayHoldIban),
              Detector.scanner("us_ssn", EXACT_CASE, literals("-"), Detectors::ssnSpans, null),
              Detector.scanner("tr_tckn", ANY_CASE, literals(), Detectors::tcknSpans, null),
              Detector.pattern(
                  "phone",
                  EXACT_CASE,
                  literals("+"),
                  "\\+[0-9](?:[ .\\-()]?[0-9]){7,14}" + END,
                  0,
                  Detectors::validPhone),
              Detector.pattern(
                  IPV4,
                  EXACT_CASE,
                  literals("."),
                  START
                      + "(?:(?:25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])\\.){3}"
                      + "(?:25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])"
                      + END,
                  0,
                  null)));

  private static String[] literals(String... s) {
    return s;
  }

  /**
   * The string in lower case one code point at a time, like the server: U+0130 becomes "i" and the
   * Kelvin sign "k", nothing grows.
   */
  static String lowerCase(String s) {
    for (int i = 0; i < s.length(); ) {
      int c = s.codePointAt(i);
      if (Character.toLowerCase(c) != c) {
        StringBuilder b = new StringBuilder(s.length()).append(s, 0, i);
        while (i < s.length()) {
          c = s.codePointAt(i);
          b.appendCodePoint(Character.toLowerCase(c));
          i += Character.charCount(c);
        }
        return b.toString();
      }
      i += Character.charCount(c);
    }
    return s;
  }

  // Validators.

  /** Rejects values a scrubber already replaced. */
  static boolean unmasked(String v) {
    return !v.startsWith("[REDACTED") && !v.equals(Redactor.FILTERED);
  }

  /**
   * Tells a token from a word after "basic": it has a digit, a base64 symbol, or capitals past its
   * first letter ("dXNlcjpwYXNz", but not "Authentication").
   */
  static boolean credentialLike(String v) {
    boolean upper = false;
    boolean lower = false;
    for (int i = 0; i < v.length(); i++) {
      char c = v.charAt(i);
      if (c >= '0' && c <= '9' || c == '+' || c == '/' || c == '=') {
        return true;
      }
      if (i > 0) {
        upper |= c >= 'A' && c <= 'Z';
        lower |= c >= 'a' && c <= 'z';
      }
    }
    return upper && lower;
  }

  private static final String[] CARD_PREFIXES = {
    "4", "51", "52", "53", "54", "55", "2221", "2720", "34", "37", "6011", "65", "35", "36", "38",
    "300", "305", "62"
  };

  /** Checks the length, a known issuer prefix and the Luhn sum. */
  static boolean validCard(String s) {
    String d = digits(s);
    if (d.length() < 13 || d.length() > 19) {
      return false;
    }
    boolean known = false;
    for (String p : CARD_PREFIXES) {
      if (d.startsWith(p)) {
        known = true;
        break;
      }
    }
    if (!known) {
      return false;
    }
    int sum = 0;
    boolean twice = false;
    for (int i = d.length() - 1; i >= 0; i--) {
      int n = d.charAt(i) - '0';
      if (twice) {
        n *= 2;
        if (n > 9) {
          n -= 9;
        }
      }
      sum += n;
      twice = !twice;
    }
    return sum % 10 == 0;
  }

  /** Checks the length (15 to 34) and the mod-97 checksum. */
  static boolean validIban(String s) {
    s = s.replace(" ", "");
    if (s.length() < 15 || s.length() > 34) {
      return false;
    }
    String rearranged = s.substring(4) + s.substring(0, 4);
    // The remainder of the decimal number the letters spell (A = 10 … Z = 35).
    int rem = 0;
    for (int i = 0; i < rearranged.length(); i++) {
      char c = rearranged.charAt(i);
      if (c >= '0' && c <= '9') {
        rem = (rem * 10 + (c - '0')) % 97;
      } else if (c >= 'A' && c <= 'Z') {
        rem = (rem * 100 + (c - 'A' + 10)) % 97;
      } else {
        return false;
      }
    }
    return rem == 1;
  }

  /** Rejects numbers the US never issues. */
  static boolean validSsn(String s) {
    String area = s.substring(0, 3);
    String group = s.substring(4, 6);
    String serial = s.substring(7, 11);
    return !area.equals("000")
        && !area.equals("666")
        && area.charAt(0) != '9'
        && !group.equals("00")
        && !serial.equals("0000");
  }

  /** Checks the Turkish identity number's two check digits. */
  static boolean validTckn(String s) {
    if (s.length() != 11 || s.charAt(0) == '0') {
      return false;
    }
    int[] d = new int[11];
    for (int i = 0; i < 11; i++) {
      d[i] = s.charAt(i) - '0';
    }
    int odd = d[0] + d[2] + d[4] + d[6] + d[8];
    int even = d[1] + d[3] + d[5] + d[7];
    if (Math.floorMod(odd * 7 - even, 10) != d[9]) {
      return false;
    }
    int sum = 0;
    for (int i = 0; i < 10; i++) {
      sum += d[i];
    }
    return sum % 10 == d[10];
  }

  /** Wants an international number of 8 to 15 digits. */
  static boolean validPhone(String s) {
    int n = digits(s).length();
    return n >= 8 && n <= 15;
  }

  private static String digits(String s) {
    StringBuilder b = new StringBuilder(s.length());
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c >= '0' && c <= '9') {
        b.append(c);
      }
    }
    return b.toString();
  }

  // Hand-written scanners for the detectors whose regular expressions would
  // otherwise try every position of digit-heavy text.

  private static boolean isWord(char c) {
    return c == '_' || c >= '0' && c <= '9' || c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z';
  }

  private static boolean isDigit(char c) {
    return c >= '0' && c <= '9';
  }

  /** A run of digits, optionally split by single spaces or dashes, that stands alone as a word. */
  static final class NumberRun {
    final int start;
    int end;
    int digits;

    /** The separator, 0 when unbroken. */
    char sep;

    /** The number of digit groups and the sizes of the first three. */
    int groups;

    final int[] firstGroups = new int[3];

    NumberRun(int start) {
      this.start = start;
    }

    void addGroup(int size) {
      if (groups < 3) {
        firstGroups[groups] = size;
      }
      groups++;
    }
  }

  static List<NumberRun> numberRuns(String s) {
    List<NumberRun> out = new ArrayList<>();
    int n = s.length();
    for (int i = 0; i < n; ) {
      if (!isDigit(s.charAt(i)) || (i > 0 && isWord(s.charAt(i - 1)))) {
        i++;
        continue;
      }
      NumberRun run = new NumberRun(i);
      int group = 0;
      int j = i;
      while (j < n) {
        char c = s.charAt(j);
        if (isDigit(c)) {
          run.digits++;
          group++;
          j++;
          continue;
        }
        if ((c == ' ' || c == '-')
            && j + 1 < n
            && isDigit(s.charAt(j + 1))
            && (run.sep == 0 || run.sep == c)) {
          run.sep = c;
          run.addGroup(group);
          group = 0;
          j++;
          continue;
        }
        break;
      }
      run.addGroup(group);
      run.end = j;
      if (j == n || !isWord(s.charAt(j))) {
        out.add(run);
      }
      i = j + 1;
    }
    return out;
  }

  static List<int[]> cardSpans(Detector.Text t) {
    List<int[]> out = new ArrayList<>();
    for (NumberRun r : t.numbers()) {
      if (r.digits >= 13 && r.digits <= 19 && validCard(t.s.substring(r.start, r.end))) {
        out.add(new int[] {r.start, r.end});
      }
    }
    return out;
  }

  static List<int[]> ssnSpans(Detector.Text t) {
    List<int[]> out = new ArrayList<>();
    for (NumberRun r : t.numbers()) {
      if (r.sep == '-'
          && r.groups == 3
          && r.firstGroups[0] == 3
          && r.firstGroups[1] == 2
          && r.firstGroups[2] == 4
          && validSsn(t.s.substring(r.start, r.end))) {
        out.add(new int[] {r.start, r.end});
      }
    }
    return out;
  }

  static List<int[]> tcknSpans(Detector.Text t) {
    List<int[]> out = new ArrayList<>();
    for (NumberRun r : t.numbers()) {
      if (r.sep == 0 && r.digits == 11 && validTckn(t.s.substring(r.start, r.end))) {
        out.add(new int[] {r.start, r.end});
      }
    }
    return out;
  }

  private static boolean isLocal(char c) {
    return isWord(c) || c == '.' || c == '%' || c == '+' || c == '-';
  }

  private static boolean isDomain(char c) {
    return isWord(c) && c != '_' || c == '.' || c == '-';
  }

  /**
   * Grows outwards from each "@" over the characters an address may hold, and keeps it if the
   * domain ends in a dotted, alphabetic TLD.
   */
  static List<int[]> emailSpans(Detector.Text t) {
    String s = t.s;
    List<int[]> out = new ArrayList<>();
    for (int i = s.indexOf('@'); i >= 0; i = s.indexOf('@', i + 1)) {
      int start = i;
      int end = i + 1;
      while (start > 0 && isLocal(s.charAt(start - 1))) {
        start--;
      }
      while (end < s.length() && isDomain(s.charAt(end))) {
        end++;
      }
      while (end > i + 1 && (s.charAt(end - 1) == '.' || s.charAt(end - 1) == '-')) {
        end--;
      }
      // The last dot of the domain, past its first character.
      int dot = end - 1;
      while (dot > i + 1 && s.charAt(dot) != '.') {
        dot--;
      }
      if (start < i && dot > i + 1) {
        int tld = end - dot - 1;
        boolean ok = tld >= 2 && tld <= 24;
        for (int k = dot + 1; k < end && ok; k++) {
          char c = s.charAt(k);
          ok = c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z';
        }
        while (start < i && (s.charAt(start) == '.' || s.charAt(start) == '-')) {
          start++;
        }
        if (ok && start < i) {
          out.add(new int[] {start, end});
        }
      }
    }
    return out;
  }

  /** Whether two capitals and two digits start a word. */
  static boolean mayHoldIban(String s) {
    for (int i = 0; i + 4 <= s.length(); i++) {
      if (s.charAt(i) >= 'A'
          && s.charAt(i) <= 'Z'
          && s.charAt(i + 1) >= 'A'
          && s.charAt(i + 1) <= 'Z'
          && isDigit(s.charAt(i + 2))
          && isDigit(s.charAt(i + 3))
          && (i == 0 || !isWord(s.charAt(i - 1)))) {
        return true;
      }
    }
    return false;
  }

  // Scanners for two of the server's patterns, finding the same leftmost
  // matches in linear time.

  /**
   * The server's {@code -----BEGIN (?:[A-Z ]+ )?PRIVATE KEY-----[\s\S]*?-----END (?:[A-Z ]+
   * )?PRIVATE KEY-----}: each BEGIN line with the first END line after it.
   */
  static List<int[]> privateKeySpans(Detector.Text t) {
    String s = t.s;
    List<int[]> out = new ArrayList<>();
    for (int from = 0; ; ) {
      int begin = s.indexOf("-----BEGIN ", from);
      if (begin < 0) {
        break;
      }
      int head = keyLabelEnd(s, begin + 11);
      if (head < 0) {
        from = begin + 1;
        continue;
      }
      int end = -1;
      int line = s.indexOf("-----END ", head);
      while (line >= 0 && (end = keyLabelEnd(s, line + 9)) < 0) {
        line = s.indexOf("-----END ", line + 1);
      }
      if (end < 0) {
        break; // a later BEGIN line finds no END line either
      }
      out.add(new int[] {begin, end});
      from = end;
    }
    return out;
  }

  /**
   * The end of {@code (?:[A-Z ]+ )?PRIVATE KEY-----} at i, or -1. "PRIVATE KEY" can only end the
   * run of capitals and spaces from i, so there is one place to look.
   */
  private static int keyLabelEnd(String s, int i) {
    int run = i;
    while (run < s.length() && (isUpper(s.charAt(run)) || s.charAt(run) == ' ')) {
      run++;
    }
    int label = run - "PRIVATE KEY".length();
    if (label < i || !s.startsWith("PRIVATE KEY-----", label)) {
      return -1;
    }
    if (label > i && (label < i + 2 || s.charAt(label - 1) != ' ')) {
      return -1; // the type before the label needs a letter or space and then a space
    }
    return label + "PRIVATE KEY-----".length();
  }

  private static boolean isUpper(char c) {
    return c >= 'A' && c <= 'Z';
  }

  private static boolean isLetter(char c) {
    return c >= 'a' && c <= 'z' || isUpper(c);
  }

  private static boolean isScheme(char c) {
    return isLetter(c) || isDigit(c) || c == '+' || c == '.' || c == '-';
  }

  /** Whether c ends the password of a URL: whitespace, "/", "?", "#" or "@". */
  private static boolean endsPassword(char c) {
    switch (c) {
      case '\t':
      case '\n':
      case '\f':
      case '\r':
      case ' ':
      case '/':
      case '?':
      case '#':
      case '@':
        return true;
      default:
        return false;
    }
  }

  /**
   * The password of the server's {@code \b[A-Za-z][A-Za-z0-9+.\-]*://[^\s/?#@:]*:([^\s/?#@]+)@}.
   * Every start in the scheme before one "://" shares the rest of the match, so only the first is
   * tried.
   */
  static List<int[]> urlCredentialSpans(Detector.Text t) {
    String s = t.s;
    int n = s.length();
    List<int[]> out = new ArrayList<>();
    int from = 0;
    for (int sep = s.indexOf("://"); sep >= 0; ) {
      int scheme = sep;
      while (scheme > from && isScheme(s.charAt(scheme - 1))) {
        scheme--;
      }
      // The first letter at a word boundary starts the scheme.
      while (scheme < sep
          && !(isLetter(s.charAt(scheme)) && (scheme == 0 || !isWord(s.charAt(scheme - 1))))) {
        scheme++;
      }
      if (scheme < sep) {
        int user = sep + 3;
        while (user < n && s.charAt(user) != ':' && !endsPassword(s.charAt(user))) {
          user++;
        }
        if (user < n && s.charAt(user) == ':') {
          int end = user + 1;
          while (end < n && !endsPassword(s.charAt(end))) {
            end++;
          }
          if (end > user + 1 && end < n && s.charAt(end) == '@') {
            out.add(new int[] {user + 1, end});
            from = end + 1;
            sep = s.indexOf("://", from);
            continue;
          }
        }
      }
      sep = s.indexOf("://", sep + 1);
    }
    return out;
  }
}
