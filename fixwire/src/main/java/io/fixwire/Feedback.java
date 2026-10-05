package io.fixwire;

/**
 * What someone said about an error or an AI answer: a message, a score from -1 (bad) to 1 (good),
 * or both. A negative score on a trace opens a {@code user_feedback} issue for the agent run.
 */
public final class Feedback {
  private String message;
  private double score;
  private String traceId;
  private String eventId;
  private String name;
  private String email;
  private String url;
  private String source;

  /** Empty feedback. */
  public Feedback() {}

  /**
   * Feedback with a message.
   *
   * @param message what they said
   */
  public Feedback(String message) {
    this.message = message;
  }

  public String getMessage() {
    return message;
  }

  public void setMessage(String message) {
    this.message = message;
  }

  /**
   * From -1 (bad) to 1 (good); 0 for none.
   *
   * @return the score
   */
  public double getScore() {
    return score;
  }

  public void setScore(double score) {
    this.score = score;
  }

  /**
   * The trace or agent run it is about; by default, the current span's.
   *
   * @return the trace id
   */
  public String getTraceId() {
    return traceId;
  }

  public void setTraceId(String traceId) {
    this.traceId = traceId;
  }

  /**
   * The error it is about, such as {@link Fixwire#lastEventId}.
   *
   * @return the event id
   */
  public String getEventId() {
    return eventId;
  }

  public void setEventId(String eventId) {
    this.eventId = eventId;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getEmail() {
    return email;
  }

  public void setEmail(String email) {
    this.email = email;
  }

  public String getUrl() {
    return url;
  }

  public void setUrl(String url) {
    this.url = url;
  }

  /**
   * Where it came from: {@code api} (the default), {@code widget}, …
   *
   * @return the source
   */
  public String getSource() {
    return source;
  }

  public void setSource(String source) {
    this.source = source;
  }
}
