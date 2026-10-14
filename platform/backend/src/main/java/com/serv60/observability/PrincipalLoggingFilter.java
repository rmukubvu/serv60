package com.serv60.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Adds the authenticated subject to the logging context. Ordered after the Spring Security filter
 * chain, so the bearer token has been verified by the time it runs. The subject is an opaque
 * identity-provider id; no names, phone numbers or locations are logged.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class PrincipalLoggingFilter extends OncePerRequestFilter {
  static final String PRINCIPAL = "principal";

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                  FilterChain chain) throws ServletException, IOException {
    var auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
      chain.doFilter(request, response);
      return;
    }
    MDC.put(PRINCIPAL, auth.getName());
    try {
      chain.doFilter(request, response);
    } finally {
      MDC.remove(PRINCIPAL);
    }
  }
}
