package com.example.app;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

/** An app with nothing but the starter and the fixwire.* properties. */
@SpringBootApplication
public class TestApp {
  @RestController
  static class Checkout {
    private static final Logger LOG = LoggerFactory.getLogger(Checkout.class);
    private final RestClient payments;

    Checkout(RestClient.Builder builder, @Value("${payments.url}") String paymentsUrl) {
      this.payments = builder.baseUrl(paymentsUrl).build();
    }

    @GetMapping("/checkout/{id}")
    String checkout() {
      LOG.info("checking out");
      HttpStatusCode status =
          payments
              .post()
              .uri("/fail/charge")
              .retrieve()
              .onStatus(s -> true, (req, res) -> {})
              .toBodilessEntity()
              .getStatusCode();
      if (status.isError()) {
        LOG.error("payment service answered {}", status.value());
        return "later";
      }
      return "ok";
    }

    @GetMapping("/crash")
    String crash() {
      throw new IllegalStateException("inventory out of sync");
    }
  }
}
