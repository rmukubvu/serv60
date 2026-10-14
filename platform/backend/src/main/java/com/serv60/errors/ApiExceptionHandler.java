package com.serv60.errors;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * One error shape, {@code {"error": "..."}}, for every module. Typed domain errors carry
 * customer-safe messages; anything unexpected is logged with its cause and answered generically so
 * internal details never reach a caller.
 */
@RestControllerAdvice
public class ApiExceptionHandler {
  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
  static final String INTERNAL = "Something went wrong on our side. Please try again";
  static final String UNAVAILABLE = "The service is temporarily unavailable. Please try again shortly";

  @ExceptionHandler(NotFound.class)
  @ResponseStatus(HttpStatus.NOT_FOUND)
  Map<String, String> notFound(NotFound error) { return body(error.getMessage()); }

  @ExceptionHandler(IllegalArgumentException.class)
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  Map<String, String> badRequest(IllegalArgumentException error) { return body(error.getMessage()); }

  @ExceptionHandler(Conflict.class)
  @ResponseStatus(HttpStatus.CONFLICT)
  Map<String, String> conflict(Conflict error) { return body(error.getMessage()); }

  @ExceptionHandler(DataIntegrityViolationException.class)
  @ResponseStatus(HttpStatus.CONFLICT)
  Map<String, String> integrity(DataIntegrityViolationException error) {
    log.warn("A database constraint rejected a write: {}", error.getMostSpecificCause().getMessage());
    return body("This change conflicts with an existing record. Refresh and try again");
  }

  @ExceptionHandler(IllegalStateException.class)
  @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
  Map<String, String> internal(IllegalStateException error) {
    log.error("Unexpected state error", error);
    return body(INTERNAL);
  }

  @ExceptionHandler(DataAccessException.class)
  ResponseEntity<Map<String, String>> database(DataAccessException error) {
    log.error("Database call failed", error);
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", "5").body(body(UNAVAILABLE));
  }

  @ExceptionHandler(AccessDeniedException.class)
  @ResponseStatus(HttpStatus.FORBIDDEN)
  Map<String, String> forbidden(AccessDeniedException error) { return body(error.getMessage()); }

  @ExceptionHandler(SecurityException.class)
  @ResponseStatus(HttpStatus.FORBIDDEN)
  Map<String, String> invalidWebhook(SecurityException error) { return body(error.getMessage()); }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  Map<String, String> invalidBody(MethodArgumentNotValidException error) {
    var field = error.getBindingResult().getFieldError();
    return body(field == null ? "Check the request details"
        : field.getField() + " " + field.getDefaultMessage());
  }

  @ExceptionHandler(HandlerMethodValidationException.class)
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  Map<String, String> invalidParameter(HandlerMethodValidationException error) {
    return body("Check the request parameters");
  }

  @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  Map<String, String> unreadable(Exception error) { return body("The request could not be read"); }

  @ExceptionHandler(MissingRequestHeaderException.class)
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  Map<String, String> missingHeader(MissingRequestHeaderException error) {
    return body("Missing required header " + error.getHeaderName());
  }

  @ExceptionHandler(MissingServletRequestParameterException.class)
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  Map<String, String> missingParameter(MissingServletRequestParameterException error) {
    return body("Missing required parameter " + error.getParameterName());
  }

  private static Map<String, String> body(String message) {
    return Map.of("error", message == null ? INTERNAL : message);
  }
}
