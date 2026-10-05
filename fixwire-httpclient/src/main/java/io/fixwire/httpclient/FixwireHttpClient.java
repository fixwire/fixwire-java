package io.fixwire.httpclient;

import io.fixwire.Hub;
import io.fixwire.OutgoingRequest;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

/**
 * A {@code java.net.http.HttpClient} that times its requests as client spans of the current trace,
 * sends trace headers to the trace propagation targets, and leaves an {@code http} breadcrumb for
 * each. Everything else is the wrapped client's.
 *
 * <pre>{@code
 * HttpClient client = FixwireHttpClient.wrap(HttpClient.newHttpClient());
 * }</pre>
 *
 * <p>Asynchronous requests belong to the trace of the thread that sends them.
 */
public final class FixwireHttpClient extends HttpClient {
  private final HttpClient delegate;

  private FixwireHttpClient(HttpClient delegate) {
    this.delegate = delegate;
  }

  /**
   * Wraps a client.
   *
   * @param client the client that sends
   * @return the traced client
   */
  public static HttpClient wrap(HttpClient client) {
    return client instanceof FixwireHttpClient ? client : new FixwireHttpClient(client);
  }

  @Override
  public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
      throws IOException, InterruptedException {
    Map<String, String> headers = new LinkedHashMap<>();
    OutgoingRequest out = start(Hub.current(), request, headers);
    try {
      HttpResponse<T> response = delegate.send(withHeaders(request, headers), handler);
      out.end(response.statusCode());
      return response;
    } catch (IOException | InterruptedException | RuntimeException e) {
      out.fail(e);
      throw e;
    }
  }

  @Override
  public <T> CompletableFuture<HttpResponse<T>> sendAsync(
      HttpRequest request, HttpResponse.BodyHandler<T> handler) {
    return sendAsync(request, handler, null);
  }

  @Override
  public <T> CompletableFuture<HttpResponse<T>> sendAsync(
      HttpRequest request,
      HttpResponse.BodyHandler<T> handler,
      HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
    Map<String, String> headers = new LinkedHashMap<>();
    OutgoingRequest out = start(Hub.current(), request, headers);
    CompletableFuture<HttpResponse<T>> future =
        pushPromiseHandler == null
            ? delegate.sendAsync(withHeaders(request, headers), handler)
            : delegate.sendAsync(withHeaders(request, headers), handler, pushPromiseHandler);
    return future.whenComplete(
        (response, error) -> {
          if (error != null) {
            out.fail(error);
          } else {
            out.end(response.statusCode());
          }
        });
  }

  private static OutgoingRequest start(Hub hub, HttpRequest request, Map<String, String> headers) {
    return OutgoingRequest.start(
        hub,
        request.method(),
        request.uri().toString(),
        (name, value) -> {
          if (request.headers().firstValue(name).isEmpty()) {
            headers.put(name, value); // the app's own trace headers win
          }
        });
  }

  /** The request with the trace headers added (requests can't be changed, so a copy). */
  static HttpRequest withHeaders(HttpRequest request, Map<String, String> headers) {
    if (headers.isEmpty()) {
      return request;
    }
    HttpRequest.Builder b =
        HttpRequest.newBuilder(request.uri())
            .method(
                request.method(),
                request.bodyPublisher().orElse(HttpRequest.BodyPublishers.noBody()))
            .expectContinue(request.expectContinue());
    request.timeout().ifPresent(b::timeout);
    request.version().ifPresent(b::version);
    request.headers().map().forEach((name, values) -> values.forEach(v -> b.header(name, v)));
    headers.forEach(b::header);
    return b.build();
  }

  @Override
  public Optional<CookieHandler> cookieHandler() {
    return delegate.cookieHandler();
  }

  @Override
  public Optional<Duration> connectTimeout() {
    return delegate.connectTimeout();
  }

  @Override
  public Redirect followRedirects() {
    return delegate.followRedirects();
  }

  @Override
  public Optional<ProxySelector> proxy() {
    return delegate.proxy();
  }

  @Override
  public SSLContext sslContext() {
    return delegate.sslContext();
  }

  @Override
  public SSLParameters sslParameters() {
    return delegate.sslParameters();
  }

  @Override
  public Optional<Authenticator> authenticator() {
    return delegate.authenticator();
  }

  @Override
  public Version version() {
    return delegate.version();
  }

  @Override
  public Optional<Executor> executor() {
    return delegate.executor();
  }

  @Override
  public WebSocket.Builder newWebSocketBuilder() {
    return delegate.newWebSocketBuilder();
  }

  // Java 21 made clients closeable. These override its methods at run time (they are not in the
  // Java 11 API this is compiled against) and hand them to the wrapped client.

  /** Closes the wrapped client (Java 21+). */
  public void close() {
    call("close");
  }

  /** Shuts the wrapped client down (Java 21+). */
  public void shutdown() {
    call("shutdown");
  }

  /** Shuts the wrapped client down now (Java 21+). */
  public void shutdownNow() {
    call("shutdownNow");
  }

  /**
   * Waits for the wrapped client to terminate (Java 21+).
   *
   * @param duration the longest wait
   * @return whether it terminated
   * @throws InterruptedException when interrupted while waiting
   */
  public boolean awaitTermination(Duration duration) throws InterruptedException {
    try {
      return (Boolean)
          HttpClient.class.getMethod("awaitTermination", Duration.class).invoke(delegate, duration);
    } catch (InvocationTargetException e) {
      if (e.getCause() instanceof InterruptedException) {
        throw (InterruptedException) e.getCause();
      }
      throw new IllegalStateException(e.getCause());
    } catch (ReflectiveOperationException e) {
      throw new UnsupportedOperationException(e);
    }
  }

  /**
   * Whether the wrapped client terminated (Java 21+).
   *
   * @return whether it terminated
   */
  public boolean isTerminated() {
    try {
      return (Boolean) HttpClient.class.getMethod("isTerminated").invoke(delegate);
    } catch (ReflectiveOperationException e) {
      throw new UnsupportedOperationException(e);
    }
  }

  private void call(String method) {
    try {
      HttpClient.class.getMethod(method).invoke(delegate);
    } catch (InvocationTargetException e) {
      if (e.getCause() instanceof RuntimeException) {
        throw (RuntimeException) e.getCause();
      }
      throw new IllegalStateException(e.getCause());
    } catch (ReflectiveOperationException e) {
      throw new UnsupportedOperationException(method + " needs Java 21", e);
    }
  }
}
