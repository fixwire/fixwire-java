import com.sun.net.httpserver.HttpServer;
import io.fixwire.Fixwire;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.GZIPInputStream;

/**
 * The built library on its own, on any Java the SDK supports: CI runs it on Java 8 and 11, where
 * the tests (compiled for Java 17) can't run. An error goes to a fake ingest, its card number
 * masked on the way.
 *
 * <pre>
 *   javac --release 8 -cp fixwire/build/libs/fixwire-*.jar -d build/smoke smoke/Smoke.java
 *   java -cp fixwire/build/libs/fixwire-*.jar:build/smoke Smoke
 * </pre>
 */
public final class Smoke {
  public static void main(String[] args) throws Exception {
    List<String> logs = new CopyOnWriteArrayList<>();
    HttpServer ingest = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    ingest.createContext(
        "/",
        exchange -> {
          InputStream body = exchange.getRequestBody();
          if ("gzip".equals(exchange.getRequestHeaders().getFirst("Content-Encoding"))) {
            body = new GZIPInputStream(body);
          }
          ByteArrayOutputStream out = new ByteArrayOutputStream();
          byte[] buf = new byte[8192];
          for (int n; (n = body.read(buf)) > 0; ) {
            out.write(buf, 0, n);
          }
          if (exchange.getRequestURI().getPath().equals("/v1/logs")) {
            logs.add(new String(out.toByteArray(), StandardCharsets.UTF_8));
          }
          byte[] ok = "{}".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, ok.length);
          exchange.getResponseBody().write(ok);
          exchange.close();
        });
    ingest.start();

    Fixwire.init(
        o -> {
          o.setDsn("http://smoke-key@127.0.0.1:" + ingest.getAddress().getPort());
          o.setRelease("smoke@1.0.0");
        });
    Fixwire.captureException(new IllegalStateException("card 4111 1111 1111 1111 was declined"));
    boolean flushed = Fixwire.flush(5000);
    ingest.stop(0);

    String java = System.getProperty("java.version");
    String all = String.join("\n", logs);
    StringBuilder failures = new StringBuilder();
    if (!flushed) failures.append("flush timed out; ");
    if (!all.contains("was declined")) failures.append("no error reached /v1/logs; ");
    if (all.contains("4111 1111 1111 1111")) failures.append("the card number wasn't masked; ");
    if (failures.length() > 0) {
      System.err.println("FAIL on Java " + java + ": " + failures);
      System.exit(1);
    }
    System.out.println("ok   io.fixwire:fixwire on Java " + java + ": an error reached the ingest, masked");
  }
}
