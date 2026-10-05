package io.fixwire.spring;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import io.fixwire.Fixwire;
import io.fixwire.logback.FixwireAppender;
import io.fixwire.servlet.FixwireFilter;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.boot.restclient.RestTemplateCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.util.ClassUtils;

/**
 * Sets Fixwire up in a Spring Boot app from the {@code fixwire.*} properties: the SDK, the servlet
 * filter first in the chain, tracing of {@code RestClient} and {@code RestTemplate} requests, and
 * Logback records as breadcrumbs and events. Frames of the application's package are its own.
 */
@AutoConfiguration
@EnableConfigurationProperties(FixwireProperties.class)
public class FixwireAutoConfiguration {
  /** The SDK, set up when the context starts and closed when it stops. */
  @Bean
  @ConditionalOnMissingBean
  public FixwireLifecycle fixwireLifecycle(FixwireProperties properties, BeanFactory beanFactory) {
    List<String> inApp = new ArrayList<>(properties.getInAppIncludes());
    if (inApp.isEmpty() && AutoConfigurationPackages.has(beanFactory)) {
      inApp.addAll(AutoConfigurationPackages.get(beanFactory));
    }
    return new FixwireLifecycle(properties, inApp);
  }

  /** Closes the SDK with the context, sending what is left. */
  public static final class FixwireLifecycle implements DisposableBean {
    FixwireLifecycle(FixwireProperties p, List<String> inApp) {
      Fixwire.init(
          o -> {
            o.setDsn(p.getDsn());
            o.setRelease(p.getRelease());
            o.setEnvironment(p.getEnvironment());
            o.setSampleRate(p.getSampleRate());
            o.setTracesSampleRate(p.getTracesSampleRate());
            o.setTracePropagationTargets(p.getTracePropagationTargets());
            o.setInAppIncludes(inApp);
            o.setSendDefaultPii(p.isSendDefaultPii());
            o.setDebug(p.isDebug());
          });
      if (p.getLogging().isEnabled()
          && ClassUtils.isPresent("ch.qos.logback.classic.LoggerContext", null)) {
        Logback.attach(p.getLogging());
      }
    }

    @Override
    public void destroy() {
      Fixwire.close(2000);
    }
  }

  /** Attaches the appender to Logback's root logger, when Logback is the logging system. */
  static final class Logback {
    private static final String NAME = "FIXWIRE";

    static void attach(FixwireProperties.Logging settings) {
      if (!(LoggerFactory.getILoggerFactory() instanceof LoggerContext)) {
        return; // Logback is there, but another logging system is in use
      }
      LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
      Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);
      if (root.getAppender(NAME) != null) {
        return;
      }
      FixwireAppender appender = new FixwireAppender();
      appender.setName(NAME);
      appender.setContext(context);
      appender.setBreadcrumbLevel(settings.getBreadcrumbLevel());
      appender.setEventLevel(settings.getEventLevel());
      appender.start();
      root.addAppender(appender);
    }
  }

  /** Each request: its own scope, crashes, release health and a server span. */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
  @ConditionalOnClass(FilterRegistrationBean.class)
  static class Servlet {
    @Bean
    @ConditionalOnMissingBean(name = "fixwireFilter")
    FilterRegistrationBean<FixwireFilter> fixwireFilter() {
      FilterRegistrationBean<FixwireFilter> f = new FilterRegistrationBean<>(new FixwireFilter());
      f.setOrder(Ordered.HIGHEST_PRECEDENCE); // first, to see every request and escaping exception
      return f;
    }
  }

  /** RestClient requests as client spans. */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(RestClientCustomizer.class)
  static class RestClients {
    @Bean
    RestClientCustomizer fixwireRestClientCustomizer() {
      return builder -> builder.requestInterceptor(new FixwireClientHttpRequestInterceptor());
    }
  }

  /** RestTemplate requests as client spans. */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(RestTemplateCustomizer.class)
  static class RestTemplates {
    @Bean
    RestTemplateCustomizer fixwireRestTemplateCustomizer() {
      return template -> template.getInterceptors().add(new FixwireClientHttpRequestInterceptor());
    }
  }
}
