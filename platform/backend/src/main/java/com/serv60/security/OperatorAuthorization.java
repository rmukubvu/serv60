package com.serv60.security;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

@Component
public class OperatorAuthorization {
  private final SecurityProperties properties;

  public OperatorAuthorization(SecurityProperties properties) { this.properties = properties; }

  public void require(Authentication authentication) {
    if (!properties.enabled()) return;
    if (!(authentication instanceof JwtAuthenticationToken token)
        || !authentication.isAuthenticated() || !hasOperatorRole(token)) {
      throw new AccessDeniedException("The authenticated principal is not a hub operator");
    }
  }

  private boolean hasOperatorRole(JwtAuthenticationToken token) {
    Object roles = CognitoClaims.claim(token, "roles", "cognito:groups");
    return CognitoClaims.values(roles)
        .anyMatch(role -> role.equals("HUB_OPERATOR") || role.equals("ADMIN"));
  }
}
