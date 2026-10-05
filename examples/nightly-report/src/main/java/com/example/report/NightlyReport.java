package com.example.report;

import io.fixwire.CheckIn;
import io.fixwire.Fixwire;
import io.fixwire.Level;
import io.fixwire.Span;
import io.fixwire.jul.FixwireHandler;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Logger;

/**
 * A cron job reporting to Fixwire: check-ins tell its monitor when it ran and how it ended, a
 * failing account is logged (and so reported) and the job carries on, and a summary warning goes
 * out at the end.
 *
 * <pre>
 * FIXWIRE_DSN=https://&lt;key&gt;@&lt;host&gt; ./gradlew :examples:nightly-report:run
 * </pre>
 */
public final class NightlyReport {
  private static final Logger LOG = Logger.getLogger(NightlyReport.class.getName());

  static final List<String> ACCOUNTS = Arrays.asList("acme", "globex", "initech");

  /** What an account without invoices gives. */
  static final class NoInvoicesException extends Exception {
    NoInvoicesException(String account) {
      super("no invoices for " + account);
    }
  }

  public static void main(String[] args) {
    System.exit(run(null)); // the DSN comes from FIXWIRE_DSN
  }

  /** Runs the job; 1 when an account failed. */
  static int run(String dsn) {
    Fixwire.init(
        o -> {
          o.setDsn(dsn);
          o.setRelease("nightly-report@1.0.0");
          o.setTracesSampleRate(1);
        });
    // Log records become breadcrumbs, and SEVERE ones events.
    Logger.getLogger("").addHandler(new FixwireHandler());
    try {
      // The first check-in creates the monitor: every night at 3, in Berlin.
      Fixwire.withMonitor(
          "nightly-report",
          CheckIn.MonitorConfig.crontab("0 3 * * *")
              .timezone("Europe/Berlin")
              .checkInMargin(10)
              .maxRuntime(30),
          NightlyReport::reportAll);
      return 0;
    } catch (Exception e) {
      LOG.info("the job failed: " + e.getMessage());
      return 1;
    } finally {
      Fixwire.close(5000); // a short-lived program sends what is left before it exits
    }
  }

  private static Void reportAll() {
    int failed = 0;
    try (Span job = Fixwire.startSpan("nightly-report", "task")) {
      for (String account : ACCOUNTS) {
        try (Span span = Fixwire.startSpan("report " + account, "task")) {
          try {
            report(account);
          } catch (NoInvoicesException e) {
            failed++;
            span.setError(e);
            // One scope per account, so its tag doesn't stay on the next.
            Fixwire.withScope(
                s -> {
                  s.setTag("account", account);
                  LOG.log(
                      java.util.logging.Level.SEVERE, "the report for " + account + " failed", e);
                });
          }
        }
      }
    }
    if (failed > 0) {
      Fixwire.captureMessage(
          "nightly report: " + failed + " of " + ACCOUNTS.size() + " accounts failed",
          Level.WARNING);
      throw new IllegalStateException(failed + " accounts failed");
    }
    return null;
  }

  private static void report(String account) throws NoInvoicesException {
    LOG.info("building the report for " + account);
    if (account.equals("globex")) {
      throw new NoInvoicesException(account);
    }
  }
}
