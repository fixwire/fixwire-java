package com.example.shop;

import io.fixwire.Fixwire;
import io.fixwire.Span;
import io.fixwire.User;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class ShopController {
  record Product(String id, String name, int priceCents) {}

  record OrderRequest(String sku, String card) {}

  /** What the payment provider answers with. */
  static final class PaymentException extends RuntimeException {
    PaymentException(String code) {
      super("payment declined: " + code);
    }
  }

  // The catalog stands in for a database.
  private static final Map<String, Product> PRODUCTS =
      Map.of(
          "sku_1", new Product("sku_1", "Mug", 1200),
          "sku_2", new Product("sku_2", "Poster", 2500));

  private final AtomicInteger orders = new AtomicInteger();

  @GetMapping("/products/{id}")
  public Product product(@PathVariable String id) {
    // A span for the lookup, under the request's.
    try (Span span = Fixwire.startSpan("SELECT products", "db.query")) {
      span.setAttribute("db.system", "postgresql");
      Product p = PRODUCTS.get(id);
      if (p == null) {
        // Spring answers 404; nothing escapes, so nothing is reported.
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no such product");
      }
      return p;
    }
  }

  @PostMapping("/orders")
  public ResponseEntity<Map<String, String>> order(
      @RequestHeader(name = "X-User-Id", required = false) String userId,
      @RequestBody OrderRequest in) {
    if (userId != null) {
      Fixwire.setUser(new User(userId)); // this request's scope only
    }
    Fixwire.setTag("sku", in.sku());
    Fixwire.addBreadcrumb("order", "order received for " + in.sku());
    String orderId = "ord_" + orders.incrementAndGet();
    try {
      charge(in.card());
    } catch (PaymentException e) {
      // Handled: the customer gets an answer, Fixwire gets the error with the order.
      Fixwire.withScope(
          s -> {
            Map<String, Object> order = new LinkedHashMap<>();
            order.put("id", orderId);
            order.put("sku", in.sku());
            s.setContext("order", order);
            Fixwire.captureException(new IllegalStateException("charging order " + orderId, e));
          });
      return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED)
          .body(Map.of("error", "payment declined"));
    }
    return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", orderId));
  }

  @GetMapping("/admin/report")
  public Map<String, Integer> report() {
    List<Integer> cents = List.of(); // today's orders: none yet
    int total = cents.stream().mapToInt(Integer::intValue).sum();
    // A bug: with no orders this divides by zero. The exception escapes the app;
    // the filter reports it as a crash and Spring answers 500.
    return Map.of("averageCents", total / cents.size());
  }

  private static void charge(String card) {
    if ("4000000000000002".equals(card)) { // the test card that is always declined
      throw new PaymentException("card_declined");
    }
  }
}
