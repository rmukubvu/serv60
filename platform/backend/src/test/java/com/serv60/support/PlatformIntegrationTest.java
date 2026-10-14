package com.serv60.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;

/**
 * The one configuration for database integration tests: the shared PostgreSQL container, a fixed
 * clock, security and the expiry sweep off, and a configured express price. Keep test classes on
 * exactly this annotation so Spring reuses a single context across them.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@SpringBootTest(
    properties = {
      "serv60.booking.expiry-sweep-enabled=false",
      "serv60.security.enabled=false",
      "serv60.booking.express-price-minor=24900",
      "serv60.booking.hold-duration-seconds=600"
    })
@Import(FixedClock.class)
@ContextConfiguration(initializers = SharedPostgres.class)
public @interface PlatformIntegrationTest {}
