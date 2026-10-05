package io.fixwire;

/**
 * Bounds the errors and messages sent, so that a crash loop costs a few events and a count, not the
 * quota. Each issue may send a burst, then so many a minute, within a budget for all of them;
 * occurrences held back are counted on the issue's next event.
 */
public final class ErrorBudget {
  private int perIssueBurst = 10;
  private double perIssuePerMinute = 1;
  private double perMinute = 600;
  private boolean enabled = true;

  /**
   * Events of one issue sent at once (default 10).
   *
   * @return the burst
   */
  public int getPerIssueBurst() {
    return perIssueBurst;
  }

  public void setPerIssueBurst(int perIssueBurst) {
    this.perIssueBurst = perIssueBurst;
  }

  /**
   * Events of one issue sent a minute after its burst (default 1).
   *
   * @return the rate
   */
  public double getPerIssuePerMinute() {
    return perIssuePerMinute;
  }

  public void setPerIssuePerMinute(double perIssuePerMinute) {
    this.perIssuePerMinute = perIssuePerMinute;
  }

  /**
   * Events a minute across issues (default 600).
   *
   * @return the rate
   */
  public double getPerMinute() {
    return perMinute;
  }

  public void setPerMinute(double perMinute) {
    this.perMinute = perMinute;
  }

  /**
   * Whether the budget applies (on); off sends every event.
   *
   * @return whether it is on
   */
  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }
}
