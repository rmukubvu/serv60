package com.serv60.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class RequestCorrelationFilterTest {
  final RequestCorrelationFilter filter = new RequestCorrelationFilter();
  final Map<String, String> seen = new HashMap<>();

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  MockHttpServletResponse run(String incomingId) throws Exception {
    var request = new MockHttpServletRequest("GET", "/v1/customer/appointments");
    if (incomingId != null) request.addHeader(RequestCorrelationFilter.HEADER, incomingId);
    var response = new MockHttpServletResponse();
    filter.doFilter(request, response, (req, res) -> {
      seen.put("requestId", MDC.get("requestId"));
      seen.put("principal", MDC.get("principal"));
    });
    return response;
  }

  @Test
  void shouldTagTheRequestWithTheCallersSafeIdAndSubjectThenClearIt() throws Exception {
    SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
        "user_2abc", null, List.of(new SimpleGrantedAuthority("SCOPE_serv60"))));

    var response = run("req-123");

    assertThat(seen).containsEntry("requestId", "req-123").containsEntry("principal", "user_2abc");
    assertThat(response.getHeader(RequestCorrelationFilter.HEADER)).isEqualTo("req-123");
    assertThat(MDC.get("requestId")).isNull();
    assertThat(MDC.get("principal")).isNull();
  }

  @Test
  void shouldReplaceAnUnsafeOrMissingIdAndLogNoAnonymousPrincipal() throws Exception {
    var response = run("bad id\nforged=log-line");
    var generated = seen.get("requestId");
    run(null);

    assertThat(generated).hasSize(36).isEqualTo(response.getHeader(RequestCorrelationFilter.HEADER));
    assertThat(seen.get("requestId")).hasSize(36).isNotEqualTo(generated);
    assertThat(seen.get("principal")).isNull();
  }
}
