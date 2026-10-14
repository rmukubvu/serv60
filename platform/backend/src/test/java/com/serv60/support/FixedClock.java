package com.serv60.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Every integration test runs at the same instant, so expiry and notice rules are deterministic. */
@TestConfiguration(proxyBeanMethods = false)
public class FixedClock {
  /** Monday 2035-01-01 08:00 in Africa/Johannesburg. */
  public static final Instant NOW = Instant.parse("2035-01-01T06:00:00Z");

  @Bean
  @Primary
  Clock fixedTestClock() {
    return Clock.fixed(NOW, ZoneOffset.UTC);
  }
}
