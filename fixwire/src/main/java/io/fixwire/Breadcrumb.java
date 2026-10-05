package io.fixwire;

import java.util.LinkedHashMap;
import java.util.Map;

/** Something that happened before an error: a log line, a request, a click. */
public final class Breadcrumb {
  private long timestampMillis;
  private String type;
  private String category;
  private String message;
  private Level level;
  private Map<String, Object> data;

  /** An empty breadcrumb; the time is set when it is added. */
  public Breadcrumb() {}

  /**
   * A breadcrumb with a message.
   *
   * @param category what it is about, such as {@code cart} or {@code http}
   * @param message what happened
   */
  public Breadcrumb(String category, String message) {
    this.category = category;
    this.message = message;
  }

  public long getTimestampMillis() {
    return timestampMillis;
  }

  public void setTimestampMillis(long timestampMillis) {
    this.timestampMillis = timestampMillis;
  }

  public String getType() {
    return type;
  }

  public void setType(String type) {
    this.type = type;
  }

  public String getCategory() {
    return category;
  }

  public void setCategory(String category) {
    this.category = category;
  }

  public String getMessage() {
    return message;
  }

  public void setMessage(String message) {
    this.message = message;
  }

  public Level getLevel() {
    return level;
  }

  public void setLevel(Level level) {
    this.level = level;
  }

  public Map<String, Object> getData() {
    return data;
  }

  public void setData(Map<String, Object> data) {
    this.data = data;
  }

  /**
   * Adds a detail.
   *
   * @param key the detail's name
   * @param value its value
   */
  public void putData(String key, Object value) {
    if (data == null) {
      data = new LinkedHashMap<>();
    }
    data.put(key, value);
  }
}
