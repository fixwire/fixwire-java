package io.fixwire;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/** The HTTP request an event happened in. */
public final class Request {
  private String method;
  private String url;
  private String query;
  private Map<String, String> headers = new LinkedHashMap<>();
  private String route;
  private Supplier<String> routeSupplier;
  private String clientAddress;

  public String getMethod() {
    return method;
  }

  public void setMethod(String method) {
    this.method = method;
  }

  /**
   * The URL without its query.
   *
   * @return such as {@code https://shop.example.com/cart}
   */
  public String getUrl() {
    return url;
  }

  public void setUrl(String url) {
    this.url = url;
  }

  public String getQuery() {
    return query;
  }

  public void setQuery(String query) {
    this.query = query;
  }

  public Map<String, String> getHeaders() {
    return headers;
  }

  public void setHeaders(Map<String, String> headers) {
    this.headers = headers == null ? new LinkedHashMap<String, String>() : headers;
  }

  /**
   * The route the request matched, such as {@code /items/{id}}; names the transaction when nothing
   * else does.
   *
   * @return the route, or null
   */
  public String getRoute() {
    if (route == null && routeSupplier != null) {
      try {
        return routeSupplier.get();
      } catch (RuntimeException e) {
        return null;
      }
    }
    return route;
  }

  public void setRoute(String route) {
    this.route = route;
  }

  /**
   * Reads the route when an event needs it, for frameworks that match it after the request started
   * (integrations set this).
   *
   * @param routeSupplier gives the route, or null
   */
  public void setRouteSupplier(Supplier<String> routeSupplier) {
    this.routeSupplier = routeSupplier;
  }

  /**
   * The client's address; sent only with {@link Options#setSendDefaultPii}.
   *
   * @return the address, or null
   */
  public String getClientAddress() {
    return clientAddress;
  }

  public void setClientAddress(String clientAddress) {
    this.clientAddress = clientAddress;
  }

  /**
   * Whether a header may identify someone or hold a secret; such headers are sent only with {@link
   * Options#setSendDefaultPii}.
   *
   * @param name the header's name
   * @return true for Authorization, Cookie, forwarding headers and API keys
   */
  public static boolean isSensitiveHeader(String name) {
    switch (name.toLowerCase(java.util.Locale.ROOT)) {
      case "authorization":
      case "proxy-authorization":
      case "cookie":
      case "set-cookie":
      case "x-forwarded-for":
      case "x-real-ip":
      case "x-api-key":
        return true;
      default:
        return false;
    }
  }
}
