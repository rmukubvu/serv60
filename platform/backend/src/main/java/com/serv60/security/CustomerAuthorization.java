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

  /** Passes only for the customer themselves. Operators never pass here, whatever their hub. */
  public void require(UUID customerId, Authentication authentication) {
    if (!properties.enabled()) return;
    if (!(authentication instanceof JwtAuthenticationToken token)
        || !authentication.isAuthenticated()
        || !isCustomer(token, customerId)) {
      throw new AccessDeniedException("The authenticated principal cannot access this customer");
    }
  }

  /**
   * The customer themselves, or an operator of the hub the resource belongs to. A resource with no
   * hub needs an ADMIN. An operator of another hub is refused even though they are an operator.
   */
  public void requireCustomerOrHubOperator(UUID customerId, UUID hubId, Authentication authentication) {
    if (!properties.enabled()) return;
    if (authentication instanceof JwtAuthenticationToken token
        && authentication.isAuthenticated()
        && (isCustomer(token, customerId) || JwtClaims.operatesHub(token, hubId))) {
      return;
    }
    throw new AccessDeniedException("The authenticated principal cannot access this customer");
  }

  /** Resolves self-service ownership from the verified identity, never a request field. */
  public UUID customerId(Authentication authentication) {
    if (!properties.enabled()) return properties.demoCustomerId();
    if (!(authentication instanceof JwtAuthenticationToken token) || !authentication.isAuthenticated()) {
      throw new AccessDeniedException("Customer login is required");
    }
    try {
      return JwtClaims.customerId(token);
    } catch (IllegalArgumentException invalid) {
      throw new AccessDeniedException("Customer identity is not configured", invalid);
    }
  }

  private boolean isCustomer(JwtAuthenticationToken token, UUID customerId) {
    try {
      return customerId.equals(JwtClaims.customerId(token));
    } catch (IllegalArgumentException invalid) {
      return false;
    }
  }

}
