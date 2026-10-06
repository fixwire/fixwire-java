# Changelog

All notable changes to the Fixwire Java and Kotlin SDK are listed here. Versions follow [Semantic
Versioning](https://semver.org); before 1.0, a minor version may change the
API.

## [Unreleased]

- Linear time for the error budget's fingerprint and the JWT detector: a few kilobytes of crafted text (`a@a@…`, `-eyJ-eyJ…`) stalled the capturing thread for seconds.
- Redaction stays fast on text with many findings and on maps with many keys that mask alike.
- Values that hold themselves become `[Circular ~]`, containers past ten levels `[Object]` or `[Array]`, objects whose `toString` fails `[Unreadable]`; they hung the capture or threw `StackOverflowError` into the app.
- Exceptions whose `getMessage` throws are captured.
- Requests to Fixwire never follow redirects; huge `Retry-After` and `Fixwire-Rate-Limits` values no longer overflow; unknown categories are not kept.
- `flush` no longer pins a virtual thread's carrier on Java 21.
- Spans keep 128 attributes, `fixwire.op` among them.
- The `java.util.logging` handler skips records logged while it captures one (from `beforeSend`), which recursed until the stack overflowed.
- The servlet filter never breaks a request or replaces the app's exception (a container hiding the headers made it fail).
- The uncaught exception handler always runs the handler before it.
- Every SDK keeps the same limits:
  - `maxValueLength` (default 1024): strings are at most that many bytes of UTF-8, cut where a character ends and ending in `...`; redaction runs first, over the part kept and the next 16 kB, so a private key or JWT the cut goes through is masked.
  - `maxStackFrames` (default 100) frames per exception, the newest; at most 10 exceptions in a chain, also for events built by hand.
  - Values the app gives keep their first 100 items and walk at most 10,000 containers; one that can't be read is `[Unreadable]`, NaN and the infinities are `"NaN"`, `"Infinity"`, `"-Infinity"`.
  - An error over 1 MB leaves out its breadcrumbs, then its contexts, and is dropped if still over; spans go in requests of at most 100 and 5 MB, and a span that can't fit alone is dropped.
  - Release health counts 5,000 users apart per send (then without the user) and sends at most 5,000 aggregates a request.
  - `Retry-After` may be an HTTP date; a 5xx with `Retry-After` pauses all data; retries wait about 1 s, then twice as long; a request whose next try is over 5 minutes away is dropped; `maxQueue` requests wait to be sent and as many for a retry.
  - An incoming `traceparent` needs version `00` and lower-case hex; `tracestate` over 512 bytes, `baggage` over 8,192 bytes, or either with a control character other than tab (W3C's list whitespace), is dropped whole.
  - `tracePropagationTargets` match a URL without its user info, query and fragment: a target with `://` is a URL prefix, any other a host with its subdomains (`example.com` no longer matches `badexample.com` or `example.com.evil.net`).
- Redaction has the server's new `secret_assignment` rule: secrets given to compound names (`access_token`, `client_secret`, `csrfToken`, `PHPSESSID`, `X-Amz-Signature`) and OAuth codes in URLs are masked, in linear time; span status messages are masked too, and a value redaction fails on is sent as `[Filtered]`. The app's own configuration (release, environment, service and server name, monitor slugs) is cut to `maxValueLength` but sent as given.
- Nothing the SDK does throws into the app: captures, feedback and check-ins log what fails (debug) and go on; `beforeBreadcrumb` failing keeps the breadcrumb.
- `init` (and the Spring Boot starter) no longer throws `IllegalArgumentException` on a malformed DSN: it says so on stderr, debug or not, and the SDK stays off.
- Logging integrations (`java.util.logging`, Logback) skip what is logged while the SDK captures, such as from `beforeSend`; `Hub.isCapturing()` says when.
- `close(timeout)` returns within its timeout; the duplicate budget reads a message's first 1,024 characters.

## [0.1.0] - 2026-10-06

First release.

- `fixwire` (Java 8+, no dependencies): errors with their causes, thread-bound scopes, spans with W3C trace context, request sessions, cron monitors and feedback.
- `fixwire-kotlin`: coroutines keep their scope and span.
- `fixwire-servlet`, `fixwire-spring-boot` (a starter), `fixwire-okhttp`, `fixwire-httpclient` and `fixwire-logback` integrations.
- On-device redaction with the server's rules; an error budget for crash loops.
- Examples run against a fake ingest in CI: a Spring Boot shop, a cron job and a Kotlin worker.
