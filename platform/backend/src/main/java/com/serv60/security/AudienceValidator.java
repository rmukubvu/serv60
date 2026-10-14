package com.serv60.security;

import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Accepts only tokens issued to one of our app clients. Cognito access tokens carry the client in
 * {@code client_id}, ID tokens in {@code aud}; Clerk session tokens use {@code azp}.
 */
final class AudienceValidator implements OAuth2TokenValidator<Jwt> {
  private static final OAuth2Error WRONG_AUDIENCE = new OAuth2Error(
      OAuth2ErrorCodes.INVALID_TOKEN, "The token was not issued to a Serv60 client", null);
  private final List<String> audiences;

  AudienceValidator(List<String> audiences) {
    this.audiences = List.copyOf(audiences);
  }

  @Override
  public OAuth2TokenValidatorResult validate(Jwt token) {
    if (audiences.isEmpty()) return OAuth2TokenValidatorResult.success();
    var presented = Stream.of(
            token.getAudience() == null ? Stream.<String>empty() : token.getAudience().stream(),
            values(token.getClaim("client_id")),
            values(token.getClaim("azp")))
        .flatMap(stream -> stream);
    return presented.anyMatch(audiences::contains)
        ? OAuth2TokenValidatorResult.success()
        : OAuth2TokenValidatorResult.failure(WRONG_AUDIENCE);
  }

  private static Stream<String> values(Object claim) {
    if (claim instanceof String value) return Stream.of(value);
    if (claim instanceof Collection<?> collection) return collection.stream().map(String::valueOf);
    return Stream.empty();
  }
}
