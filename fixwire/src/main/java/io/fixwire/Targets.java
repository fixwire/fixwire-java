package io.fixwire;

import java.util.List;
import java.util.Locale;

/**
 * Which outgoing requests carry trace headers: the URLs the trace propagation targets match
 * (sdks/PROTOCOL.md §13), compared without their user info, query and fragment.
 */
final class Targets {
  private Targets() {}

  /** Whether one of the targets matches the URL. */
  static boolean match(List<String> targets, String url) {
    if (url == null || targets == null || targets.isEmpty()) {
      return false;
    }
    Url u = Url.parse(url);
    if (u == null) {
      return false; // not absolute: a server has no origin to resolve it against
    }
    for (String t : targets) {
      if (t == null || t.isEmpty() || t.startsWith("/")) {
        continue; // a path of the page's own origin, which servers don't have
      }
      if (t.contains("://")) {
        Url prefix = Url.parse(t);
        if (prefix != null && u.compared().startsWith(prefix.compared())) {
          return true;
        }
      } else if (hostMatches(t, u)) {
        return true;
      }
    }
    return false;
  }

  /** A host target, with a port if it has one: that host and its subdomains. */
  private static boolean hostMatches(String target, Url u) {
    String t = target.toLowerCase(Locale.ROOT);
    String host = t;
    String port = null;
    int colon = t.lastIndexOf(':');
    if (colon > 0 && t.indexOf(']') < colon && (t.startsWith("[") || t.indexOf(':') == colon)) {
      host = t.substring(0, colon);
      port = t.substring(colon + 1);
    }
    if (host.isEmpty()) {
      return false;
    }
    if (!u.host.equals(host) && !u.host.endsWith("." + host)) {
      return false;
    }
    return port == null || port.equals(u.port());
  }

  /** An absolute URL's parts, as targets compare them. */
  static final class Url {
    final String scheme;
    final String host;
    final String explicitPort;
    final String path;

    private Url(String scheme, String host, String explicitPort, String path) {
      this.scheme = scheme;
      this.host = host;
      this.explicitPort = explicitPort;
      this.path = path;
    }

    /**
     * {@code scheme://host[:port]/path}, without user info, query and fragment; null if not
     * absolute.
     */
    static Url parse(String s) {
      s = s.trim();
      int sep = s.indexOf("://");
      if (sep <= 0 || !isScheme(s, sep)) {
        return null;
      }
      int start = sep + 3;
      int end = start;
      while (end < s.length() && "/?#".indexOf(s.charAt(end)) < 0) {
        end++;
      }
      String authority = s.substring(start, end);
      authority = authority.substring(authority.lastIndexOf('@') + 1); // without user info
      int pathEnd = end;
      while (pathEnd < s.length() && s.charAt(pathEnd) != '?' && s.charAt(pathEnd) != '#') {
        pathEnd++;
      }
      String host = authority;
      String port = null;
      int colon = authority.lastIndexOf(':');
      if (colon >= 0 && authority.indexOf(']') < colon) {
        host = authority.substring(0, colon);
        port = authority.substring(colon + 1);
      }
      return new Url(
          s.substring(0, sep).toLowerCase(Locale.ROOT),
          host.toLowerCase(Locale.ROOT),
          port == null || port.isEmpty() ? null : port,
          s.substring(end, pathEnd));
    }

    private static boolean isScheme(String s, int end) {
      for (int i = 0; i < end; i++) {
        char c = s.charAt(i);
        boolean letter = c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z';
        if (!letter && (i == 0 || !(c >= '0' && c <= '9' || c == '+' || c == '-' || c == '.'))) {
          return false;
        }
      }
      return true;
    }

    /** The port, the scheme's own when the URL names none. */
    String port() {
      if (explicitPort != null) {
        return explicitPort;
      }
      return scheme.equals("https") || scheme.equals("wss")
          ? "443"
          : scheme.equals("http") || scheme.equals("ws") ? "80" : null;
    }

    /** The URL as a {@code ://} target is compared with. */
    String compared() {
      return scheme + "://" + host + (explicitPort == null ? "" : ":" + explicitPort) + path;
    }
  }
}
