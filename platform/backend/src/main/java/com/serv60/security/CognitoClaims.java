package com.serv60.security;

import java.util.Collection;
import java.util.stream.Stream;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

final class CognitoClaims {
  private CognitoClaims() {}

  static Object claim(JwtAuthenticationToken token, String name, String cognitoName) {
    var claims = token.getToken().getClaims();
    return claims.containsKey(name) ? claims.get(name) : claims.get(cognitoName);
  }

  static Stream<String> values(Object value) {
    if (value instanceof Collection<?> collection) return collection.stream().map(String::valueOf);
    if (value instanceof String string) return Stream.of(string.split("[, ]+"));
    return Stream.empty();
  }
}
