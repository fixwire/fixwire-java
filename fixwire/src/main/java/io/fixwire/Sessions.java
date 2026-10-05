package io.fixwire;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TimeZone;

/**
 * Release health for servers: each request is a session, counted per minute and user and sent about
 * every minute (sdks/PROTOCOL.md §5).
 */
final class Sessions {
  /** The session of the request a scope serves. */
  static final class RequestSession {
    private String status = "ok";

    synchronized void mark(boolean crashed) {
      if (crashed) {
        status = "crashed";
      } else if (status.equals("ok")) {
        status = "errored";
      }
    }

    synchronized String status() {
      return status;
    }
  }

  private static final class Key {
    final long minute;
    final String did;

    Key(long minute, String did) {
      this.minute = minute;
      this.did = did;
    }

    @Override
    public boolean equals(Object o) {
      return o instanceof Key && ((Key) o).minute == minute && Objects.equals(((Key) o).did, did);
    }

    @Override
    public int hashCode() {
      return Long.hashCode(minute) * 31 + Objects.hashCode(did);
    }
  }

  private final Client client;
  private Map<Key, int[]> buckets = new HashMap<>(); // exited, errored, crashed

  Sessions(Client client) {
    this.client = client;
  }

  /** Counts a request that ended. */
  synchronized void record(String status, String did, long nowMillis) {
    Key k = new Key(nowMillis - Math.floorMod(nowMillis, 60_000L), did);
    int[] counts = buckets.get(k);
    if (counts == null) {
      counts = new int[3];
      buckets.put(k, counts);
    }
    counts["crashed".equals(status) ? 2 : "errored".equals(status) ? 1 : 0]++;
  }

  /** Sends what was counted. */
  void send() {
    Map<Key, int[]> taken;
    synchronized (this) {
      if (buckets.isEmpty()) {
        return;
      }
      taken = buckets;
      buckets = new HashMap<>();
    }
    SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
    iso.setTimeZone(TimeZone.getTimeZone("UTC"));
    List<Object> aggregates = new ArrayList<>();
    for (Map.Entry<Key, int[]> e : taken.entrySet()) {
      Map<String, Object> a = new LinkedHashMap<>();
      a.put("started", iso.format(new Date(e.getKey().minute)));
      if (e.getKey().did != null) {
        a.put("did", e.getKey().did);
      }
      a.put("exited", e.getValue()[0]);
      a.put("errored", e.getValue()[1]);
      a.put("crashed", e.getValue()[2]);
      aggregates.add(a);
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("sdk", Client.sdk());
    body.put("release", client.options().getRelease());
    body.put("environment", client.options().getEnvironment());
    body.put("aggregates", aggregates);
    client.sendJson("/v1/sessions", Transport.SESSION, body);
  }

  /**
   * The user, hashed on the device: the first 16 bytes of the SHA-256 of their id (else email, else
   * username), as hex. Never the raw id.
   */
  static String deviceId(User u) {
    if (u == null) {
      return null;
    }
    String id =
        !Options.empty(u.getId())
            ? u.getId()
            : !Options.empty(u.getEmail()) ? u.getEmail() : u.getUsername();
    if (Options.empty(id)) {
      return null;
    }
    try {
      byte[] sum = MessageDigest.getInstance("SHA-256").digest(id.getBytes(StandardCharsets.UTF_8));
      StringBuilder b = new StringBuilder(32);
      for (int i = 0; i < 16; i++) {
        b.append(String.format("%02x", sum[i] & 0xff));
      }
      return b.toString();
    } catch (NoSuchAlgorithmException e) {
      return null; // every JVM has SHA-256
    }
  }
}
