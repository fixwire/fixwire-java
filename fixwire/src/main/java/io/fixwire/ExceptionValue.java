package io.fixwire;

import java.util.ArrayList;
import java.util.List;

/** One exception of an event's chain: the one caught, then its causes. */
public final class ExceptionValue {
  private String type;
  private String message;
  private String module;
  private String mechanism = "generic";
  private boolean handled = true;
  private List<Frame> frames = new ArrayList<>();

  /**
   * The exception's class.
   *
   * @return such as {@code java.lang.IllegalStateException}
   */
  public String getType() {
    return type;
  }

  public void setType(String type) {
    this.type = type;
  }

  public String getMessage() {
    return message;
  }

  public void setMessage(String message) {
    this.message = message;
  }

  /**
   * The package of the exception's class.
   *
   * @return such as {@code java.lang}
   */
  public String getModule() {
    return module;
  }

  public void setModule(String module) {
    this.module = module;
  }

  /**
   * How it was caught: {@code generic} (captured by the app), {@code UncaughtExceptionHandler},
   * {@code servlet}, {@code logging}, {@code chained} (a cause), …
   *
   * @return the mechanism's type
   */
  public String getMechanism() {
    return mechanism;
  }

  public void setMechanism(String mechanism) {
    this.mechanism = mechanism;
  }

  /**
   * False for a crash: nothing handled the exception.
   *
   * @return whether it was handled
   */
  public boolean isHandled() {
    return handled;
  }

  public void setHandled(boolean handled) {
    this.handled = handled;
  }

  /**
   * The stack, the oldest call first.
   *
   * @return the frames
   */
  public List<Frame> getFrames() {
    return frames;
  }

  public void setFrames(List<Frame> frames) {
    this.frames = frames == null ? new ArrayList<Frame>() : frames;
  }
}
