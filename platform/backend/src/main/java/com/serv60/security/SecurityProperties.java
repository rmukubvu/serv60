package com.serv60.security;

import java.util.List;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * @param audiences client ids a token must be issued for, matched against {@code aud},
 *     {@code client_id} (Cognito access tokens) or {@code azp} (Clerk). Empty accepts any client
 *     of the issuer, which is only safe when the issuer serves no other application.
 * @param trustCognitoCustomAttributes read {@code custom:customer_id} and {@code custom:hub_ids}.
 *     Leave off unless every app client's write access to those attributes is removed, or a user
 *     can edit their own attribute and become another customer or join a hub.
 */
@ConfigurationProperties(prefix = "serv60.security")
public record SecurityProperties(
    boolean enabled, UUID demoCustomerId, List<String> audiences, boolean trustCognitoCustomAttributes) {
  public SecurityProperties(boolean enabled) { this(enabled, null, List.of(), false); }

  public SecurityProperties(boolean enabled, UUID demoCustomerId) {
    this(enabled, demoCustomerId, List.of(), false);
  }

  @ConstructorBinding
  public SecurityProperties {
    if (demoCustomerId == null) demoCustomerId = UUID.fromString("00000000-0000-0000-0000-000000000001");
    audiences = audiences == null ? List.of()
        : audiences.stream().map(String::trim).filter(audience -> !audience.isEmpty()).toList();
  }
}
