package com.serv60.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Puts a request id and the authenticated subject into the logging context for the whole request,
 * and echoes the id so callers can quote it. Runs after Spring Security, so the subject is known.
 * The subject is an opaque identity-provider id; no names, phone numbers or locations are logged.
 */
@Component
public class RequestCorrelationFilter extends OncePerRequestFilter {
  public static final String HEADER = "X-Request-Id";
  static final String REQUEST_ID = "requestId";
  static final String PRINCIPAL = "principal";
  private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                  FilterChain chain) throws ServletException, IOException {
    var incoming = request.getHeader(HEADER);
    // Never trust a caller-supplied id into the logs unless it is short and inert.
    var id = incoming != null && SAFE_ID.matcher(incoming).matches() ? incoming : UUID.randomUUID().toString();
    MDC.put(REQUEST_ID, id);
    response.setHeader(HEADER, id);
    var auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken)) {
      MDC.put(PRINCIPAL, auth.getName());
    }
    try {
      chain.doFilter(request, response);
    } finally {
      MDC.remove(REQUEST_ID);
      MDC.remove(PRINCIPAL);
    }
  }
}
