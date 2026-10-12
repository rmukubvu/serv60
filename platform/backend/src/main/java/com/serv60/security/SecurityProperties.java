package com.serv60.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "serv60.security")
public record SecurityProperties(boolean enabled, java.util.UUID demoCustomerId) {
  public SecurityProperties(boolean enabled) { this(enabled, null); }
  @org.springframework.boot.context.properties.bind.ConstructorBinding
  public SecurityProperties {
    if (demoCustomerId == null) demoCustomerId = java.util.UUID.fromString("00000000-0000-0000-0000-000000000001");
  }
}
