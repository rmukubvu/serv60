package com.serv60.security;

import java.util.Collection;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

@Component
public class CustomerAuthorization {
  private final SecurityProperties properties;

  public CustomerAuthorization(SecurityProperties properties) {
    this.properties = properties;
  }

  public void require(UUID customerId, Authentication authentication) {
    if (!properties.enabled()) return;
    if (!(authentication instanceof JwtAuthenticationToken token)
        || !authentication.isAuthenticated()
        || !isOperator(token) && !isCustomer(token, customerId)) {
      throw new AccessDeniedException("The authenticated principal cannot access this customer");
    }
  }

  private boolean isCustomer(JwtAuthenticationToken token, UUID customerId) {
    var claim = token.getToken().getClaims().get("customer_id");
    return customerId.toString().equals(String.valueOf(claim))
        || customerId.toString().equals(token.getToken().getSubject());
  }

  private boolean isOperator(JwtAuthenticationToken token) {
    Object roles = token.getToken().getClaims().get("roles");
    if (roles instanceof Collection<?> values) {
      return values.stream().map(String::valueOf)
          .anyMatch(role -> role.equals("HUB_OPERATOR") || role.equals("ADMIN"));
    }
    return roles instanceof String value
        && (value.contains("HUB_OPERATOR") || value.contains("ADMIN"));
  }
}
