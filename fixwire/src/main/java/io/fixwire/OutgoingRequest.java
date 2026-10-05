package io.fixwire;

import java.net.URI;

/**
 * An outgoing HTTP request, for HTTP client integrations (OkHttp, Spring's {@code RestClient},
 * {@code java.net.http}): a client span under the current span when it is sampled, trace headers
 * when the URL is one of the trace propagation targets, and an {@code http} breadcrumb when it
 * ends. Nothing here throws into the caller's request.
 *
 * <pre>{@code
 * OutgoingRequest out = OutgoingRequest.start("GET", url, builder::header);
 * try {
 *   Response res = send(builder.build());
 *   out.end(res.code());
 *   return res;
 * } catch (IOException e) {
 *   out.fail(e);
 *   throw e;
 * }
 * }</pre>
 */
public final class OutgoingRequest {
  /** Adds a header to the request being built. */
  public interface HeaderSetter {
    /**
     * Sets a header.
     *
     * @param name the header's name
     * @param value its value
     */
    void set(String name, String value);
  }

  private final Hub hub;
  private final String method;
  private final String url;
  private final Span span;
  private boolean ended;

  private OutgoingRequest(Hub hub, String method, String url, Span span) {
    this.hub = hub;
    this.method = method;
    this.url = url;
    this.span = span;
  }

  /**
   * Starts tracking a request on the current thread's hub.
   *
   * @param method such as {@code GET}
   * @param url the request's URL
   * @param headers adds trace headers to the request, before it is sent
   * @return the request in flight
   */
  public static OutgoingRequest start(String method, String url, HeaderSetter headers) {
    return start(Hub.current(), method, url, headers);
  }

  /**
   * Starts tracking a request.
   *
   * @param hub the hub of the work the request is for
   * @param method such as {@code GET}
   * @param url the request's URL
   * @param headers adds trace headers to the request, before it is sent
   * @return the request in flight
   */
  public static OutgoingRequest start(Hub hub, String method, String url, HeaderSetter headers) {
    String plain = withoutQuery(url);
    String m = method == null ? "GET" : method.toUpperCase(java.util.Locale.ROOT);
    Span span = null;
    try {
      Client client = hub.getClient();
      Span parent = hub.getScope().getSpan();
      if (parent != null && parent.isSampled()) {
        span =
            hub.spanBuilder(m + " " + plain)
                .op("http.client")
                .kind(Span.Kind.CLIENT)
                .attribute("http.request.method", m)
                .attribute("url.full", plain)
                .attribute("server.address", host(url))
                .startDetached();
      }
      Span from = span != null ? span : parent;
      if (from != null && client != null && client.shouldPropagate(url)) {
        headers.set("traceparent", from.traceparent());
        if (from.tracestate() != null) {
          headers.set("tracestate", from.tracestate());
        }
        if (from.baggage() != null) {
          headers.set("baggage", from.baggage());
        }
      }
    } catch (RuntimeException e) {
      // tracing must never break the request
    }
    return new OutgoingRequest(hub, m, plain, span);
  }

  /**
   * Ends the request with the server's answer; a 4xx or 5xx fails the span.
   *
   * @param status the response's status code
   */
  public void end(int status) {
    finish(status, null);
  }

  /**
   * Ends a request that got no answer.
   *
   * @param error what went wrong (a timeout, a refused connection, …)
   */
  public void fail(Throwable error) {
    finish(0, error);
  }

  private synchronized void finish(int status, Throwable error) {
    if (ended) {
      return;
    }
    ended = true;
    try {
      if (span != null) {
        if (status > 0) {
          span.setAttribute("http.response.status_code", status);
        }
        if (error != null) {
          span.setError(error);
        } else if (status >= 400) {
          span.setError("HTTP " + status);
        }
        span.finish();
      }
      Breadcrumb b = new Breadcrumb("http", null);
      b.setType("http");
      b.setLevel(error != null || status >= 500 ? Level.ERROR : Level.INFO);
      b.putData("method", method);
      b.putData("url", url);
      if (status > 0) {
        b.putData("status_code", status);
      }
      hub.addBreadcrumb(b);
    } catch (RuntimeException e) {
      // tracing must never break the request
    }
  }

  private static String withoutQuery(String url) {
    int cut = url.length();
    int q = url.indexOf('?');
    int f = url.indexOf('#');
    if (q >= 0) {
      cut = q;
    }
    if (f >= 0 && f < cut) {
      cut = f;
    }
    return url.substring(0, cut);
  }

  private static String host(String url) {
    try {
      return URI.create(url).getHost();
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
