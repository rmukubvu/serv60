package com.serv60.support;

import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * One PostgreSQL container for the whole test JVM, migrated once by the first context that uses
 * it. Every integration test points at it through this initializer, which is part of Spring's
 * context cache key, so test classes with the same configuration also share one context.
 */
public final class SharedPostgres
    implements ApplicationContextInitializer<ConfigurableApplicationContext> {
  private static final PostgreSQLContainer CONTAINER = startOnce();

  private static PostgreSQLContainer startOnce() {
    var container = new PostgreSQLContainer("postgres:18-alpine");
    container.start();
    // Ryuk removes it in CI; the hook also cleans up where Ryuk is disabled.
    Runtime.getRuntime().addShutdownHook(new Thread(container::stop));
    return container;
  }

  @Override
  public void initialize(ConfigurableApplicationContext context) {
    TestPropertyValues.of(
            "spring.datasource.url=" + CONTAINER.getJdbcUrl(),
            "spring.datasource.username=" + CONTAINER.getUsername(),
            "spring.datasource.password=" + CONTAINER.getPassword())
        .applyTo(context.getEnvironment());
  }
}
