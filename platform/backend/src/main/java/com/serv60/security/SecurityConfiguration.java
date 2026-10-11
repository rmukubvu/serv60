package com.serv60.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.Customizer;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(SecurityProperties.class)
public class SecurityConfiguration {
  @Bean
  SecurityFilterChain apiSecurity(HttpSecurity http, SecurityProperties properties) throws Exception {
    http.csrf(AbstractHttpConfigurer::disable);
    if (properties.enabled()) {
      http.authorizeHttpRequests(authorize -> authorize
          .requestMatchers("/actuator/health/**").permitAll()
          .anyRequest().authenticated())
          .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));
    } else {
      http.authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll());
    }
    return http.build();
  }
}
