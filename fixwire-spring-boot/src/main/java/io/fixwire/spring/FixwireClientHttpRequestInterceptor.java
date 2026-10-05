package io.fixwire.spring;

import io.fixwire.OutgoingRequest;
import java.io.IOException;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * Times {@code RestClient} and {@code RestTemplate} requests as client spans of the current trace,
 * sends trace headers to the trace propagation targets, and leaves an {@code http} breadcrumb for
 * each. The starter adds it to the builders Spring Boot gives out.
 */
public final class FixwireClientHttpRequestInterceptor implements ClientHttpRequestInterceptor {
  @Override
  public ClientHttpResponse intercept(
      HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
    OutgoingRequest out =
        OutgoingRequest.start(
            request.getMethod().name(),
            request.getURI().toString(),
            (name, value) -> {
              if (request.getHeaders().getFirst(name) == null) {
                request.getHeaders().set(name, value); // the app's own trace headers win
              }
            });
    try {
      ClientHttpResponse response = execution.execute(request, body);
      out.end(response.getStatusCode().value());
      return response;
    } catch (IOException | RuntimeException e) {
      out.fail(e);
      throw e;
    }
  }
}
