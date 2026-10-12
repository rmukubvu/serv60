package com.serv60.security;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class OperatorAuthorizationTest {
  @Test
  void disabledSecurityAllowsLocalDispatch() {
    new OperatorAuthorization(new SecurityProperties(false)).require(null);
  }

  @Test
  void operatorRoleIsRequiredWhenSecurityIsEnabled() {
    var authorization = new OperatorAuthorization(new SecurityProperties(true));
    var customer = new JwtAuthenticationToken(jwtWithRoles("CUSTOMER"),
        List.of(new SimpleGrantedAuthority("SCOPE_serv60")));

    assertThatThrownBy(() -> authorization.require(customer))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void hubOperatorAndAdminRolesAreAccepted() {
    var authorization = new OperatorAuthorization(new SecurityProperties(true));
    authorization.require(new JwtAuthenticationToken(jwtWithRoles("HUB_OPERATOR"), List.of()));
    authorization.require(new JwtAuthenticationToken(jwtWithRoles("ADMIN"), List.of()));
  }

  @Test
  void cognitoGroupsAreAcceptedAsOperatorRoles() {
    var authorization = new OperatorAuthorization(new SecurityProperties(true));
    var jwt = Jwt.withTokenValue("token").header("alg", "none")
        .claim("cognito:groups", List.of("HUB_OPERATOR")).build();

    authorization.require(new JwtAuthenticationToken(jwt, List.of()));
  }

  private Jwt jwtWithRoles(String roles) {
    return Jwt.withTokenValue("token").header("alg", "none").claim("roles", roles).build();
  }
}
