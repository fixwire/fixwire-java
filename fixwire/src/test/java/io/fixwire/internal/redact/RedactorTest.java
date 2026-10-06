package io.fixwire.internal.redact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Cases beyond the shared corpus; every expected string was checked against the server. */
class RedactorTest {
  private static final Redactor R = Redactor.defaults();

  private static String mask(String s) {
    return R.mask(s).text;
  }

  @Test
  void wordBoundariesAreAscii() {
    // Other scripts are not word characters, on every Java version.
    assertEquals(
        "é[REDACTED:aws_access_key]é _AKIAIOSFODNN7EXAMPLE",
        mask("éAKIAIOSFODNN7EXAMPLEé _AKIAIOSFODNN7EXAMPLE"));
    assertEquals("é[REDACTED:email]é", mask("éada@example.comé"));
    assertEquals("card é[REDACTED:credit_card]é", mask("card é4111111111111111é"));
  }

  @Test
  void caseIsIgnoredLikeTheServer() {
    // The server's case folding takes the Kelvin sign for "k" and the long s for "s".
    assertEquals("to\u212Aen=[REDACTED:secret_assignment]", mask("to\u212Aen=abcdefgh"));
    assertEquals(
        "x\u017Fecret=[REDACTED:secret_assignment] token", mask("x\u017Fecret=abcdefgh token"));
    assertEquals("bearer [REDACTED:http_auth]", mask("bearer \u017F\u212Aabcdefghijkl1"));
    // Its prefilter lowers U+0130 to "i", so "apİkey" lets the pattern run.
    assertEquals(
        "ap\u0130key access_key=[REDACTED:secret_assignment]",
        mask("ap\u0130key access_key=abcdefgh"));
    // No prefilter literal in "access_key", so alone it is not looked at.
    assertEquals("access_key=abcdefgh", mask("access_key=abcdefgh"));
  }

  @Test
  void slackTokenStopsBeforeATrailingDash() {
    assertEquals(
        "xoxb-123456789-  [REDACTED:slack_token]- x", mask("xoxb-123456789-  xoxb-1234567890- x"));
  }

  @Test
  void privateKeysAndUrlCredentials() {
    assertEquals(
        "a [REDACTED:private_key] b -----END RSA PRIVATE KEY-----",
        mask(
            "a -----BEGIN "
                + "RSA PRIVATE KEY-----\nMII\n-----END RSA PRIVATE KEY----- b"
                + " -----END RSA PRIVATE KEY-----"));
    assertEquals(
        "-----BEGIN " + " PRIVATE KEY-----x-----END PRIVATE KEY-----",
        mask("-----BEGIN " + " PRIVATE KEY-----x-----END PRIVATE KEY-----"));
    assertEquals(
        "x://u:[REDACTED:url_credentials]@h://v:[REDACTED:url_credentials]@z 1a://u:p@h",
        mask("x://u:p:q@h://v:w@z 1a://u:p@h"));
    assertEquals("postgres://u:[Filtered]@h", mask("postgres://u:[Filtered]@h"));
  }

  @Test
  void renamedKeysAreNumberedInCodePointOrder() {
    // UTF-16 order would put the emoji (past U+FFFF) before U+FF01.
    var doc = new LinkedHashMap<String, Object>();
    doc.put("http://u:\uD83D\uDE00@h", "emoji");
    doc.put("http://u:\uFF01@h", "fullwidth");
    var count = new int[1];
    var want = new LinkedHashMap<String, Object>();
    want.put("http://u:[REDACTED:url_credentials]@h", "fullwidth");
    want.put("http://u:[REDACTED:url_credentials]@h (2)", "emoji");
    assertEquals(want, R.walk(doc, count));
    assertEquals(2, count[0]);
  }

  @Test
  void walkMasksWhatJsonWouldWrite() {
    var untouched = List.<Object>of("nothing here", 1);
    var doc = new LinkedHashMap<String, Object>();
    doc.put("set", new LinkedHashSet<>(List.of("ada@example.com", "x")));
    doc.put("array", new Object[] {"ada@example.com", 1});
    doc.put("text", new StringBuilder("mail ada@example.com"));
    doc.put("uri", URI.create("https://u:secretpw@example.com/"));
    doc.put("untouched", untouched);
    doc.put("n", 3);
    var count = new int[1];
    assertSame(doc, R.walk(doc, count));
    assertEquals(4, count[0]);
    assertEquals(Arrays.asList("[REDACTED:email]", "x"), doc.get("set"));
    assertEquals(Arrays.asList("[REDACTED:email]", 1), doc.get("array"));
    assertEquals("mail [REDACTED:email]", doc.get("text"));
    assertEquals("https://u:[REDACTED:url_credentials]@example.com/", doc.get("uri"));
    assertSame(untouched, doc.get("untouched"));
    assertEquals(3, doc.get("n"));
  }

  @Test
  void walkFiltersSensitiveValues() {
    var doc = new LinkedHashMap<String, Object>();
    var typed = new LinkedHashMap<String, Object>();
    typed.put("value", "hunter2");
    doc.put("password", typed);
    doc.put("X-Api-Key", List.of(1, 2));
    doc.put("max_tokens", 512);
    doc.put("session_id", "");
    doc.put("headers", new ArrayList<>(List.of(new ArrayList<>(List.of("Cookie", "a=b")))));
    var count = new int[1];
    R.walk(doc, count);
    assertEquals(Map.of("value", "[Filtered]", "type", "string"), doc.get("password"));
    assertEquals("[Filtered]", doc.get("X-Api-Key"));
    assertEquals(512, doc.get("max_tokens"));
    assertEquals("", doc.get("session_id"));
    assertEquals(List.of(List.of("Cookie", "[Filtered]")), doc.get("headers"));
    assertEquals(3, count[0]);
  }

  @Test
  void walkStopsAtJsonDepth() {
    var doc = new LinkedHashMap<String, Object>();
    doc.put("self", doc);
    doc.put("email", "ada@example.com");
    var count = new int[1];
    R.walk(doc, count);
    assertEquals("[REDACTED:email]", doc.get("email"));
    assertEquals(1, count[0]);
  }

  @Test
  void sensitiveKeysReplaceTheDefaults() {
    assertSame(Redactor.defaults(), Redactor.create(null));
    var r = Redactor.create(List.of("Internal-ID"));
    var doc = new LinkedHashMap<String, Object>();
    doc.put("internal_id", "x");
    doc.put("password", "y");
    doc.put("Auth", "z");
    r.walk(doc, new int[1]);
    assertEquals(Map.of("internal_id", "[Filtered]", "password", "y", "Auth", "[Filtered]"), doc);
  }

  @Test
  void ipv4IsOffByDefault() {
    assertEquals("host 10.0.0.1", mask("host 10.0.0.1"));
    var r = new Redactor(List.of("ipv4"), null);
    assertEquals("host [REDACTED:ipv4]", r.mask("host 10.0.0.1").text);
    assertThrows(IllegalArgumentException.class, () -> new Redactor(List.of("nope"), null));
  }

  @Test
  void maskNull() {
    var m = R.mask(null);
    assertNull(m.text);
    assertTrue(m.findings.isEmpty());
  }

  private static String repeat(String s, int n) {
    return s.repeat(n);
  }

  @Test
  void largeTextIsFast() {
    var inputs =
        List.of(
            repeat(
                "GET /api/v1/users/12345 took 87 ms; status=200 at com.example.Service.handle"
                    + "(Service.java:42) https://example.com/docs?x=1 basic info, token count 3 + 1. ",
                700),
            // Text a backtracking engine would take quadratic time on.
            repeat("a.", 50_000) + "://",
            repeat("-----BEGIN PRIVATE KEY-----", 3_700),
            repeat("a@", 50_000),
            repeat("1 ", 50_000),
            repeat("-eyJ", 25_000));
    for (int round = 0; round < 3; round++) {
      for (var s : inputs) {
        assertTimeout(Duration.ofSeconds(1), () -> assertTrue(R.mask(s).findings.isEmpty()));
      }
    }
  }

  @Test
  void jwtsAreFoundWhereTheServerFindsThem() {
    String jwt = "eyJhbGciOiJIUzI1.eyJzdWIiOiIxMjM0.c2lnbmF0dXJl";
    assertEquals("x [REDACTED:jwt] y", mask("x " + jwt + " y"));
    assertEquals("-[REDACTED:jwt]", mask("-eyJaaaaaaaa-eyJbbbbbbbb.eyJcccccccc.dddddddd"));
    assertEquals("a" + jwt, mask("a" + jwt), "not at a word boundary");
    assertEquals(
        "eyJshort.eyJbbbbbbbb.dddddddd [REDACTED:jwt]",
        mask("eyJshort.eyJbbbbbbbb.dddddddd " + jwt));
  }

  @Test
  void manyFindingsAreFast() {
    var text = repeat("ada@example.com ", 60_000);
    assertTimeout(Duration.ofSeconds(1), () -> assertEquals(60_000, R.mask(text).findings.size()));
    // Keys that mask alike, numbered (2) to (20000).
    var doc = new LinkedHashMap<String, Object>();
    for (int i = 0; i < 20_000; i++) {
      doc.put("user" + i + "@example.com", i);
    }
    var count = new int[1];
    assertTimeout(Duration.ofSeconds(1), () -> R.walk(doc, count));
    assertEquals(20_000, count[0]);
    assertEquals(20_000, doc.size());
    assertEquals(0, doc.get("[REDACTED:email]"));
    assertTrue(doc.containsKey("[REDACTED:email] (20000)"));
  }
}
