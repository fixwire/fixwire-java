package io.fixwire.spring;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.example.app.TestApp;
import io.fixwire.Fixwire;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/** A typo in fixwire.dsn: the app starts anyway, with Fixwire off and a warning on stderr. */
@SpringBootTest(
    classes = TestApp.class,
    properties = {
      "fixwire.dsn=ingest.fixwire.io",
      "fixwire.logging.enabled=false",
      "payments.url=http://127.0.0.1:9"
    })
class FixwireMalformedDsnTest {
  @Test
  void startsTheAppWithFixwireOff() {
    assertFalse(Fixwire.isEnabled());
    assertNull(Fixwire.captureMessage("not sent"));
  }
}
