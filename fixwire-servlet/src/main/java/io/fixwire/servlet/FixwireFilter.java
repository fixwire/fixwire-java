package io.fixwire.servlet;

import io.fixwire.Client;
import io.fixwire.Hub;
import io.fixwire.Request;
import io.fixwire.Scope;
import io.fixwire.Span;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletMapping;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.MappingMatch;
import java.io.IOException;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Reports what happens in a servlet app's requests. Each request gets its own hub (its scope holds
 * the request), exceptions that escape the app are sent as crashes, each request is counted for
 * release health, and with tracing on it is a server span that continues the caller's trace, named
 * after the route when the framework says it (Spring MVC does).
 *
 * <p>Register it first in the filter chain, for every request:
 *
 * <pre>{@code
 * FilterRegistration.Dynamic f = servletContext.addFilter("fixwire", new FixwireFilter());
 * f.addMappingForUrlPatterns(EnumSet.allOf(DispatcherType.class), false, "/*");
 * }</pre>
 */
public final class FixwireFilter implements Filter {
  /** The request attribute Spring MVC puts the matched route pattern in. */
  static final String SPRING_ROUTE =
      "org.springframework.web.servlet.HandlerMapping.bestMatchingPattern";

  private static final String SEEN = FixwireFilter.class.getName() + ".seen";

  @Override
  public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
      throws IOException, ServletException {
    if (!(req instanceof HttpServletRequest)
        || !(res instanceof HttpServletResponse)
        || req.getAttribute(SEEN) != null) {
      chain.doFilter(req, res); // not HTTP, or an inner dispatch of a request we see already
      return;
    }
    req.setAttribute(SEEN, Boolean.TRUE);
    HttpServletRequest request = (HttpServletRequest) req;
    HttpServletResponse response = (HttpServletResponse) res;
    Hub hub = Hub.current().copy();
    Client client = hub.getClient();
    boolean pii = client != null && client.options().isSendDefaultPii();
    Scope scope = hub.getScope();
    scope.setRequest(requestOf(request, pii));

    try (Hub.Binding b = hub.bind()) {
      Span span =
          hub.spanBuilder(request.getMethod())
              .op("http.server")
              .continueTrace(
                  request.getHeader("traceparent"),
                  request.getHeader("tracestate"),
                  request.getHeader("baggage"))
              .attribute("http.request.method", request.getMethod())
              .attribute("url.path", request.getRequestURI())
              .attribute("url.scheme", request.getScheme())
              .attribute("server.address", request.getServerName())
              .attribute("user_agent.original", request.getHeader("User-Agent"))
              .start();
      Runnable endSession = hub.startRequestSession();
      AtomicBoolean ended = new AtomicBoolean();
      Runnable end =
          () -> {
            if (ended.compareAndSet(false, true)) {
              finish(span, request, response.getStatus());
              endSession.run();
            }
          };
      try {
        chain.doFilter(request, response);
      } catch (IOException | ServletException | RuntimeException | Error e) {
        Throwable cause =
            e instanceof ServletException && ((ServletException) e).getRootCause() != null
                ? ((ServletException) e).getRootCause()
                : e;
        hub.captureException(cause, "servlet", false, null);
        span.setError(cause);
        finish(span, request, 500);
        endSession.run();
        ended.set(true);
        throw e;
      }
      if (request.isAsyncStarted()) {
        request.getAsyncContext().addListener(new Ender(end));
      } else {
        end.run();
      }
    }
  }

  /** Ends the request when its async processing does. */
  private static final class Ender implements AsyncListener {
    private final Runnable end;

    Ender(Runnable end) {
      this.end = end;
    }

    @Override
    public void onComplete(AsyncEvent event) {
      end.run();
    }

    @Override
    public void onTimeout(AsyncEvent event) {
      end.run();
    }

    @Override
    public void onError(AsyncEvent event) {
      end.run();
    }

    @Override
    public void onStartAsync(AsyncEvent event) {
      event.getAsyncContext().addListener(this);
    }
  }

  private static void finish(Span span, HttpServletRequest request, int status) {
    String route = route(request);
    if (route != null) {
      span.setName(request.getMethod() + " " + route);
      span.setAttribute("http.route", route);
    }
    span.setAttribute("http.response.status_code", status);
    if (status >= 500) {
      span.setError("HTTP " + status);
    }
    span.close();
  }

  /**
   * The route the request matched: Spring MVC's pattern, else the servlet mapping when it names a
   * path (not the default servlet's {@code /}).
   */
  static String route(HttpServletRequest request) {
    Object spring = request.getAttribute(SPRING_ROUTE);
    if (spring instanceof String && !((String) spring).isEmpty()) {
      return (String) spring;
    }
    try {
      HttpServletMapping m = request.getHttpServletMapping();
      if (m != null
          && (m.getMappingMatch() == MappingMatch.EXACT
              || m.getMappingMatch() == MappingMatch.PATH)) {
        return m.getPattern();
      }
    } catch (RuntimeException | LinkageError e) {
      // a container before Servlet 4
    }
    return null;
  }

  static Request requestOf(HttpServletRequest r, boolean pii) {
    Request out = new Request();
    out.setMethod(r.getMethod());
    out.setUrl(r.getRequestURL().toString());
    out.setQuery(r.getQueryString());
    out.setRouteSupplier(() -> route(r));
    for (String name : Collections.list(r.getHeaderNames())) {
      if (pii || !Request.isSensitiveHeader(name)) {
        out.getHeaders().put(name, r.getHeader(name));
      }
    }
    if (pii) {
      String forwarded = r.getHeader("X-Forwarded-For");
      out.setClientAddress(
          forwarded != null && !forwarded.isEmpty()
              ? forwarded.split(",")[0].trim()
              : r.getRemoteAddr());
    }
    return out;
  }
}
