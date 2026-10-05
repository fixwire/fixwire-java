package io.fixwire;

/** One call in a stack trace. */
public final class Frame {
  private String function;
  private String module;
  private String file;
  private int line;
  private boolean inApp;

  /**
   * The method.
   *
   * @return such as {@code charge}
   */
  public String getFunction() {
    return function;
  }

  public void setFunction(String function) {
    this.function = function;
  }

  /**
   * The class.
   *
   * @return such as {@code com.example.shop.Cart}
   */
  public String getModule() {
    return module;
  }

  public void setModule(String module) {
    this.module = module;
  }

  public String getFile() {
    return file;
  }

  public void setFile(String file) {
    this.file = file;
  }

  /**
   * The line, or 0 when it is not known.
   *
   * @return the line number
   */
  public int getLine() {
    return line;
  }

  public void setLine(int line) {
    this.line = line;
  }

  /**
   * Whether the frame is the app's code rather than a library's.
   *
   * @return true for the app's frames
   */
  public boolean isInApp() {
    return inApp;
  }

  public void setInApp(boolean inApp) {
    this.inApp = inApp;
  }
}
