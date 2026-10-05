package io.fixwire;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.LinkedHashMap;
import java.util.Map;

/** A run of a scheduled job, reported to its monitor. */
public final class CheckIn {
  /** How a run is going. */
  public enum Status {
    /** The run started. */
    IN_PROGRESS("in_progress"),
    /** The run ended well. */
    OK("ok"),
    /** The run failed. */
    ERROR("error");

    final String wire;

    Status(String wire) {
      this.wire = wire;
    }
  }

  private String monitor;
  private Status status = Status.OK;
  private String id;
  private long durationMillis;
  private MonitorConfig config;

  /**
   * A check-in.
   *
   * @param monitor the monitor's slug, such as {@code nightly-report}
   * @param status how the run is going
   */
  public CheckIn(String monitor, Status status) {
    this.monitor = monitor;
    this.status = status == null ? Status.OK : status;
  }

  public String getMonitor() {
    return monitor;
  }

  public void setMonitor(String monitor) {
    this.monitor = monitor;
  }

  public Status getStatus() {
    return status;
  }

  public void setStatus(Status status) {
    this.status = status == null ? Status.OK : status;
  }

  /**
   * Ties the end of a run to its start: the id the start returned. Made when null.
   *
   * @return the id
   */
  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public long getDurationMillis() {
    return durationMillis;
  }

  public void setDurationMillis(long durationMillis) {
    this.durationMillis = durationMillis;
  }

  /**
   * Creates or updates the monitor.
   *
   * @return the monitor's settings, or null
   */
  public MonitorConfig getConfig() {
    return config;
  }

  public void setConfig(MonitorConfig config) {
    this.config = config;
  }

  static String pathSegment(String monitor) {
    try {
      return URLEncoder.encode(monitor, "UTF-8").replace("+", "%20");
    } catch (UnsupportedEncodingException e) {
      throw new IllegalStateException(e); // every JVM has UTF-8
    }
  }

  /** When a job runs, and how late or long it may be. */
  public static final class MonitorConfig {
    private final String scheduleType;
    private final Object scheduleValue;
    private final String scheduleUnit;
    private int checkInMarginMinutes;
    private int maxRuntimeMinutes;
    private String timezone;

    private MonitorConfig(String type, Object value, String unit) {
      this.scheduleType = type;
      this.scheduleValue = value;
      this.scheduleUnit = unit;
    }

    /**
     * A job that runs on a crontab.
     *
     * @param crontab such as {@code 0 3 * * *}
     * @return the settings
     */
    public static MonitorConfig crontab(String crontab) {
      return new MonitorConfig("crontab", crontab, null);
    }

    /**
     * A job that runs every so many units.
     *
     * @param every how many units
     * @param unit {@code minute}, {@code hour}, {@code day}, {@code week}, {@code month} or {@code
     *     year}
     * @return the settings
     */
    public static MonitorConfig interval(int every, String unit) {
      return new MonitorConfig("interval", every, unit);
    }

    /**
     * Sets the minutes a check-in may be late.
     *
     * @param minutes the margin
     * @return these settings
     */
    public MonitorConfig checkInMargin(int minutes) {
      this.checkInMarginMinutes = minutes;
      return this;
    }

    /**
     * Sets the minutes a run may take.
     *
     * @param minutes the longest run
     * @return these settings
     */
    public MonitorConfig maxRuntime(int minutes) {
      this.maxRuntimeMinutes = minutes;
      return this;
    }

    /**
     * Sets the schedule's time zone.
     *
     * @param timezone such as {@code Europe/Berlin}
     * @return these settings
     */
    public MonitorConfig timezone(String timezone) {
      this.timezone = timezone;
      return this;
    }

    Map<String, Object> toMap() {
      Map<String, Object> schedule = new LinkedHashMap<>();
      schedule.put("type", scheduleType);
      schedule.put("value", scheduleValue);
      if (scheduleUnit != null) {
        schedule.put("unit", scheduleUnit);
      }
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("schedule", schedule);
      if (checkInMarginMinutes > 0) {
        m.put("checkin_margin", checkInMarginMinutes);
      }
      if (maxRuntimeMinutes > 0) {
        m.put("max_runtime", maxRuntimeMinutes);
      }
      if (timezone != null) {
        m.put("timezone", timezone);
      }
      return m;
    }
  }
}
