package com.serv60.security;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

class HubAuthorizationTest {
  private static final UUID HUB = UUID.fromString("00000000-0000-0000-0000-000000000060");
  private static final UUID OTHER_HUB = UUID.fromString("00000000-0000-0000-0000-000000000061");

  @Test
  void disabledSecurityAllowsLocalRequests() {
    new HubAuthorization(new SecurityProperties(false)).require(HUB, null);
  }

  @Test
  void authenticatedJwtWithHubClaimIsAllowed() {
    var token = token(List.of(HUB.toString()));

    new HubAuthorization(new SecurityProperties(true)).require(HUB, token);
  }

  @Test
  void missingOrWrongHubClaimIsDenied() {
    var authorization = new HubAuthorization(new SecurityProperties(true));
    var token = token(List.of(OTHER_HUB.toString()));

    assertThatThrownBy(() -> authorization.require(HUB, null)).isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> authorization.require(HUB, token)).isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void commaSeparatedHubClaimIsAccepted() {
    var jwt = Jwt.withTokenValue("token").header("alg", "none")
        .claim("hub_ids", "00000000-0000-0000-0000-000000000061, " + HUB).build();

    new HubAuthorization(new SecurityProperties(true)).require(HUB,
        new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("SCOPE_serv60"))));
  }

  @Test
  void cognitoCustomHubClaimIsAcceptedOnlyWhenTrusted() {
    var jwt = Jwt.withTokenValue("token").header("alg", "none")
        .claim("custom:hub_ids", List.of(HUB.toString())).build();
    var token = new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("SCOPE_serv60")));

    new HubAuthorization(new SecurityProperties(true, null, List.of(), true)).require(HUB, token);
    assertThatThrownBy(() -> new HubAuthorization(new SecurityProperties(true)).require(HUB, token))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void onlyAnOperatorRoleAtTheHubOperatesIt() {
    var authorization = new HubAuthorization(new SecurityProperties(true));
    var operator = roleToken("HUB_OPERATOR", HUB);
    var member = token(List.of(HUB.toString()));

    org.assertj.core.api.Assertions.assertThat(authorization.isOperatorOf(HUB, operator)).isTrue();
    org.assertj.core.api.Assertions.assertThat(authorization.isOperatorOf(OTHER_HUB, operator)).isFalse();
    org.assertj.core.api.Assertions.assertThat(authorization.isOperatorOf(HUB, member)).isFalse();
    org.assertj.core.api.Assertions.assertThat(authorization.isOperatorOf(null, operator)).isFalse();
    org.assertj.core.api.Assertions.assertThat(authorization.isOperatorOf(null, roleToken("ADMIN", HUB))).isTrue();
    assertThatThrownBy(() -> authorization.requireOperatorOf(OTHER_HUB, operator))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void disabledSecurityTreatsEveryCallerAsAnOperator() {
    org.assertj.core.api.Assertions.assertThat(
        new HubAuthorization(new SecurityProperties(false)).isOperatorOf(HUB, null)).isTrue();
  }

  private JwtAuthenticationToken roleToken(String role, UUID hub) {
    var jwt = Jwt.withTokenValue("token").header("alg", "none").claim("roles", List.of(role))
        .claim("hub_ids", List.of(hub.toString())).build();
    return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("SCOPE_serv60")));
  }

  private JwtAuthenticationToken token(List<String> hubIds) {
    var jwt = Jwt.withTokenValue("token").header("alg", "none").claim("hub_ids", hubIds).build();
    return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("SCOPE_serv60")));
  }
}
