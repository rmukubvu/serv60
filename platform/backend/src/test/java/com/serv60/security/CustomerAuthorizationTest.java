package com.serv60.security;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class CustomerAuthorizationTest {
  private static final UUID CUSTOMER = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-000000000002");

  @Test
  void disabledSecurityAllowsLocalRequests() {
    new CustomerAuthorization(new SecurityProperties(false)).require(CUSTOMER, null);
  }

  @Test
  void customerClaimAndSubjectAreAccepted() {
    var authorization = new CustomerAuthorization(new SecurityProperties(true));
    authorization.require(CUSTOMER, token("customer_id", CUSTOMER.toString()));
    authorization.require(CUSTOMER, subjectToken(CUSTOMER.toString()));
  }

  @Test
  void operatorRolesCanAccessAnyCustomer() {
    var jwt = Jwt.withTokenValue("token").header("alg", "none")
        .claim("roles", List.of("HUB_OPERATOR")).build();
    new CustomerAuthorization(new SecurityProperties(true)).require(OTHER,
        authenticated(jwt));
  }

  @Test
  void missingOrWrongCustomerIsDenied() {
    var authorization = new CustomerAuthorization(new SecurityProperties(true));
    assertThatThrownBy(() -> authorization.require(CUSTOMER, null))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> authorization.require(CUSTOMER, token("customer_id", OTHER.toString())))
        .isInstanceOf(AccessDeniedException.class);
  }

  private JwtAuthenticationToken token(String claim, String value) {
    var jwt = Jwt.withTokenValue("token").header("alg", "none").claim(claim, value).build();
    return authenticated(jwt);
  }

  private JwtAuthenticationToken subjectToken(String subject) {
    var jwt = Jwt.withTokenValue("token").header("alg", "none").subject(subject).build();
    return authenticated(jwt);
  }

  private JwtAuthenticationToken authenticated(Jwt jwt) {
    return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("SCOPE_serv60")));
  }
}
