package io.fixwire;

/** Who the work is for. */
public final class User {
  private String id;
  private String email;
  private String username;
  private String ipAddress;

  /** A user without details. */
  public User() {}

  /**
   * A user by id.
   *
   * @param id the user's id in your app
   */
  public User(String id) {
    this.id = id;
  }

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public String getEmail() {
    return email;
  }

  public void setEmail(String email) {
    this.email = email;
  }

  public String getUsername() {
    return username;
  }

  public void setUsername(String username) {
    this.username = username;
  }

  /**
   * The user's IP address; sent only with {@link Options#setSendDefaultPii}.
   *
   * @return the address, or null
   */
  public String getIpAddress() {
    return ipAddress;
  }

  public void setIpAddress(String ipAddress) {
    this.ipAddress = ipAddress;
  }

  boolean isEmpty() {
    return id == null && email == null && username == null && ipAddress == null;
  }

  User copy() {
    User u = new User(id);
    u.email = email;
    u.username = username;
    u.ipAddress = ipAddress;
    return u;
  }
}
