# Spring Boot shop

A small Spring Boot 4 API with Fixwire set up the way a production service
would be.

```sh
FIXWIRE_DSN=https://<key>@<host> ./gradlew :examples:spring-boot-shop:bootRun   # from sdks/java
```

Then:

```sh
curl localhost:8080/products/sku_1                      # 200, with a database span
curl localhost:8080/products/nope                       # 404: not reported
curl -H 'X-User-Id: user-1' -H 'Content-Type: application/json' \
     -d '{"sku":"sku_1","card":"4242424242424242"}' localhost:8080/orders
curl -H 'X-User-Id: user-2' -H 'Content-Type: application/json' \
     -d '{"sku":"sku_1","card":"4000000000000002"}' localhost:8080/orders
curl localhost:8080/admin/report                        # an exception escapes: a crash, answered 500
```

What arrives in Fixwire:

- **The declined payment** as an error of `POST /orders`: the chain
  (`charging order ord_2` caused by `PaymentException`), the user
  `user-2`, the `sku` tag, the order as context and the breadcrumb before
  it. The customer got a 402; the error was handled.
- **The crash** in `GET /admin/report` (`ArithmeticException: / by
  zero`): nothing in the app caught it, so the filter reports it as a crash
  and Spring answers 500.
- **A trace per request**, named after its route (`GET /products/{id}`,
  from Spring MVC), with the database lookup under it. A caller's
  `traceparent` is continued.
- **Release health** for `shop@1.0.0`: each request is a session, ended
  well, with an error, or crashed.

How it is wired, in `FixwireConfig.java`:

```java
@Configuration
public class FixwireConfig {
  public FixwireConfig(@Value("${fixwire.dsn:}") String dsn, @Value("${fixwire.release:shop@1.0.0}") String release) {
    Fixwire.init(o -> {
      o.setDsn(dsn);              // empty: FIXWIRE_DSN
      o.setRelease(release);
      o.setTracesSampleRate(1.0);
    });
  }

  @Bean
  public FilterRegistrationBean<FixwireFilter> fixwireFilter() {
    FilterRegistrationBean<FixwireFilter> f = new FilterRegistrationBean<>(new FixwireFilter());
    f.setOrder(Ordered.HIGHEST_PRECEDENCE); // first, so it sees every request and every escaping exception
    return f;
  }

  @PreDestroy
  public void close() {
    Fixwire.close(2000); // send what is left when the app stops
  }
}
```

In controllers, `Fixwire.setUser`, `Fixwire.setTag` and
`Fixwire.captureException` act on the current request's scope only.
