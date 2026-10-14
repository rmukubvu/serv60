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
        || !authentication.isAuthenticated() || !JwtClaims.belongsToHub(token, hubId, properties.trustCognitoCustomAttributes())) {
      throw new AccessDeniedException("The authenticated principal is not a member of this hub");
    }
  }

  /** An operator of this hub, or an ADMIN when the resource has no hub. */
  public void requireOperatorOf(UUID hubId, Authentication authentication) {
    if (!isOperatorOf(hubId, authentication)) {
      throw new AccessDeniedException("The authenticated principal does not operate this hub");
    }
  }

  /**
   * Whether the caller operates this hub (an ADMIN when there is no hub). With security disabled
   * every caller is treated as an operator, matching the other checks, which then pass too.
   */
  public boolean isOperatorOf(UUID hubId, Authentication authentication) {
    if (!properties.enabled()) return true;
    return authentication instanceof JwtAuthenticationToken token
        && authentication.isAuthenticated() && JwtClaims.operatesHub(token, hubId, properties.trustCognitoCustomAttributes());
  }
}
