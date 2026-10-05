package com.example.shop;

import io.fixwire.Fixwire;
import io.fixwire.servlet.FixwireFilter;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Sets Fixwire up when the app starts: the SDK from the {@code fixwire.*} properties (the DSN falls
 * back to {@code FIXWIRE_DSN}), and the filter in front of every request.
 */
@Configuration
public class FixwireConfig {
  public FixwireConfig(
      @Value("${fixwire.dsn:}") String dsn,
      @Value("${fixwire.release:shop@1.0.0}") String release,
      @Value("${fixwire.traces-sample-rate:1.0}") double tracesSampleRate) {
    Fixwire.init(
        o -> {
          o.setDsn(dsn);
          o.setRelease(release);
          o.setTracesSampleRate(tracesSampleRate);
          o.setInAppIncludes(java.util.List.of("com.example.shop"));
        });
  }

  /** The filter goes first, so it sees every request and every exception that escapes. */
  @Bean
  public FilterRegistrationBean<FixwireFilter> fixwireFilter() {
    FilterRegistrationBean<FixwireFilter> f = new FilterRegistrationBean<>(new FixwireFilter());
    f.setOrder(Ordered.HIGHEST_PRECEDENCE);
    return f;
  }

  /** Sends what is left when the app stops. */
  @PreDestroy
  public void close() {
    Fixwire.close(2000);
  }
}
