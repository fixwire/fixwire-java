# fixwire for Java and Kotlin

[![CI](https://github.com/fixwire/fixwire-java/actions/workflows/ci.yml/badge.svg)](https://github.com/fixwire/fixwire-java/actions/workflows/ci.yml)

The Fixwire SDK for the JVM: errors with their causes, traces, release
health, cron monitors and feedback. The core runs on Java 8 and newer and
depends on nothing.

| Module | For |
|---|---|
| `io.fixwire:fixwire` | Every app: errors, spans, sessions, check-ins, feedback, `java.util.logging` |
| `io.fixwire:fixwire-spring-boot` | Spring Boot 4 apps: everything below from `application.properties` (Java 17+) |
| `io.fixwire:fixwire-servlet` | Servlet apps (Tomcat, Jetty, Undertow; Jakarta Servlet, Java 11+) |
| `io.fixwire:fixwire-logback` | Logback records as breadcrumbs and events (Java 11+) |
| `io.fixwire:fixwire-okhttp` | OkHttp requests as client spans and breadcrumbs |
| `io.fixwire:fixwire-httpclient` | `java.net.http` requests as client spans and breadcrumbs (Java 11+) |
| `io.fixwire:fixwire-kotlin` | Kotlin coroutines |

```kotlin
// build.gradle.kts
dependencies { implementation("io.fixwire:fixwire:0.1.0") }
```

```java
Fixwire.init(o -> {
  o.setDsn("https://fw_pk_live_…@ingest.eu.fixwire.io");
  o.setRelease("api@1.4.0");
});

try {
  charge(order);
} catch (PaymentException e) {
  Fixwire.captureException(e);
}
```

```kotlin
Fixwire.init {
    it.dsn = "https://fw_pk_live_…@ingest.eu.fixwire.io"
    it.release = "api@1.4.0"
}
```

The DSN is your project's publishable key and the ingest host,
`https://<key>@<host>`. Without one the SDK reads `FIXWIRE_DSN`; without
either it does nothing. `FIXWIRE_RELEASE` and `FIXWIRE_ENVIRONMENT` work the
same way. `init` never throws: a malformed DSN is reported on stderr and the
SDK stays off, so a typo can't stop the app from starting.

`init` also reports exceptions no code caught (the handler that was there
before still runs), and the JVM's shutdown waits up to two seconds for what
is left to be sent.

**What's different**
- Secrets and personal data are masked on the device, with the same rules
  as the Fixwire server (`setRedact(false)` turns it off).
- A crash loop costs a few events and a count, not your quota
  (`getErrorBudget()`).
- Captures never block: one daemon thread sends from a bounded queue,
  retries with backoff and honours rate limits, pausing only the kind of
  data a limit names.
- It speaks the Fixwire protocol: errors, messages and spans travel as
  OpenTelemetry's OTLP/HTTP (JSON), with structured stack traces,
  breadcrumbs and redaction on top.

## Spring Boot

```kotlin
implementation("io.fixwire:fixwire-spring-boot:0.1.0")
```

```properties
fixwire.release=shop@1.4.0
fixwire.traces-sample-rate=0.2
fixwire.trace-propagation-targets=https://inventory.internal
```

That's all: the starter sets Fixwire up when the app starts (the DSN from
`fixwire.dsn` or `FIXWIRE_DSN`), reports each request (its scope, crashes,
release health, a server span named after the route), traces the requests
of the `RestClient.Builder` and `RestTemplateBuilder` Spring Boot gives
out, sends Logback records, marks your application's package as your code,
and flushes when the app stops. `fixwire.logging.*` sets the Logback levels
(or turns it off).

## Errors

An exception is sent with its causes and their stacks. Frames of the JDK,
Kotlin and well-known libraries are marked as not yours; name your packages
with `setInAppIncludes` when the defaults guess wrong.

```java
Fixwire.configureScope(s -> {
  s.setUser(new User("user-1"));
  s.setTag("plan", "team");
});
Fixwire.addBreadcrumb("cart", "checkout started");
Fixwire.captureMessage("disk usage above 90%", Level.WARNING);
```

## Requests and threads

`Fixwire`'s methods use the current thread's hub. A request (or a job)
gets its own copy, so what it sets stays with it:

```java
try (Hub.Binding b = Hub.current().copy().bind()) {
  handle(request);
}
executor.submit(Fixwire.wrap(() -> work())); // the task runs with a copy of the hub
```

**Servlets** (`fixwire-servlet`): register `FixwireFilter` first, for every
request. It does the above for each request, sends exceptions that escape
the app as crashes, counts the request for release health and, with tracing
on, makes it a server span that continues the caller's trace, named after
the route (Spring MVC's pattern, or the servlet mapping).

**Coroutines** (`fixwire-kotlin`): `launch(FixwireContext()) { … }` keeps a
coroutine's hub on whichever thread runs it; `withSpan(name, op) { … }`
times a suspending block.

## Tracing

```java
o.setTracesSampleRate(0.2);

try (Span span = Fixwire.startSpan("SELECT carts", "db.query")) {
  …
}
```

A span without a parent in the process is sent with the spans under it
when it closes. `Fixwire.spanBuilder(name).continueTrace(traceparent,
tracestate, baggage).start()` continues a caller's trace; its sampling
decision holds.

Trace headers go only to `setTracePropagationTargets`, which compare a URL
without its user info, query and fragment:

```java
o.setTracePropagationTargets(List.of(
    "https://api.example.com/v2", // URLs that start with it
    "example.com",                // that host and its subdomains (not badexample.com)
    "internal:8443"));            // a host on that port
```

Outgoing requests become client spans of the current trace, with an `http`
breadcrumb each:

```java
OkHttpClient okhttp = new OkHttpClient.Builder().addInterceptor(new FixwireInterceptor()).build();
HttpClient jdk = FixwireHttpClient.wrap(HttpClient.newHttpClient());
```

Other clients can use `OutgoingRequest` the same way.

## Logs

```java
Logger.getLogger("").addHandler(new FixwireHandler()); // java.util.logging
```

```xml
<!-- logback.xml (the Spring Boot starter does this by itself) -->
<appender name="FIXWIRE" class="io.fixwire.logback.FixwireAppender"/>
<root level="INFO"><appender-ref ref="FIXWIRE"/></root>
```

`INFO` and above become breadcrumbs; `SEVERE` (`ERROR` in Logback) and
above are sent as events, as the record's exception when it has one (once,
if the app captured it already). Logback's MDC goes with them.

## Cron jobs and feedback

```java
Fixwire.withMonitor("nightly-report",
    CheckIn.MonitorConfig.crontab("0 3 * * *").timezone("Europe/Berlin"),
    () -> report());

Feedback f = new Feedback("Refunded the wrong order");
f.setScore(-1);
f.setTraceId(runTraceId);
Fixwire.captureFeedback(f); // a negative score opens a user_feedback issue for the agent
```

## Options

| Option | Default | |
|---|---|---|
| `dsn` | `FIXWIRE_DSN` | Where to send; nothing is sent without one |
| `release`, `environment` | `FIXWIRE_RELEASE`, `production` | Release health needs a release |
| `serviceName` | `OTEL_SERVICE_NAME`, else `api` of `api@1.4.0` | |
| `sampleRate` | 1 | Share of errors sent |
| `tracesSampleRate` | 0 | Share of new traces kept |
| `tracePropagationTargets` | none | URL prefixes and hosts that receive trace headers |
| `beforeSend`, `beforeBreadcrumb` | | Change or drop events and breadcrumbs |
| `sendDefaultPii` | off | Send the user's IP address and identifying headers |
| `redact`, `sensitiveKeys` | on, the server's keys | On-device masking |
| `errorBudget` | 10 per issue, then 1 a minute; 600 a minute | |
| `inAppIncludes`, `inAppExcludes` | all but the JDK's and known libraries' | Which frames are your code |
| `maxBreadcrumbs` | 100 | Breadcrumbs kept, the last ones |
| `maxValueLength` | 1024 | Longest string sent, in bytes of UTF-8 (masked first, then cut) |
| `maxStackFrames` | 100 | Frames sent per exception, the newest |
| `maxQueue` | 100 | Requests waiting to be sent, and as many waiting for a retry |
| `uncaughtExceptionHandler` | on | Report exceptions nothing caught |
| `shutdownTimeoutMillis` | 2000 | How long shutdown waits to send |

## Examples

[examples](examples) holds real apps, run by their tests against a fake
ingest: a Spring Boot API ([spring-boot-shop](examples/spring-boot-shop)), a
cron job ([nightly-report](examples/nightly-report)) and a Kotlin coroutine
worker ([order-worker](examples/order-worker)).

## Building

```sh
./gradlew build          # tests, Javadoc, formatting (google-java-format, ktlint)
./gradlew spotlessApply  # formats
```

The build runs on JDK 21, which Gradle downloads when it is missing; the
libraries are compiled for Java 8 (the servlet module for Java 11).

## License

MIT.
