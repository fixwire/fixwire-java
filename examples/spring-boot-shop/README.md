# Spring Boot shop

A small Spring Boot 4 API with Fixwire set up the way a production service
would be: the `fixwire-spring-boot` starter and a few properties, no code.

```sh
FIXWIRE_DSN=https://<key>@<host> ./gradlew :examples:spring-boot-shop:bootRun   # from the repository root
```

It listens on `:8080` and reserves stock at an inventory service
(`inventory.url`, default `http://localhost:8081`; without one, orders fail
at the reservation, and that is reported too). Then:

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
  `user-2`, the `sku` tag, the order as context, and the breadcrumbs that
  led to it (the `order received` log line, the call to the inventory
  service). The customer got a 402; the error was handled.
- **A logged error**: `LOG.error("no stock for {}", sku)` is an event of
  its request, with its user and tags, when the inventory says sold out.
- **The crash** in `GET /admin/report` (`ArithmeticException: / by
  zero`): nothing in the app caught it, so it is reported as a crash and
  Spring answers 500. Frames of `com.example.shop` are marked as your code.
- **A trace per request**, named after its route (`GET /products/{id}`),
  with the database lookup and the `RestClient` call to the inventory
  service under it. The inventory service gets a `traceparent` header and
  continues the trace; other hosts get none.
- **Release health** for `shop@1.0.0`: each request is a session, ended
  well, with an error, or crashed.

How it is wired:

```kotlin
// build.gradle.kts
implementation("io.fixwire:fixwire-spring-boot:0.1.0")
```

```properties
# application.properties
fixwire.release=shop@1.0.0
fixwire.traces-sample-rate=1.0
fixwire.trace-propagation-targets=${inventory.url}
```

The starter sets Fixwire up when the app starts (the DSN from
`fixwire.dsn` or `FIXWIRE_DSN`), puts the request filter first, traces
requests of the `RestClient.Builder` and `RestTemplateBuilder` Spring Boot
gives out, sends Logback records (breadcrumbs from `INFO`, events from
`ERROR`), and flushes when the app stops. In controllers,
`Fixwire.setUser`, `Fixwire.setTag` and `Fixwire.captureException` act on
the current request only.
