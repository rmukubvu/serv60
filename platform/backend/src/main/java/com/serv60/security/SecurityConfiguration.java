package com.serv60.security;

import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.Customizer;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
@EnableConfigurationProperties({SecurityProperties.class, CorsProperties.class})
public class SecurityConfiguration {
  @Bean
  CorsConfigurationSource corsConfigurationSource(CorsProperties properties) {
    var configuration = new CorsConfiguration();
    configuration.setAllowedOrigins(properties.allowedOrigins());
    configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
    // Webhook signature headers are server-to-server only; a browser has no reason to send them.
    configuration.setAllowedHeaders(List.of(
        "Authorization", "Content-Type", "Idempotency-Key", "X-Request-Id"));
    configuration.setExposedHeaders(List.of("Location", "X-Request-Id"));
    configuration.setAllowCredentials(false);
    configuration.setMaxAge(3_600L);

    var source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", configuration);
    return source;
  }

  @Bean
  SecurityFilterChain apiSecurity(
      HttpSecurity http, SecurityProperties properties, CorsConfigurationSource corsConfigurationSource,
      ObjectProvider<JwtDecoder> decoders)
      throws Exception {
    http.csrf(AbstractHttpConfigurer::disable)
        .cors(cors -> cors.configurationSource(corsConfigurationSource));
    if (properties.enabled()) {
      requireJwtDecoder(decoders);
      http.authorizeHttpRequests(authorize -> authorize
          .requestMatchers("/actuator/health/**").permitAll()
          // Metrics and build info describe the whole platform: administrators only, not any token.
          .requestMatchers("/actuator/**").access((authentication, context) ->
              new AuthorizationDecision(isAdmin(authentication.get())))
          .requestMatchers("/v1/payments/webhooks/**").permitAll()
          .anyRequest().authenticated())
          .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));
    } else {
      http.authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll());
    }
    return http.build();
  }

  static boolean isAdmin(Authentication authentication) {
    return authentication instanceof JwtAuthenticationToken token
        && authentication.isAuthenticated()
        && JwtClaims.values(JwtClaims.claim(token, "roles", "cognito:groups")).anyMatch("ADMIN"::equals);
  }

  /** Fails startup with an actionable message instead of a missing-bean stack trace. */
  static void requireJwtDecoder(ObjectProvider<JwtDecoder> decoders) {
    if (decoders.getIfAvailable() == null) {
      throw new IllegalStateException(
          "serv60.security.enabled=true requires spring.security.oauth2.resourceserver.jwt"
              + ".issuer-uri or .jwk-set-uri. Set SECURITY_ENABLED=false only for local development.");
    }
  }
}
