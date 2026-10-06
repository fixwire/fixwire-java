<div align="center">

_Bugs reach production. Fixwire finds them first: errors, traces, logs and
AI agent runs in one place, an AI debugger on every plan, and your data
kept in Europe._

[![Discord](https://img.shields.io/badge/Discord-join%20us-5865F2?logo=discord&logoColor=white)](https://fixwire.io/discord)
[![Slack](https://img.shields.io/badge/Slack-community-4A154B?logo=slack&logoColor=white)](https://fixwire.io/slack)
[![X](https://img.shields.io/badge/X-follow%20us-000000?logo=x&logoColor=white)](https://fixwire.io/x)
[![Release](https://img.shields.io/github/v/release/fixwire/fixwire-java?label=release)](https://github.com/fixwire/fixwire-java/releases)
[![Java](https://img.shields.io/badge/java-8%20%7C%2011%20%7C%2017%20%7C%2021%20%7C%2025-blue)](https://github.com/fixwire/fixwire-java/actions/workflows/ci.yml)
[![CI](https://github.com/fixwire/fixwire-java/actions/workflows/ci.yml/badge.svg)](https://github.com/fixwire/fixwire-java/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](https://github.com/fixwire/fixwire-java/blob/main/LICENSE)

<br/>

</div>

# Fixwire SDK for Java

Welcome to the official Java SDK for **[Fixwire](https://fixwire.io)**. It
captures errors and crashes with their causes, traces, log records, release
health, cron monitors and feedback, from Java and Kotlin alike.

## 📦 Getting started

### Prerequisites

- A Fixwire account and a project ([fixwire.io](https://fixwire.io)).
- Java 8 or newer for the core library. The core depends on nothing.
- Some integrations need more: Java 11+ for the servlet, Logback and
  `java.net.http` modules; Java 17+ and Spring Boot 4.0+ for the Spring Boot
  starter.
- Kotlin works with the same API; `fixwire-kotlin` adds coroutine support.

### Installation

Gradle (Kotlin DSL):

```kotlin
// build.gradle.kts
dependencies {
    implementation("io.fixwire:fixwire:0.1.0")
}
```

Maven:

```xml
<dependency>
  <groupId>io.fixwire</groupId>
  <artifactId>fixwire</artifactId>
  <version>0.1.0</version>
</dependency>
```

Spring Boot apps add the starter instead (see
[Spring Boot](https://github.com/fixwire/fixwire-java#spring-boot)); the
other integrations are separate modules too (see
[Integrations](https://github.com/fixwire/fixwire-java#-integrations)).

### Basic configuration

Call `init` once, as early as you can:

```java
import io.fixwire.Fixwire;

public final class Main {
  public static void main(String[] args) {
    Fixwire.init(o -> {
      o.setDsn("https://fw_pk_live_…@ingest.eu.fixwire.io");
      o.setRelease("api@1.4.0");
      o.setEnvironment("production");
      o.setTracesSampleRate(0.2);
      // o.setSendDefaultPii(true); // also send the user's IP address and identifying headers
      // o.setRedact(false);        // turn off on-device masking of secrets and personal data
    });
  }
}
```

The same in Kotlin:

```kotlin
Fixwire.init {
    it.dsn = "https://fw_pk_live_…@ingest.eu.fixwire.io"
    it.release = "api@1.4.0"
    it.environment = "production"
    it.tracesSampleRate = 0.2
}
```

The DSN is your project's publishable key and the ingest host,
`https://<publishable key>@<host>`. Without one the SDK reads `FIXWIRE_DSN`;
without either it does nothing, so the same code runs in tests.
`FIXWIRE_RELEASE` and `FIXWIRE_ENVIRONMENT` work the same way. `init` never
throws: a malformed DSN is reported on stderr and the SDK stays off, so a
typo can't stop the app from starting.

`init` also reports exceptions no code caught (the handler that was there
before still runs), and the JVM's shutdown waits up to two seconds for what
is left to be sent. A short-lived program that exits on its own calls
`Fixwire.close(2000)` (or `Fixwire.flush(2000)`) first.

### Quick usage example

```java
Fixwire.captureMessage("Hello Fixwire!"); // a message, with the scope's tags and breadcrumbs

try {
  charge(order);
} catch (PaymentException e) {
  Fixwire.captureException(e); // an error with its causes and their stacks
}
```

An exception is sent with its causes and their stacks. Frames of the JDK,
Kotlin and well-known libraries are marked as not yours; name your packages
with `setInAppIncludes` when the defaults guess wrong.

**Context.** Users, tags and breadcrumbs go with every event that follows:

```java
Fixwire.configureScope(s -> {
  s.setUser(new User("user-1"));
  s.setTag("plan", "team");
});
Fixwire.addBreadcrumb("cart", "checkout started");
Fixwire.captureMessage("disk usage above 90%", Level.WARNING);
```

**Requests and threads.** `Fixwire`'s methods use the current thread's hub.
A request (or a job) gets its own copy, so what it sets stays with it:

```java
try (Hub.Binding b = Hub.current().copy().bind()) {
  handle(request);
}
executor.submit(Fixwire.wrap(() -> work())); // the task runs with a copy of the hub
```

The servlet filter and the Kotlin coroutine context do this for you.

**Spans.** With a traces sample rate above 0:

```java
try (Span span = Fixwire.startSpan("SELECT carts", "db.query")) {
  span.setAttribute("db.rows", loadCarts());
}
```

A span without a parent in the process is sent with the spans under it when
it closes. `Fixwire.spanBuilder(name).continueTrace(traceparent, tracestate,
baggage).start()` continues a caller's trace, and its sampling decision
holds.

**Cron monitors.** A run of a scheduled job checks in when it starts and
when it ends; the monitor notices runs that fail, take too long or never
happen:

```java
int rows = Fixwire.withMonitor(
    "nightly-report",
    CheckIn.MonitorConfig.crontab("0 3 * * *").timezone("Europe/Berlin"),
    () -> buildReport());
```

**Feedback.** Rate an AI answer, or say what went wrong with a crash:

```java
Feedback f = new Feedback("Refunded the wrong order");
f.setScore(-1);
f.setTraceId(runTraceId);
Fixwire.captureFeedback(f); // a negative score opens a user_feedback issue for the agent
```

## ✨ Why Fixwire

- **Redaction on the device.** Secrets and personal data are masked before
  they leave the JVM, with the same rules as the Fixwire server.
- **Crash loops don't eat your quota.** A crash loop costs a few events and
  a count, not your quota (see
  [Error budget](https://github.com/fixwire/fixwire-java#error-budget)).
- **It never gets in your app's way.** `init` never throws, and nothing the
  SDK does throws into your code. Captures never block: one daemon thread
  sends from a bounded queue, retries with backoff and honours rate limits,
  pausing only the kind of data a limit names. Strings, stacks, values and
  requests all have strict limits.
- **OpenTelemetry-native.** It speaks the Fixwire protocol
  (OpenTelemetry's OTLP/HTTP plus a few small JSON endpoints): errors,
  messages and spans travel as OTLP JSON, with structured stack traces,
  breadcrumbs and redaction on top.
- **Trace headers only where you allow.** Outgoing requests carry trace
  headers only to the targets you name; by default, none.
- **Your data stays in Europe.** Fixwire keeps what the SDK sends in
  Europe.
- **No dependencies.** The core runs on Java 8 and newer with nothing else
  on the classpath; each integration is its own small module.

## 🧩 Integrations

| Integration | What it does | How to use |
| --- | --- | --- |
| Spring Boot (`fixwire-spring-boot`) | Sets everything below up from `fixwire.*` properties (Spring Boot 4.0+, Java 17+) | [Spring Boot](https://github.com/fixwire/fixwire-java#spring-boot) |
| Servlet (`fixwire-servlet`) | Each request: its own scope, crashes, release health, a server span (Tomcat, Jetty, Undertow; Jakarta Servlet, Java 11+) | [Servlets](https://github.com/fixwire/fixwire-java#servlets) |
| Kotlin coroutines (`fixwire-kotlin`) | A coroutine keeps its hub and span on whichever thread runs it | [Kotlin coroutines](https://github.com/fixwire/fixwire-java#kotlin-coroutines) |
| OkHttp (`fixwire-okhttp`) | Requests as client spans and breadcrumbs, trace headers to your services | [HTTP clients](https://github.com/fixwire/fixwire-java#http-clients) |
| `java.net.http` (`fixwire-httpclient`) | Requests as client spans and breadcrumbs, trace headers to your services (Java 11+) | [HTTP clients](https://github.com/fixwire/fixwire-java#http-clients) |
| Logback (`fixwire-logback`) | Records as breadcrumbs and events, with the MDC (Java 11+) | [Logs](https://github.com/fixwire/fixwire-java#logs) |
| `java.util.logging` (in `fixwire`) | Records as breadcrumbs and events | [Logs](https://github.com/fixwire/fixwire-java#logs) |
| Uncaught exceptions (in `fixwire`) | Reported as crashes; the handler that was there before still runs | On by default (`uncaughtExceptionHandler`) |

Every module has the version of the core, `0.1.0`. Modules that hook into a
library (OkHttp, Logback, the servlet API, kotlinx-coroutines) use the one
your app already has.

### Spring Boot

Gradle:

```kotlin
implementation("io.fixwire:fixwire-spring-boot:0.1.0")
```

Maven:

```xml
<dependency>
  <groupId>io.fixwire</groupId>
  <artifactId>fixwire-spring-boot</artifactId>
  <version>0.1.0</version>
</dependency>
```

Then, in `application.properties`:

```properties
fixwire.release=shop@1.4.0
fixwire.traces-sample-rate=0.2
fixwire.trace-propagation-targets=https://inventory.internal
```

That's all: the starter sets Fixwire up when the app starts (the DSN from
`fixwire.dsn` or `FIXWIRE_DSN`), reports each request (its scope, crashes,
release health, a server span named after the route), traces the requests
of the `RestClient.Builder` and `RestTemplateBuilder` Spring Boot gives out,
sends Logback records, marks your application's package as your code, and
flushes when the app stops.

The properties are `fixwire.dsn`, `release`, `environment`, `sample-rate`,
`traces-sample-rate`, `trace-propagation-targets`, `in-app-includes`,
`send-default-pii` and `debug`. `fixwire.logging.breadcrumb-level` (default
`INFO`) and `fixwire.logging.event-level` (default `ERROR`) set the Logback
levels, and `fixwire.logging.enabled=false` turns that part off.

### Servlets

Gradle:

```kotlin
implementation("io.fixwire:fixwire-servlet:0.1.0")
```

Maven:

```xml
<dependency>
  <groupId>io.fixwire</groupId>
  <artifactId>fixwire-servlet</artifactId>
  <version>0.1.0</version>
</dependency>
```

Register `FixwireFilter` first, for every request:

```java
FilterRegistration.Dynamic f = servletContext.addFilter("fixwire", new FixwireFilter());
f.addMappingForUrlPatterns(EnumSet.allOf(DispatcherType.class), false, "/*");
```

Each request gets its own hub, so what it sets stays with it. Exceptions
that escape the app are sent as crashes, each request is counted for release
health and, with tracing on, it becomes a server span that continues the
caller's trace, named after the route (Spring MVC's pattern, or the servlet
mapping).

### Kotlin coroutines

Gradle:

```kotlin
implementation("io.fixwire:fixwire-kotlin:0.1.0")
```

Maven:

```xml
<dependency>
  <groupId>io.fixwire</groupId>
  <artifactId>fixwire-kotlin</artifactId>
  <version>0.1.0</version>
</dependency>
```

`launch(FixwireContext()) { … }` keeps a coroutine's hub on whichever thread
runs it; `withSpan(name, op) { … }` times a suspending block:

```kotlin
launch(FixwireContext()) {
    Fixwire.setTag("order", order.id)
    withSpan("charge", "payment") {
        charge(order) // errors captured here carry the tag, on any thread
    }
}
```

### HTTP clients

Gradle:

```kotlin
implementation("io.fixwire:fixwire-okhttp:0.1.0")     // OkHttp
implementation("io.fixwire:fixwire-httpclient:0.1.0") // java.net.http
```

Maven:

```xml
<dependency>
  <groupId>io.fixwire</groupId>
  <artifactId>fixwire-okhttp</artifactId>
  <version>0.1.0</version>
</dependency>
<dependency>
  <groupId>io.fixwire</groupId>
  <artifactId>fixwire-httpclient</artifactId>
  <version>0.1.0</version>
</dependency>
```

Outgoing requests become client spans of the current trace, with an `http`
breadcrumb each:

```java
OkHttpClient okhttp = new OkHttpClient.Builder().addInterceptor(new FixwireInterceptor()).build();
HttpClient jdk = FixwireHttpClient.wrap(HttpClient.newHttpClient());
```

Other clients can use `OutgoingRequest` from the core the same way:

```java
OutgoingRequest out = OutgoingRequest.start("GET", url, builder::header);
try {
  Response res = send(builder.build());
  out.end(res.code());
  return res;
} catch (IOException e) {
  out.fail(e);
  throw e;
}
```

### Logs

For Logback, Gradle:

```kotlin
implementation("io.fixwire:fixwire-logback:0.1.0")
```

Maven:

```xml
<dependency>
  <groupId>io.fixwire</groupId>
  <artifactId>fixwire-logback</artifactId>
  <version>0.1.0</version>
</dependency>
```

```xml
<!-- logback.xml (the Spring Boot starter does this by itself) -->
<appender name="FIXWIRE" class="io.fixwire.logback.FixwireAppender">
  <breadcrumbLevel>INFO</breadcrumbLevel>
  <eventLevel>ERROR</eventLevel>
</appender>
<root level="INFO"><appender-ref ref="FIXWIRE"/></root>
```

`java.util.logging` needs nothing more than the core:

```java
Logger.getLogger("").addHandler(new FixwireHandler());
```

`INFO` and above become breadcrumbs; `SEVERE` (`ERROR` in Logback) and above
are sent as events, as the record's exception when it has one (once, if the
app captured it already). Logback's MDC goes with them.

## ⚙️ Configuration

Each option has a setter on `Options` (`setDsn`) and, in Kotlin, a property
(`it.dsn`). Set them in `init`; later changes have no effect.

| Option | Default | What it does |
| --- | --- | --- |
| `dsn` | `FIXWIRE_DSN` | Where to send; nothing is sent without one |
| `release` | `FIXWIRE_RELEASE` | Your app's version; release health needs one |
| `environment` | `FIXWIRE_ENVIRONMENT`, else `production` | Where the app runs |
| `serviceName` | `OTEL_SERVICE_NAME`, else `api` of `api@1.4.0` | The service's name |
| `serverName` | the host's name | The machine the app runs on |
| `sampleRate` | 1 | Share of errors and messages sent |
| `tracesSampleRate` | 0 | Share of new traces kept (0: no tracing) |
| `tracePropagationTargets` | none | URL prefixes and hosts that receive trace headers |
| `beforeSend`, `beforeBreadcrumb` | none | Change or drop events and breadcrumbs |
| `sendDefaultPii` | off | Send the user's IP address and identifying headers |
| `redact`, `sensitiveKeys` | on, the server's keys | On-device masking |
| `errorBudget` | 10 per issue, then 1 a minute; 600 a minute | Bounds what a crash loop sends |
| `inAppIncludes`, `inAppExcludes` | all but the JDK's and known libraries' | Which frames are your code |
| `maxBreadcrumbs` | 100 | Breadcrumbs kept, the last ones |
| `maxValueLength` | 1024 | Longest string sent, in bytes of UTF-8 (masked first, then cut) |
| `maxStackFrames` | 100 | Frames sent per exception, the newest |
| `maxQueue` | 100 | Requests waiting to be sent, and as many waiting for a retry |
| `timeoutMillis` | 10000 | Connect and read timeout of a request to Fixwire |
| `autoSessionTracking` | on | Count requests for release health (needs a release) |
| `sessionIntervalMillis` | 60000 | How often release health counts are sent |
| `uncaughtExceptionHandler` | on | Report exceptions nothing caught |
| `shutdownTimeoutMillis` | 2000 | How long shutdown waits to send (0: not at all) |
| `debug` | off | Log what the SDK does to stderr |

### Sampling

`sampleRate` is the share of errors and messages sent. `tracesSampleRate` is
the share of new traces kept; a trace continued from a caller follows the
caller's decision.

### Trace propagation targets

Trace headers go only to `tracePropagationTargets`, which compare a URL
without its user info, query and fragment:

```java
o.setTracePropagationTargets(List.of(
    "https://api.example.com/v2", // URLs that start with it
    "example.com",                // that host and its subdomains (not badexample.com)
    "internal:8443"));            // a host on that port
```

A target that starts with `/` names a path of a page's own origin, which a
server doesn't have, so it matches nothing here.

An incoming `traceparent` is used only when it is well formed (W3C: version
`00`, lower-case hex). A caller's `tracestate` over 512 bytes, or `baggage`
over 8,192 bytes, or either with a control character other than tab, is
dropped rather than passed on.

### beforeSend and beforeBreadcrumb

Both see each item before it is kept, and drop it by returning `null`:

```java
o.setBeforeSend(event -> {
  if (event.getThrowable() instanceof CancellationException) {
    return null; // not worth an issue
  }
  event.getTags().put("region", "eu-west");
  return event;
});
o.setBeforeBreadcrumb(b -> "sql".equals(b.getCategory()) ? null : b);
```

If a callback throws, the event is sent as it was and the breadcrumb is
kept.

### Redaction

Secrets and personal data (emails, card numbers, tokens, private keys,
values of keys such as `password`) are masked on the device, with the same
rules as the Fixwire server: messages, attributes, span names and status
messages, breadcrumbs, feedback, URLs and their queries, and map keys.
`setSensitiveKeys` replaces the server's list of key fragments whose values
are filtered whole; `setRedact(false)` turns masking off.

Strings are masked first, then cut to `maxValueLength`, so a secret the cut
goes through is still masked. Your own configuration (release, environment,
service and server name, monitor slugs) is cut but sent as given.

### Error budget

Each issue may send a burst of events, then so many a minute, within a
budget for all issues. Occurrences held back are counted on the issue's
next event.

```java
o.getErrorBudget().setPerIssueBurst(10);
o.getErrorBudget().setPerIssuePerMinute(1);
o.getErrorBudget().setPerMinute(600);
```

## 🧪 Examples

Real apps, each run by its tests against a fake ingest in CI:

- [spring-boot-shop](https://github.com/fixwire/fixwire-java/tree/main/examples/spring-boot-shop):
  a Spring Boot 4 API set up from `application.properties`, with
  per-request users, crashes, traces and release health.
- [nightly-report](https://github.com/fixwire/fixwire-java/tree/main/examples/nightly-report):
  a cron job in plain Java, with check-ins, `java.util.logging` and a trace
  for the run.
- [order-worker](https://github.com/fixwire/fixwire-java/tree/main/examples/order-worker):
  Kotlin coroutine workers, each order with its own hub and trace.

Run one with your DSN:

```sh
FIXWIRE_DSN=https://<key>@<host> ./gradlew :examples:nightly-report:run
```

## 📚 Documentation

The full guide lives in this README and the examples.

- [Configuration](https://github.com/fixwire/fixwire-java#%EF%B8%8F-configuration)
- [Examples](https://github.com/fixwire/fixwire-java/tree/main/examples)
- [Changelog](https://github.com/fixwire/fixwire-java/blob/main/CHANGELOG.md)
- [Security policy](https://github.com/fixwire/fixwire-java/blob/main/SECURITY.md)
- [Contributing guide](https://github.com/fixwire/fixwire-java/blob/main/CONTRIBUTING.md)

## 🚧 Coming from another error tracker?

The API follows the shape most error-tracking SDKs share: `init`,
`captureException` and `captureMessage`, users, tags, breadcrumbs and
spans. Moving over is mostly a change of dependency and DSN. Two things
are Java-shaped: options are set in a lambda (`Fixwire.init(o -> …)`, or
`it.` properties in Kotlin), and each thread has its own hub, which
`Fixwire.wrap` carries to executor tasks.

## 🙌 Want to contribute?

We'd love your help, whether it's a bug report, a fix or a new
integration. Start with the
[contributing guide](https://github.com/fixwire/fixwire-java/blob/main/CONTRIBUTING.md),
browse the [open issues](https://github.com/fixwire/fixwire-java/issues), or
pick one of the
[good first issues](https://github.com/fixwire/fixwire-java/issues?q=is%3Aopen+label%3A%22good+first+issue%22).

`./gradlew build` runs the tests, Javadoc and formatting checks
(google-java-format, ktlint), and `./gradlew spotlessApply` formats. The
build runs on JDK 21, which Gradle downloads when it is missing; the
libraries are compiled for Java 8 (the servlet, Logback and `java.net.http`
modules for Java 11).

## 🛟 Need help?

- Questions: ask on [Discord](https://fixwire.io/discord) or
  [Slack](https://fixwire.io/slack).
- Bugs: open a [GitHub issue](https://github.com/fixwire/fixwire-java/issues).
- Found a security issue? Please don't open an issue; follow the
  [security policy](https://github.com/fixwire/fixwire-java/blob/main/SECURITY.md).

## 🔗 Resources

- [Website](https://fixwire.io)
- [Pricing](https://fixwire.io/pricing)
- [Discord](https://fixwire.io/discord)
- [Slack](https://fixwire.io/slack)
- [X](https://fixwire.io/x)
- [Changelog](https://github.com/fixwire/fixwire-java/blob/main/CHANGELOG.md)
- [Examples](https://github.com/fixwire/fixwire-java/tree/main/examples)
- [Security policy](https://github.com/fixwire/fixwire-java/blob/main/SECURITY.md)

## 📃 License

The SDK is open source under the MIT license; see
[LICENSE](https://github.com/fixwire/fixwire-java/blob/main/LICENSE).

## 😘 Contributors

Thanks to everyone who helps make Fixwire better!

<a href="https://github.com/fixwire/fixwire-java/graphs/contributors"><img src="https://contrib.rocks/image?repo=fixwire/fixwire-java" alt="Contributors" /></a>
