package io.fixwire.internal.redact;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * The shared corpus of the Fixwire server's redaction (a copy of pkg/redact/testdata/vectors.json
 * in fixwire/fixwire, kept identical): this port must mask every string and document exactly as the
 * server does.
 */
class VectorsTest {
  // Numbers decode alike on both sides, so documents compare by value.
  private static final ObjectMapper JSON =
      new ObjectMapper()
          .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
          .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS);

  private static final Map<String, Object> VECTORS = load();

  /** The corpus, next to this class on the test classpath. */
  private static Map<String, Object> load() {
    try (InputStream in = VectorsTest.class.getResourceAsStream("vectors.json")) {
      if (in == null) {
        throw new IllegalStateException("vectors.json is not on the test classpath");
      }
      return map(JSON.readValue(in, Object.class));
    } catch (IOException e) {
      throw new IllegalStateException("reading vectors.json", e);
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object v) {
    return (Map<String, Object>) v;
  }

  @SuppressWarnings("unchecked")
  private static List<Object> list(Object v) {
    return (List<Object>) v;
  }

  /** Replaces each {{fixture}} with its parts joined (kept apart in the file). */
  private static String expand(String s) {
    for (var e : map(VECTORS.get("fixtures")).entrySet()) {
      var joined = new StringBuilder();
      for (Object part : list(e.getValue())) {
        joined.append((String) part);
      }
      s = s.replace("{{" + e.getKey() + "}}", joined);
    }
    return s;
  }

  /** A deep, mutable copy of a decoded value with the fixtures expanded (keys too). */
  private static Object expandValue(Object v) {
    if (v instanceof String s) {
      return expand(s);
    }
    if (v instanceof List<?> l) {
      var out = new ArrayList<Object>(l.size());
      for (Object x : l) {
        out.add(expandValue(x));
      }
      return out;
    }
    if (v instanceof Map<?, ?> m) {
      var out = new LinkedHashMap<String, Object>();
      for (var e : m.entrySet()) {
        out.put(expand((String) e.getKey()), expandValue(e.getValue()));
      }
      return out;
    }
    return v;
  }

  @Test
  void sameDetectorsAndKeys() {
    assertEquals(VECTORS.get("detectors"), Redactor.DEFAULT_DETECTORS);
    assertEquals(VECTORS.get("sensitive_keys"), Redactor.DEFAULT_SENSITIVE_KEYS);
  }

  @TestFactory
  Stream<DynamicTest> strings() {
    return list(VECTORS.get("strings")).stream()
        .map(
            c -> {
              var tc = map(c);
              return DynamicTest.dynamicTest(
                  (String) tc.get("name"),
                  () -> {
                    var m = Redactor.defaults().mask(expand((String) tc.get("input")));
                    assertEquals(tc.get("masked"), m.text);
                    assertEquals(tc.get("findings"), m.findings);
                  });
            });
  }

  @TestFactory
  Stream<DynamicTest> documents() {
    return list(VECTORS.get("documents")).stream()
        .map(
            c -> {
              var tc = map(c);
              return DynamicTest.dynamicTest(
                  (String) tc.get("name"),
                  () -> {
                    var count = new int[1];
                    var got = Redactor.defaults().walk(expandValue(tc.get("input")), count);
                    assertEquals(tc.get("masked"), got);
                    assertEquals(((Number) tc.get("count")).intValue(), count[0]);
                  });
            });
  }
}
