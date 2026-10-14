package com.serv60.security;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** Reads Serv60 authorization claims from Cognito or Clerk JWTs. */
final class JwtClaims {
  private JwtClaims() {}

  static Object claim(JwtAuthenticationToken token, String name, String alternateName) {
    var claims = token.getToken().getClaims();
    if (claims.containsKey(name)) return claims.get(name);
    if (claims.containsKey(alternateName)) return claims.get(alternateName);
    Object metadata = claims.get("public_metadata");
    if (metadata instanceof Map<?, ?> map && map.containsKey(name)) return map.get(name);
    Object metadataSnake = claims.get("publicMetadata");
    if (metadataSnake instanceof Map<?, ?> map && map.containsKey(name)) return map.get(name);
    return null;
  }

  static Stream<String> values(Object value) {
    if (value instanceof Collection<?> collection) return collection.stream().map(String::valueOf);
    if (value instanceof String string) return Stream.of(string.split("[, ]+"));
    return Stream.empty();
  }

  /**
   * Resolves the Serv60 customer UUID from an explicit claim, a UUID {@code sub},
   * or a stable name-based UUID derived from a Clerk-style subject ({@code user_…}).
   * A customer_id claim that is present but not a UUID is rejected: silently falling back to the
   * subject would move every customer to a different identity after a claim-mapping mistake.
   */
  static UUID customerId(JwtAuthenticationToken token) {
    Object claim = claim(token, "customer_id", "custom:customer_id");
    if (claim != null) {
      try {
        return UUID.fromString(String.valueOf(claim));
      } catch (IllegalArgumentException invalid) {
        throw new IllegalArgumentException("customer_id claim is not a UUID", invalid);
      }
    }
    String subject = token.getToken().getSubject();
    if (subject == null || subject.isBlank()) {
      throw new IllegalArgumentException("JWT subject is missing");
    }
    try {
      return UUID.fromString(subject);
    } catch (IllegalArgumentException ignored) {
      return UUID.nameUUIDFromBytes(("serv60:clerk:" + subject).getBytes(StandardCharsets.UTF_8));
    }
  }
}
