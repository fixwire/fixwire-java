package io.fixwire;

/** An event's or a breadcrumb's severity. */
public enum Level {
  /** Details for debugging. */
  DEBUG("debug", 5),
  /** Something worth knowing. */
  INFO("info", 9),
  /** Something that may become a problem. */
  WARNING("warning", 13),
  /** Something failed. */
  ERROR("error", 17),
  /** Something failed and the program cannot go on. */
  FATAL("fatal", 21);

  private final String wire;
  private final int severity;

  Level(String wire, int severity) {
    this.wire = wire;
    this.severity = severity;
  }

  /**
   * The level's name in the protocol.
   *
   * @return such as {@code warning}
   */
  public String wireName() {
    return wire;
  }

  /**
   * OpenTelemetry's severity number of the level.
   *
   * @return the severity number
   */
  public int severityNumber() {
    return severity;
  }
}
