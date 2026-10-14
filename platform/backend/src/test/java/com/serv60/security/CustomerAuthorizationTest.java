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
  void operatorsAreNotTheCustomer() {
    var jwt = Jwt.withTokenValue("token").header("alg", "none")
        .claim("roles", List.of("HUB_OPERATOR")).build();

    assertThatThrownBy(() -> new CustomerAuthorization(new SecurityProperties(true)).require(OTHER, authenticated(jwt)))
        .isInstanceOf(AccessDeniedException.class);
  }

  static final UUID HUB_A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
  static final UUID HUB_B = UUID.fromString("00000000-0000-0000-0000-00000000000b");

  JwtAuthenticationToken operator(String role, UUID hub) {
    return authenticated(Jwt.withTokenValue("token").header("alg", "none").subject("staff")
        .claim("roles", List.of(role)).claim("hub_ids", List.of(hub.toString())).build());
  }

  @Test
  void anOperatorOfTheResourcesHubMayActForItsCustomer() {
    new CustomerAuthorization(new SecurityProperties(true))
        .requireCustomerOrHubOperator(OTHER, HUB_A, operator("HUB_OPERATOR", HUB_A));
  }

  @Test
  void anOperatorOfAnotherHubIsRefused() {
    var authorization = new CustomerAuthorization(new SecurityProperties(true));

    assertThatThrownBy(() -> authorization.requireCustomerOrHubOperator(OTHER, HUB_B, operator("HUB_OPERATOR", HUB_A)))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void aResourceWithNoHubNeedsAnAdmin() {
    var authorization = new CustomerAuthorization(new SecurityProperties(true));

    assertThatThrownBy(() -> authorization.requireCustomerOrHubOperator(OTHER, null, operator("HUB_OPERATOR", HUB_A)))
        .isInstanceOf(AccessDeniedException.class);
    authorization.requireCustomerOrHubOperator(OTHER, null, operator("ADMIN", HUB_A));
  }

  @Test
  void theCustomerPassesWithoutAnyHubRole() {
    new CustomerAuthorization(new SecurityProperties(true))
        .requireCustomerOrHubOperator(CUSTOMER, HUB_B, token("customer_id", CUSTOMER.toString()));
  }

  @Test
  void cognitoCustomCustomerClaimIsAcceptedOnlyWhenTrusted() {
    var jwt = Jwt.withTokenValue("token").header("alg", "none").subject("someone-else")
        .claim("custom:customer_id", CUSTOMER.toString()).build();
    var trusted = new SecurityProperties(true, null, List.of(), true);

    new CustomerAuthorization(trusted).require(CUSTOMER, authenticated(jwt));
    assertThatThrownBy(() -> new CustomerAuthorization(new SecurityProperties(true))
            .require(CUSTOMER, authenticated(jwt)))
        .isInstanceOf(AccessDeniedException.class);
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

  @Test
  void resolvesOnlyVerifiedCustomerScopeOrExplicitDemoIdentity() {
    var id=java.util.UUID.randomUUID();
    var demo=new CustomerAuthorization(new SecurityProperties(false,id));
    org.assertj.core.api.Assertions.assertThat(demo.customerId(null)).isEqualTo(id);
    var secure=new CustomerAuthorization(new SecurityProperties(true));
    org.assertj.core.api.Assertions.assertThatThrownBy(()->secure.customerId(null)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    for (var claim: java.util.List.of("customer_id","sub")) {
      var jwt=org.springframework.security.oauth2.jwt.Jwt.withTokenValue("test").header("alg","none").subject("invalid").claim(claim,id.toString()).build();
      org.assertj.core.api.Assertions.assertThat(secure.customerId(new org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken(jwt,java.util.List.of()))).isEqualTo(id);
    }
    var clerkUser = org.springframework.security.oauth2.jwt.Jwt.withTokenValue("test")
        .header("alg", "none").subject("user_2abcClerkExample").build();
    var expected = java.util.UUID.nameUUIDFromBytes(
        ("serv60:clerk:user_2abcClerkExample").getBytes(java.nio.charset.StandardCharsets.UTF_8));
    org.assertj.core.api.Assertions.assertThat(secure.customerId(
            new org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken(
                clerkUser, java.util.List.of())))
        .isEqualTo(expected);
  }

  @Test
  void malformedCustomerClaimIsRejectedRatherThanReplacedByTheSubject() {
    var authorization = new CustomerAuthorization(new SecurityProperties(true));
    var jwt = Jwt.withTokenValue("token").header("alg", "none")
        .subject(CUSTOMER.toString()).claim("customer_id", "not-a-uuid").build();

    assertThatThrownBy(() -> authorization.customerId(authenticated(jwt)))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> authorization.require(CUSTOMER, authenticated(jwt)))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void clerkSubjectMapsToStableCustomerUuid() {
    var authorization = new CustomerAuthorization(new SecurityProperties(true));
    var customer = java.util.UUID.nameUUIDFromBytes(
        ("serv60:clerk:user_abc").getBytes(java.nio.charset.StandardCharsets.UTF_8));
    authorization.require(customer, subjectToken("user_abc"));
  }
}
