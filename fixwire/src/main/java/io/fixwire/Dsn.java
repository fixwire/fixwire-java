package io.fixwire;

import java.net.URI;
import java.net.URISyntaxException;

/** Where the SDK sends, and with which key: {@code https://<key>@<host>}. */
public final class Dsn {
  private final String key;
  private final String baseUrl;

  private Dsn(String key, String baseUrl) {
    this.key = key;
    this.baseUrl = baseUrl;
  }

  /**
   * Reads a DSN.
   *
   * @param dsn the project's DSN
   * @return the parsed DSN
   * @throws IllegalArgumentException when it has no scheme, host or key
   */
  public static Dsn parse(String dsn) {
    URI u;
    try {
      u = new URI(dsn == null ? "" : dsn.trim());
    } catch (URISyntaxException e) {
      throw invalid();
    }
    String scheme = u.getScheme();
    String key = u.getRawUserInfo();
    if (!"https".equals(scheme) && !"http".equals(scheme)
        || u.getHost() == null
        || key == null
        || key.isEmpty()) {
      throw invalid();
    }
    int colon = key.indexOf(':');
    if (colon >= 0) {
      key = key.substring(0, colon);
    }
    if (key.isEmpty()) {
      throw invalid();
    }
    String path = u.getRawPath() == null ? "" : u.getRawPath();
    while (path.endsWith("/")) {
      path = path.substring(0, path.length() - 1);
    }
    String port = u.getPort() >= 0 ? ":" + u.getPort() : "";
    return new Dsn(key, scheme + "://" + u.getHost() + port + path);
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("fixwire: the DSN must look like https://<key>@<host>");
  }

  /**
   * The project's publishable key.
   *
   * @return the key
   */
  public String key() {
    return key;
  }

  /**
   * The DSN without the key; the endpoints are relative to it.
   *
   * @return the base URL
   */
  public String baseUrl() {
    return baseUrl;
  }

  /**
   * The address of an endpoint.
   *
   * @param path such as {@code /v1/logs}
   * @return the endpoint's URL
   */
  public String url(String path) {
    return baseUrl + path;
  }
}
