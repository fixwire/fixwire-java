package io.fixwire;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * The SDK's options. Only the DSN is needed; without one (and without {@code FIXWIRE_DSN}) the SDK
 * does nothing. Set them before {@link Fixwire#init}; changes later have no effect.
 */
public final class Options {
  /** Changes an event before it is sent, or drops it by returning null. */
  public interface BeforeSend {
    /**
     * Called for each event.
     *
     * @param event the event, with the scope's details
     * @return the event to send, or null to drop it
     */
    Event execute(Event event);
  }

  /** Changes a breadcrumb before it is kept, or drops it by returning null. */
  public interface BeforeBreadcrumb {
    /**
     * Called for each breadcrumb.
     *
     * @param breadcrumb the breadcrumb
     * @return the breadcrumb to keep, or null to drop it
     */
    Breadcrumb execute(Breadcrumb breadcrumb);
  }

  private String dsn;
  private String release;
  private String environment;
  private String serverName;
  private String serviceName;
  private double sampleRate = 1;
  private double tracesSampleRate;
  private List<String> tracePropagationTargets = new ArrayList<>();
  private BeforeSend beforeSend;
  private BeforeBreadcrumb beforeBreadcrumb;
  private int maxBreadcrumbs = 100;
  private boolean sendDefaultPii;
  private boolean redact = true;
  private List<String> sensitiveKeys;
  private ErrorBudget errorBudget = new ErrorBudget();
  private List<String> inAppIncludes = new ArrayList<>();
  private List<String> inAppExcludes = new ArrayList<>();
  private int maxQueue = 100;
  private int timeoutMillis = 10_000;
  private boolean debug;
  private boolean autoSessionTracking = true;
  private long sessionIntervalMillis = 60_000;
  private boolean uncaughtExceptionHandler = true;
  private long shutdownTimeoutMillis = 2_000;

  /**
   * The project's DSN, {@code https://<key>@<host>}; {@code FIXWIRE_DSN} when not set.
   *
   * @return the DSN
   */
  public String getDsn() {
    return dsn;
  }

  public void setDsn(String dsn) {
    this.dsn = dsn;
  }

  /**
   * The version of the app, such as {@code api@1.4.0} or a commit SHA; {@code FIXWIRE_RELEASE} when
   * not set. Release health needs one.
   *
   * @return the release
   */
  public String getRelease() {
    return release;
  }

  public void setRelease(String release) {
    this.release = release;
  }

  /**
   * Where the app runs; {@code FIXWIRE_ENVIRONMENT}, else {@code production}.
   *
   * @return the environment
   */
  public String getEnvironment() {
    return environment;
  }

  public void setEnvironment(String environment) {
    this.environment = environment;
  }

  /**
   * The machine's name; its host name when not set.
   *
   * @return the server's name
   */
  public String getServerName() {
    return serverName;
  }

  public void setServerName(String serverName) {
    this.serverName = serverName;
  }

  /**
   * The service's name: {@code OTEL_SERVICE_NAME} when not set, else the name in a {@code
   * name@version} release.
   *
   * @return the service's name
   */
  public String getServiceName() {
    return serviceName;
  }

  public void setServiceName(String serviceName) {
    this.serviceName = serviceName;
  }

  /**
   * The share of errors and messages sent (default 1).
   *
   * @return between 0 and 1
   */
  public double getSampleRate() {
    return sampleRate;
  }

  public void setSampleRate(double sampleRate) {
    this.sampleRate = sampleRate;
  }

  /**
   * The share of new traces kept (default 0: no tracing). Traces continued from a caller follow its
   * decision.
   *
   * @return between 0 and 1
   */
  public double getTracesSampleRate() {
    return tracesSampleRate;
  }

  public void setTracesSampleRate(double tracesSampleRate) {
    this.tracesSampleRate = tracesSampleRate;
  }

  /**
   * The URLs outgoing requests carry trace headers to: those holding one of these strings (default
   * none, so that no other service sees them).
   *
   * @return the targets
   */
  public List<String> getTracePropagationTargets() {
    return tracePropagationTargets;
  }

  public void setTracePropagationTargets(List<String> tracePropagationTargets) {
    this.tracePropagationTargets =
        tracePropagationTargets == null ? new ArrayList<String>() : tracePropagationTargets;
  }

  public BeforeSend getBeforeSend() {
    return beforeSend;
  }

  public void setBeforeSend(BeforeSend beforeSend) {
    this.beforeSend = beforeSend;
  }

  public BeforeBreadcrumb getBeforeBreadcrumb() {
    return beforeBreadcrumb;
  }

  public void setBeforeBreadcrumb(BeforeBreadcrumb beforeBreadcrumb) {
    this.beforeBreadcrumb = beforeBreadcrumb;
  }

  /**
   * The breadcrumbs kept per scope (default 100; 0 keeps none).
   *
   * @return the number
   */
  public int getMaxBreadcrumbs() {
    return maxBreadcrumbs;
  }

  public void setMaxBreadcrumbs(int maxBreadcrumbs) {
    this.maxBreadcrumbs = maxBreadcrumbs;
  }

  /**
   * Whether to send the user's IP address and request headers that may identify them (off by
   * default).
   *
   * @return whether personal data is sent
   */
  public boolean isSendDefaultPii() {
    return sendDefaultPii;
  }

  public void setSendDefaultPii(boolean sendDefaultPii) {
    this.sendDefaultPii = sendDefaultPii;
  }

  /**
   * Whether secrets and personal data are masked on the device, with the same rules as the Fixwire
   * server (on by default).
   *
   * @return whether redaction is on
   */
  public boolean isRedact() {
    return redact;
  }

  public void setRedact(boolean redact) {
    this.redact = redact;
  }

  /**
   * The key fragments (password, token, cookie, …) whose values are filtered whole; null for the
   * server's.
   *
   * @return the fragments, or null
   */
  public List<String> getSensitiveKeys() {
    return sensitiveKeys;
  }

  public void setSensitiveKeys(List<String> sensitiveKeys) {
    this.sensitiveKeys = sensitiveKeys;
  }

  /**
   * Bounds the events sent per issue and per minute, so that a crash loop costs a few events and a
   * count.
   *
   * @return the budget
   */
  public ErrorBudget getErrorBudget() {
    return errorBudget;
  }

  public void setErrorBudget(ErrorBudget errorBudget) {
    this.errorBudget = errorBudget == null ? new ErrorBudget() : errorBudget;
  }

  /**
   * Package prefixes of the app's code. Frames of the JDK, Kotlin and well-known libraries are not
   * the app's; others are, unless {@link #getInAppExcludes} names them.
   *
   * @return the prefixes, such as {@code com.example.shop}
   */
  public List<String> getInAppIncludes() {
    return inAppIncludes;
  }

  public void setInAppIncludes(List<String> inAppIncludes) {
    this.inAppIncludes = inAppIncludes == null ? new ArrayList<String>() : inAppIncludes;
  }

  public List<String> getInAppExcludes() {
    return inAppExcludes;
  }

  public void setInAppExcludes(List<String> inAppExcludes) {
    this.inAppExcludes = inAppExcludes == null ? new ArrayList<String>() : inAppExcludes;
  }

  /**
   * The requests waiting to be sent (default 100); past it, new ones are dropped.
   *
   * @return the queue's size
   */
  public int getMaxQueue() {
    return maxQueue;
  }

  public void setMaxQueue(int maxQueue) {
    this.maxQueue = maxQueue;
  }

  /**
   * The connect and read timeout of a request to Fixwire (default 10 s).
   *
   * @return milliseconds
   */
  public int getTimeoutMillis() {
    return timeoutMillis;
  }

  public void setTimeoutMillis(int timeoutMillis) {
    this.timeoutMillis = timeoutMillis;
  }

  /**
   * Whether the SDK logs what it does to stderr.
   *
   * @return whether debug output is on
   */
  public boolean isDebug() {
    return debug;
  }

  public void setDebug(boolean debug) {
    this.debug = debug;
  }

  /**
   * Whether requests are counted for release health (on; needs a release).
   *
   * @return whether sessions are sent
   */
  public boolean isAutoSessionTracking() {
    return autoSessionTracking;
  }

  public void setAutoSessionTracking(boolean autoSessionTracking) {
    this.autoSessionTracking = autoSessionTracking;
  }

  public long getSessionIntervalMillis() {
    return sessionIntervalMillis;
  }

  public void setSessionIntervalMillis(long sessionIntervalMillis) {
    this.sessionIntervalMillis = sessionIntervalMillis;
  }

  /**
   * Whether {@link Fixwire#init} reports exceptions no code caught (on). The handler that was there
   * before still runs.
   *
   * @return whether the handler is installed
   */
  public boolean isUncaughtExceptionHandler() {
    return uncaughtExceptionHandler;
  }

  public void setUncaughtExceptionHandler(boolean uncaughtExceptionHandler) {
    this.uncaughtExceptionHandler = uncaughtExceptionHandler;
  }

  /**
   * How long the JVM's shutdown waits for what is left to be sent (default 2 s; 0 sends nothing at
   * shutdown).
   *
   * @return milliseconds
   */
  public long getShutdownTimeoutMillis() {
    return shutdownTimeoutMillis;
  }

  public void setShutdownTimeoutMillis(long shutdownTimeoutMillis) {
    this.shutdownTimeoutMillis = shutdownTimeoutMillis;
  }

  /** Fills in what is not set, from the environment. */
  void applyDefaults() {
    if (empty(dsn)) {
      dsn = System.getenv("FIXWIRE_DSN");
    }
    if (empty(release)) {
      release = System.getenv("FIXWIRE_RELEASE");
    }
    if (empty(environment)) {
      environment = System.getenv("FIXWIRE_ENVIRONMENT");
    }
    if (empty(environment)) {
      environment = "production";
    }
    if (empty(serverName)) {
      serverName = hostName();
    }
    if (empty(serviceName)) {
      serviceName = System.getenv("OTEL_SERVICE_NAME");
    }
    if (empty(serviceName) && release != null && release.indexOf('@') > 0) {
      serviceName = release.substring(0, release.indexOf('@')); // "api" of "api@1.4.0"
    }
    if (!(sampleRate > 0 && sampleRate <= 1)) {
      sampleRate = 1;
    }
    if (!(tracesSampleRate >= 0)) {
      tracesSampleRate = 0;
    }
    tracesSampleRate = Math.min(tracesSampleRate, 1);
    maxBreadcrumbs = Math.max(maxBreadcrumbs, 0);
    if (maxQueue <= 0) {
      maxQueue = 100;
    }
    if (timeoutMillis <= 0) {
      timeoutMillis = 10_000;
    }
    if (sessionIntervalMillis <= 0) {
      sessionIntervalMillis = 60_000;
    }
  }

  boolean sessionsOn() {
    return autoSessionTracking && !empty(release);
  }

  static boolean empty(String s) {
    return s == null || s.trim().isEmpty();
  }

  /** The host's name, without a DNS lookup where the system says it. */
  private static String hostName() {
    String name = System.getenv("HOSTNAME");
    if (empty(name)) {
      try {
        name =
            new String(Files.readAllBytes(Paths.get("/etc/hostname")), StandardCharsets.UTF_8)
                .trim();
      } catch (Exception | LinkageError e) {
        name = null;
      }
    }
    if (empty(name)) {
      name = System.getenv("COMPUTERNAME");
    }
    if (empty(name)) {
      try {
        name = InetAddress.getLocalHost().getHostName();
      } catch (Exception e) {
        name = null;
      }
    }
    return name == null ? null : name.trim();
  }
}
