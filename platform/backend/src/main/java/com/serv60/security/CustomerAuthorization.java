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
    var claim = CognitoClaims.claim(token, "customer_id", "custom:customer_id");
    return customerId.toString().equals(String.valueOf(claim))
        || customerId.toString().equals(token.getToken().getSubject());
  }

  private boolean isOperator(JwtAuthenticationToken token) {
    Object roles = CognitoClaims.claim(token, "roles", "cognito:groups");
    return CognitoClaims.values(roles)
        .anyMatch(role -> role.equals("HUB_OPERATOR") || role.equals("ADMIN"));
  }
}
