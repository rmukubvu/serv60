package com.serv60.errors;

/**
 * The requested resource does not exist, or is not visible to the caller. Mapped to 404. Extends
 * IllegalArgumentException so existing callers that treat it as invalid input keep working.
 */
public class NotFound extends IllegalArgumentException {
  public NotFound(String message) {
    super(message);
  }
}
