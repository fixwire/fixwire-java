# Nightly report (a cron job)

```sh
FIXWIRE_DSN=https://<key>@<host> ./gradlew :examples:nightly-report:run   # from sdks/java
```

The job builds a report per account. One account (`globex`) has no
invoices: the job logs the failure, carries on with the others, sends a
summary warning and exits 1.

What arrives in Fixwire:

- **Check-ins** for the `nightly-report` monitor: `in_progress` when the
  run starts and `error` when it ends, with its duration. The first
  check-in creates the monitor (every night at 3, Berlin time, 10 minutes'
  margin, 30 minutes at most), so Fixwire also notices a night the job does
  not run at all.
- **The failure**, from the `SEVERE` log record with its exception
  (`NoInvoicesException`), tagged `account: globex`, with the `building the
  report for globex` log line as its last breadcrumb.
- **The summary warning**, without the account's tag: each account had its
  own scope (`Fixwire.withScope`).
- **A trace for the run**, with a span per account; the failure is linked
  to it.

How it is wired, in `NightlyReport.java`:

```java
Fixwire.init(o -> {
  o.setRelease("nightly-report@1.0.0");
  o.setTracesSampleRate(1);
});
Logger.getLogger("").addHandler(new FixwireHandler()); // log records: breadcrumbs, SEVERE ones events
try {
  Fixwire.withMonitor("nightly-report",
      CheckIn.MonitorConfig.crontab("0 3 * * *").timezone("Europe/Berlin").checkInMargin(10).maxRuntime(30),
      NightlyReport::reportAll);
} finally {
  Fixwire.close(5000); // a short-lived program sends what is left before it exits
}
```
