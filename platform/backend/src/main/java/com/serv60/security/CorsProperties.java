package com.serv60.security;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configures the browser origins that may call the platform API. */
@ConfigurationProperties(prefix = "serv60.cors")
public record CorsProperties(List<String> allowedOrigins) {
  public CorsProperties {
    if (allowedOrigins == null || allowedOrigins.isEmpty()) {
      throw new IllegalArgumentException("serv60.cors.allowed-origins must contain an origin");
    }
    allowedOrigins = allowedOrigins.stream()
        .map(String::trim)
        .filter(origin -> !origin.isBlank())
        .toList();
    if (allowedOrigins.isEmpty()) {
      throw new IllegalArgumentException("serv60.cors.allowed-origins must contain an origin");
    }
  }
}
