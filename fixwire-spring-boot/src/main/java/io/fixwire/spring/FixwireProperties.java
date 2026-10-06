package io.fixwire.spring;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The {@code fixwire.*} properties. Without a DSN (and without {@code FIXWIRE_DSN}) nothing is
 * sent.
 */
@ConfigurationProperties("fixwire")
public class FixwireProperties {
  /** The project's DSN, https://<key>@<host>; FIXWIRE_DSN when empty. */
  private String dsn;

  /** The app's version, such as shop@1.4.0; FIXWIRE_RELEASE when empty. */
  private String release;

  /** Where the app runs; FIXWIRE_ENVIRONMENT, else production. */
  private String environment;

  /** The share of errors sent. */
  private double sampleRate = 1;

  /** The share of new traces kept (0: no tracing). */
  private double tracesSampleRate;

  /**
   * Where outgoing requests carry trace headers: URL prefixes (https://api.example.com/v2) or
   * hosts, which match their subdomains too (example.com, example.com:8443).
   */
  private List<String> tracePropagationTargets = new ArrayList<>();

  /** Package prefixes of your code; by default the application's package. */
  private List<String> inAppIncludes = new ArrayList<>();

  /** Send the user's IP address and identifying headers. */
  private boolean sendDefaultPii;

  /** Log what the SDK does to stderr. */
  private boolean debug;

  private final Logging logging = new Logging();

  /** Logback records as breadcrumbs and events. */
  public static class Logging {
    /** Attach Fixwire to Logback's root logger. */
    private boolean enabled = true;

    /** Records at or above it become breadcrumbs. */
    private String breadcrumbLevel = "INFO";

    /** Records at or above it are sent as events. */
    private String eventLevel = "ERROR";

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    public String getBreadcrumbLevel() {
      return breadcrumbLevel;
    }

    public void setBreadcrumbLevel(String breadcrumbLevel) {
      this.breadcrumbLevel = breadcrumbLevel;
    }

    public String getEventLevel() {
      return eventLevel;
    }

    public void setEventLevel(String eventLevel) {
      this.eventLevel = eventLevel;
    }
  }

  public String getDsn() {
    return dsn;
  }

  public void setDsn(String dsn) {
    this.dsn = dsn;
  }

  public String getRelease() {
    return release;
  }

  public void setRelease(String release) {
    this.release = release;
  }

  public String getEnvironment() {
    return environment;
  }

  public void setEnvironment(String environment) {
    this.environment = environment;
  }

  public double getSampleRate() {
    return sampleRate;
  }

  public void setSampleRate(double sampleRate) {
    this.sampleRate = sampleRate;
  }

  public double getTracesSampleRate() {
    return tracesSampleRate;
  }

  public void setTracesSampleRate(double tracesSampleRate) {
    this.tracesSampleRate = tracesSampleRate;
  }

  public List<String> getTracePropagationTargets() {
    return tracePropagationTargets;
  }

  public void setTracePropagationTargets(List<String> tracePropagationTargets) {
    this.tracePropagationTargets = tracePropagationTargets;
  }

  public List<String> getInAppIncludes() {
    return inAppIncludes;
  }

  public void setInAppIncludes(List<String> inAppIncludes) {
    this.inAppIncludes = inAppIncludes;
  }

  public boolean isSendDefaultPii() {
    return sendDefaultPii;
  }

  public void setSendDefaultPii(boolean sendDefaultPii) {
    this.sendDefaultPii = sendDefaultPii;
  }

  public boolean isDebug() {
    return debug;
  }

  public void setDebug(boolean debug) {
    this.debug = debug;
  }

  public Logging getLogging() {
    return logging;
  }
}
