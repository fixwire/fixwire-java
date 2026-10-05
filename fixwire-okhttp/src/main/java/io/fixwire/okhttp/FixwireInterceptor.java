package io.fixwire.okhttp;

import io.fixwire.OutgoingRequest;
import java.io.IOException;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Times OkHttp requests as client spans of the current trace, sends trace headers to the trace
 * propagation targets, and leaves an {@code http} breadcrumb for each.
 *
 * <pre>{@code
 * OkHttpClient client = new OkHttpClient.Builder().addInterceptor(new FixwireInterceptor()).build();
 * }</pre>
 *
 * <p>Synchronous calls run on the caller's thread, with its hub. Asynchronous calls ({@code
 * enqueue}) run on OkHttp's threads: make the callback's work part of the trace with {@code
 * Fixwire.wrap}.
 */
public final class FixwireInterceptor implements Interceptor {
  @Override
  public Response intercept(Chain chain) throws IOException {
    Request request = chain.request();
    Request.Builder traced = request.newBuilder();
    OutgoingRequest out =
        OutgoingRequest.start(
            request.method(),
            request.url().toString(),
            (name, value) -> {
              if (request.header(name) == null) {
                traced.header(name, value); // the app's own trace headers win
              }
            });
    try {
      Response response = chain.proceed(traced.build());
      out.end(response.code());
      return response;
    } catch (IOException | RuntimeException e) {
      out.fail(e);
      throw e;
    }
  }
}
