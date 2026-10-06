# Changelog

All notable changes to the Fixwire Java and Kotlin SDK are listed here. Versions follow [Semantic
Versioning](https://semver.org); before 1.0, a minor version may change the
API.

## [0.1.0] - 2026-10-06

First release.

- `fixwire` (Java 8+, no dependencies): errors with their causes, thread-bound scopes, spans with W3C trace context, request sessions, cron monitors and feedback.
- `fixwire-kotlin`: coroutines keep their scope and span.
- `fixwire-servlet`, `fixwire-spring-boot` (a starter), `fixwire-okhttp`, `fixwire-httpclient` and `fixwire-logback` integrations.
- On-device redaction with the server's rules; an error budget for crash loops.
- Examples run against a fake ingest in CI: a Spring Boot shop, a cron job and a Kotlin worker.
