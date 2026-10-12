package com.serv60.concierge.api;

/** A bounded, retryable assistant failure; manual booking remains available. */
public final class AssistantUnavailable extends RuntimeException {
  public AssistantUnavailable(String message) {
    super(message);
  }

  public AssistantUnavailable(String message, Throwable cause) {
    super(message, cause);
  }
}
