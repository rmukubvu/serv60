package com.serv60.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class AudienceValidatorTest {
  final AudienceValidator validator = new AudienceValidator(List.of("ops-client", "mobile-client"));

  Jwt token(String claim, Object value) {
    return Jwt.withTokenValue("token").header("alg", "none").claim(claim, value).build();
  }

  @Test
  void shouldAcceptATokenIssuedToOneOfOurClients() {
    assertThat(validator.validate(token("aud", List.of("other", "ops-client"))).hasErrors()).isFalse();
    assertThat(validator.validate(token("client_id", "mobile-client")).hasErrors()).isFalse();
    assertThat(validator.validate(token("azp", "ops-client")).hasErrors()).isFalse();
  }

  @Test
  void shouldRejectATokenForAnotherClientOrWithNoClient() {
    assertThat(validator.validate(token("client_id", "someone-elses-app")).hasErrors()).isTrue();
    assertThat(validator.validate(token("sub", "user")).hasErrors()).isTrue();
  }

  @Test
  void shouldAcceptAnyClientWhenNoAudienceIsConfigured() {
    assertThat(new AudienceValidator(List.of()).validate(token("client_id", "anything")).hasErrors()).isFalse();
  }

  @Test
  void shouldIgnoreBlankConfiguredAudiences() {
    assertThat(new SecurityProperties(true, null, List.of(" ", "a "), false).audiences()).containsExactly("a");
    assertThat(new SecurityProperties(true, null, null, false).audiences()).isEmpty();
  }
}
