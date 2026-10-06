# Examples

Real apps, each with its own README. Each has a test that runs it against a
fake ingest and checks what Fixwire receives, so they keep working
(`./gradlew build` at the repository root runs them).

| Example | Shows |
|---|---|
| [spring-boot-shop](spring-boot-shop) | Spring Boot 4: Fixwire set up from `application.properties`, the servlet filter first in the chain; per-request users and tags; a handled error with context; an exception that escapes reported as a crash (Spring answers 500); 404s not reported; a database span; a trace per request named after its route; release health |
| [nightly-report](nightly-report) | A cron job in plain Java: check-ins to a monitor (created from the first one), `java.util.logging` records as breadcrumbs and events, one scope per account, carrying on after a failure, a summary warning, a trace for the run, `close` before exiting |
| [order-worker](order-worker) | Kotlin coroutines: three workers, each order with its own hub (`FixwireContext`) so its user and tags stay with it on any thread, a trace per order (`withSpan`), a failed order reported without stopping the others |
