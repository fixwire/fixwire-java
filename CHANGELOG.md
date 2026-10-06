# Changelog

All notable changes to the Fixwire Java and Kotlin SDK are listed here. Versions follow [Semantic
Versioning](https://semver.org); before 1.0, a minor version may change the
API.

## [Unreleased]

- Linear time for the error budget's fingerprint and the JWT detector: a few kilobytes of crafted text (`a@a@…`, `-eyJ-eyJ…`) stalled the capturing thread for seconds.
- Redaction stays fast on text with many findings and on maps with many keys that mask alike.
- Values that hold themselves become `[Circular ~]`, containers past ten levels `[Object]` or `[Array]`, objects whose `toString` fails their class's name; they hung the capture or threw `StackOverflowError` into the app.
- Exceptions whose `getMessage` throws are captured.
- Requests to Fixwire never follow redirects; huge `Retry-After` and `Fixwire-Rate-Limits` values no longer overflow; unknown categories are not kept.
- `flush` no longer pins a virtual thread's carrier on Java 21.
- Spans keep 128 attributes; a caller's `tracestate` over 512 characters or `baggage` over 8192 is not passed on.
- The `java.util.logging` handler skips records logged while it captures one (from `beforeSend`), which recursed until the stack overflowed.
- The servlet filter never breaks a request or replaces the app's exception (a container hiding the headers made it fail).
- The uncaught exception handler always runs the handler before it.

## [0.1.0] - 2026-10-06

First release.

- `fixwire` (Java 8+, no dependencies): errors with their causes, thread-bound scopes, spans with W3C trace context, request sessions, cron monitors and feedback.
- `fixwire-kotlin`: coroutines keep their scope and span.
- `fixwire-servlet`, `fixwire-spring-boot` (a starter), `fixwire-okhttp`, `fixwire-httpclient` and `fixwire-logback` integrations.
- On-device redaction with the server's rules; an error budget for crash loops.
- Examples run against a fake ingest in CI: a Spring Boot shop, a cron job and a Kotlin worker.
