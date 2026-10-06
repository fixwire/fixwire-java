package io.fixwire;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Exceptions and their stacks, as events carry them. */
final class Frames {
  private Frames() {}

  /** The causes followed, and the frames kept per exception (the newest). */
  static final int MAX_CHAIN = 10;

  static final int MAX_FRAMES = 100;

  /** Packages of the JDK, Kotlin and well-known libraries: not the app's. */
  private static final String[] LIBRARIES = {
    "java.",
    "javax.",
    "jakarta.",
    "jdk.",
    "sun.",
    "com.sun.",
    "kotlin.",
    "kotlinx.",
    "scala.",
    "groovy.",
    "org.springframework.",
    "org.apache.",
    "org.eclipse.jetty.",
    "io.undertow.",
    "io.netty.",
    "reactor.",
    "io.reactivex.",
    "rx.",
    "okhttp3.",
    "okio.",
    "retrofit2.",
    "com.fasterxml.",
    "org.hibernate.",
    "ch.qos.logback.",
    "org.slf4j.",
    "org.junit.",
    "junit.",
    "org.gradle.",
    "io.micronaut.",
    "io.quarkus.",
    "io.vertx.",
    "io.ktor.",
    "io.grpc.",
    "com.google.",
    "net.bytebuddy.",
    "org.jboss.",
    "io.opentelemetry.",
    "com.zaxxer.",
    "org.postgresql.",
    "com.mysql.",
    "org.flywaydb.",
    "org.liquibase.",
    "io.fixwire."
  };

  /**
   * Parts of generated class names that change between runs and builds: lambdas, proxies and
   * bytecode enhancers.
   */
  private static final Pattern GENERATED =
      Pattern.compile(
          "(\\$\\$Lambda)(?:\\$\\d+)?(?:/0x[0-9a-fA-F]+)?"
              + "|(\\$\\$(?:EnhancerBySpringCGLIB|SpringCGLIB|FastClassBySpringCGLIB|EnhancerByCGLIB|HibernateProxy))\\$\\$[0-9a-zA-Z]+"
              + "|(\\$Proxy)\\d+");

  /**
   * A throwable and its causes, the outermost first.
   *
   * @param mechanism how it was caught; causes are {@code chained}
   */
  static List<ExceptionValue> chain(Throwable t, String mechanism, boolean handled, Options opts) {
    List<ExceptionValue> out = new ArrayList<>();
    Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
    for (Throwable e = t; e != null && out.size() < MAX_CHAIN && seen.add(e); e = cause(e)) {
      ExceptionValue x = new ExceptionValue();
      String type = e.getClass().getName();
      x.setType(type);
      x.setModule(type.lastIndexOf('.') > 0 ? type.substring(0, type.lastIndexOf('.')) : "");
      x.setMessage(message(e));
      x.setMechanism(out.isEmpty() ? mechanism : "chained");
      x.setHandled(handled);
      x.setFrames(frames(e.getStackTrace(), opts));
      out.add(x);
    }
    return out;
  }

  /** The throwable's message, or "": an override that throws must not fail the capture. */
  static String message(Throwable e) {
    try {
      String m = e.getMessage();
      return m == null ? "" : m;
    } catch (RuntimeException ex) {
      return "";
    }
  }

  private static Throwable cause(Throwable e) {
    try {
      return e.getCause();
    } catch (RuntimeException ex) {
      return null;
    }
  }

  /** Stack trace elements (the newest first) as frames, the oldest first. */
  static List<Frame> frames(StackTraceElement[] stack, Options opts) {
    int n = Math.min(stack.length, MAX_FRAMES); // the newest calls are kept
    List<Frame> out = new ArrayList<>(n);
    for (int i = n - 1; i >= 0; i--) {
      StackTraceElement s = stack[i];
      Frame f = new Frame();
      String module = normalize(s.getClassName());
      f.setModule(module);
      f.setFunction(s.getMethodName());
      f.setFile(s.getFileName());
      f.setLine(Math.max(s.getLineNumber(), 0));
      f.setInApp(inApp(module, opts));
      out.add(f);
    }
    return out;
  }

  static String normalize(String className) {
    if (className.indexOf('$') < 0) {
      return className;
    }
    java.util.regex.Matcher m = GENERATED.matcher(className);
    StringBuffer b = new StringBuffer();
    while (m.find()) {
      String kept = m.group(1) != null ? m.group(1) : m.group(2) != null ? m.group(2) : m.group(3);
      m.appendReplacement(b, java.util.regex.Matcher.quoteReplacement(kept));
    }
    m.appendTail(b);
    return b.toString();
  }

  /** Whether a class is the app's code. */
  static boolean inApp(String className, Options opts) {
    for (String p : opts.getInAppExcludes()) {
      if (className.startsWith(p)) {
        return false;
      }
    }
    for (String p : opts.getInAppIncludes()) {
      if (className.startsWith(p)) {
        return true;
      }
    }
    for (String p : LIBRARIES) {
      if (className.startsWith(p)) {
        return false;
      }
    }
    return true;
  }
}
