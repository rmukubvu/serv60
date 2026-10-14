package com.serv60.security;

import java.util.stream.Stream;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** @deprecated Use {@link JwtClaims}. Kept as a thin alias for existing call sites. */
@Deprecated
final class CognitoClaims {
  private CognitoClaims() {}

  static Object claim(JwtAuthenticationToken token, String name, String cognitoName) {
    return JwtClaims.claim(token, name, cognitoName);
  }

  static Stream<String> values(Object value) {
    return JwtClaims.values(value);
  }
}
