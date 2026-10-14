package com.serv60.errors;

/**
 * A business rule rejected the request in the resource's current state (stale revision, slot
 * taken, outside the notice period). Mapped to 409 with its message shown to the caller. Any other
 * IllegalStateException is treated as an internal error and never shown.
 */
public class Conflict extends IllegalStateException {
  public Conflict(String message) {
    super(message);
  }
}
