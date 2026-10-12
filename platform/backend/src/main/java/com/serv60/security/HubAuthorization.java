package com.serv60.security;

import java.util.Collection;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

@Component
public class HubAuthorization {
  private final SecurityProperties properties;

  public HubAuthorization(SecurityProperties properties) { this.properties = properties; }

  public void require(UUID hubId, Authentication authentication) {
    if (!properties.enabled()) return;
    if (!(authentication instanceof JwtAuthenticationToken token)
        || !authentication.isAuthenticated() || !hasHubClaim(token, hubId)) {
      throw new AccessDeniedException("The authenticated principal is not a member of this hub");
    }
  }

  private boolean hasHubClaim(JwtAuthenticationToken token, UUID hubId) {
    Object claim = CognitoClaims.claim(token, "hub_ids", "custom:hub_ids");
    return CognitoClaims.values(claim).map(String::trim).anyMatch(hubId.toString()::equals);
  }
}
