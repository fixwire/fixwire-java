package io.fixwire;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.Delayed;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.GZIPOutputStream;

/**
 * Sends requests one at a time from a bounded queue on a daemon thread, and backs off where Fixwire
 * says to ({@code Fixwire-Rate-Limits}, {@code Retry-After}). Never blocks the caller.
 */
final class Transport {
  // The kinds of data, as the protocol's rate limits name them.
  static final String ERROR = "error";
  static final String LOG = "log";
  static final String SPAN = "span";
  static final String SESSION = "session";
  static final String CHECK_IN = "check_in";
  static final String FEEDBACK = "feedback";

  /** The kinds of data the SDK sends: pauses for others are not kept. */
  private static final Set<String> CATEGORIES =
      new HashSet<>(Arrays.asList("", ERROR, LOG, SPAN, SESSION, CHECK_IN, FEEDBACK));

  /** The sends of one request, and the longest a paused one waits. */
  static final int MAX_ATTEMPTS = 4;

  static final long MAX_WAIT_MILLIS = 5 * 60_000L;

  /** The longest pause an answer can ask for (a day), so that huge numbers don't overflow. */
  static final long MAX_PAUSE_SECONDS = 24 * 3600;

  /** The first retry's wait, halved (tests shorten it). */
  static volatile long backoffUnitMillis = 1000;

  static final class Item implements Delayed {
    final String path;
    final String category;
    final byte[] body;
    int attempts;
    volatile long readyAt; // System.nanoTime()

    Item(String path, String category, byte[] body) {
      this.path = path;
      this.category = category;
      this.body = body;
      this.readyAt = System.nanoTime();
    }

    @Override
    public long getDelay(TimeUnit unit) {
      return unit.convert(readyAt - System.nanoTime(), TimeUnit.NANOSECONDS);
    }

    @Override
    public int compareTo(Delayed o) {
      return Long.compare(readyAt, ((Item) o).readyAt);
    }
  }

  private final Dsn dsn;
  private final Options opts;
  private final DelayQueue<Item> queue = new DelayQueue<>();
  private final Map<String, Long> pausedUntil = new HashMap<>(); // category ("" for all) → millis
  // A lock rather than a monitor: a virtual thread waiting in flush on
  // Java 21 would pin its carrier thread.
  private final ReentrantLock lock = new ReentrantLock();
  private final Condition drained = lock.newCondition();
  private int pending;
  private volatile boolean closed;
  private final Thread worker;

  Transport(Dsn dsn, Options opts) {
    this.dsn = dsn;
    this.opts = opts;
    this.worker = new Thread(this::run, "fixwire-transport");
    worker.setDaemon(true);
    worker.start();
  }

  void log(String format, Object... args) {
    if (opts.isDebug()) {
      System.err.println("fixwire: " + String.format(format, args));
    }
  }

  /** Queues a request; false when the queue is full or closed. */
  boolean send(String path, String category, byte[] body) {
    boolean full;
    lock.lock();
    try {
      full = closed || pending >= opts.getMaxQueue();
      if (!full) {
        pending++;
      }
    } finally {
      lock.unlock();
    }
    if (full) {
      log("dropping a %s request: %s", category, closed ? "closed" : "the queue is full");
      return false;
    }
    queue.add(new Item(path, category, body));
    return true;
  }

  private void done() {
    lock.lock();
    try {
      pending = Math.max(pending - 1, 0);
      if (pending == 0) {
        drained.signalAll();
      }
    } finally {
      lock.unlock();
    }
  }

  private void later(Item item, long millis) {
    item.readyAt = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
    queue.add(item);
  }

  private void run() {
    while (!closed || !queue.isEmpty()) {
      Item item;
      try {
        item = queue.poll(200, TimeUnit.MILLISECONDS);
      } catch (InterruptedException e) {
        if (closed) {
          break;
        }
        continue;
      }
      if (item == null) {
        if (closed) {
          break; // what is left is waiting for a retry: dropped
        }
        continue;
      }
      try {
        deliver(item);
      } catch (RuntimeException e) {
        log("sending a %s request failed: %s", item.category, e);
        done();
      }
    }
    lock.lock();
    try {
      pending = 0;
      drained.signalAll();
    } finally {
      lock.unlock();
    }
  }

  private long pausedFor(String category, long now) {
    lock.lock();
    try {
      Long c = pausedUntil.get(category);
      Long all = pausedUntil.get("");
      return Math.max(Math.max(c == null ? 0 : c - now, all == null ? 0 : all - now), 0);
    } finally {
      lock.unlock();
    }
  }

  private void deliver(Item item) {
    long wait = pausedFor(item.category, System.currentTimeMillis());
    if (wait > 0) {
      if (wait > MAX_WAIT_MILLIS || closed) {
        log("dropping a %s request: paused for %d s", item.category, wait / 1000);
        done();
      } else {
        later(item, wait);
      }
      return;
    }
    int status;
    long retryAfter = 0;
    try {
      long[] answer = post(item);
      status = (int) answer[0];
      retryAfter = answer[1];
    } catch (IOException e) {
      status = -1;
      log("sending a %s request: %s", item.category, e);
    }
    if (status >= 200 && status < 300) {
      done();
    } else if (status == -1 || status == 429 || status >= 500) {
      item.attempts++;
      if (item.attempts >= MAX_ATTEMPTS || closed) {
        log("dropping a %s request after %d attempts (%d)", item.category, item.attempts, status);
        done();
        return;
      }
      long backoff = (1L << item.attempts) * backoffUnitMillis;
      later(item, Math.max(backoff, retryAfter));
    } else {
      log("%s request refused: %d", item.category, status);
      done();
    }
  }

  /** Sends a request, gzipped: the status and Retry-After in millis. */
  private long[] post(Item item) throws IOException {
    ByteArrayOutputStream gz = new ByteArrayOutputStream(item.body.length / 4 + 64);
    try (GZIPOutputStream z = new GZIPOutputStream(gz)) {
      z.write(item.body);
    }
    HttpURLConnection c = (HttpURLConnection) new URL(dsn.url(item.path)).openConnection();
    try {
      c.setRequestMethod("POST");
      // The key goes to the DSN's host only: an answer that redirects is refused, not followed.
      c.setInstanceFollowRedirects(false);
      c.setConnectTimeout(opts.getTimeoutMillis());
      c.setReadTimeout(opts.getTimeoutMillis());
      c.setDoOutput(true);
      c.setFixedLengthStreamingMode(gz.size());
      c.setRequestProperty("Authorization", "Bearer " + dsn.key());
      c.setRequestProperty("Content-Type", "application/json");
      c.setRequestProperty("Content-Encoding", "gzip");
      c.setRequestProperty("User-Agent", Client.SDK_NAME + "/" + Client.SDK_VERSION);
      try (OutputStream out = c.getOutputStream()) {
        gz.writeTo(out);
      }
      int status = c.getResponseCode();
      drain(status >= 400 ? c.getErrorStream() : c.getInputStream());
      long now = System.currentTimeMillis();
      long retryAfter = 0;
      String ra = c.getHeaderField("Retry-After");
      if (ra != null) {
        try {
          retryAfter = Math.min(Math.max(Long.parseLong(ra.trim()), 0), MAX_PAUSE_SECONDS) * 1000;
        } catch (NumberFormatException ignored) {
          // an HTTP date: the default backoff will do
        }
      }
      String limits = c.getHeaderField("Fixwire-Rate-Limits");
      limit(limits, now);
      if (status == 429 && limits == null) {
        limit(Math.max(retryAfter / 1000, 60) + ":", now);
      }
      return new long[] {status, retryAfter};
    } finally {
      c.disconnect();
    }
  }

  private static void drain(InputStream in) throws IOException {
    if (in == null) {
      return;
    }
    try (InputStream s = in) {
      byte[] buf = new byte[4096];
      int total = 0;
      int n;
      while (total < 64 * 1024 && (n = s.read(buf)) > 0) {
        total += n;
      }
    }
  }

  /** Reads {@code <seconds>:<category;…>, …}; no categories means all. */
  void limit(String header, long now) {
    if (header == null || header.isEmpty()) {
      return;
    }
    lock.lock();
    try {
      for (String part : header.split(",")) {
        String[] sc = part.trim().split(":", 2);
        long secs;
        try {
          secs = Long.parseLong(sc[0].trim());
        } catch (NumberFormatException e) {
          continue;
        }
        if (secs <= 0) {
          continue;
        }
        long until = now + Math.min(secs, MAX_PAUSE_SECONDS) * 1000;
        String cats = sc.length > 1 ? sc[1].trim() : "";
        for (String cat : cats.isEmpty() ? new String[] {""} : cats.split(";")) {
          String c = cat.trim();
          Long was = pausedUntil.get(c);
          if (CATEGORIES.contains(c) && (was == null || until > was)) {
            pausedUntil.put(c, until);
          }
        }
      }
    } finally {
      lock.unlock();
    }
  }

  /** Waits until every queued request is sent or dropped; false on timeout. */
  boolean flush(long timeoutMillis) {
    long left = TimeUnit.MILLISECONDS.toNanos(Math.max(timeoutMillis, 0));
    lock.lock();
    try {
      while (pending > 0) {
        if (left <= 0) {
          return false;
        }
        try {
          left = drained.awaitNanos(left);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return false;
        }
      }
    } finally {
      lock.unlock();
    }
    return true;
  }

  /** Stops the worker; what is not sent yet is dropped. */
  void close() {
    closed = true;
    worker.interrupt();
    try {
      worker.join(1000);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
