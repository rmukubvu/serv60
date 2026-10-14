package com.serv60.hubs.api;

/** A kind of bookable physical resource at a hub, stored by name in {@code resource.kind}. */
public enum ResourceKind {
  EXPRESS_BAY("EXPRESS"),
  BARBER_CHAIR("BARBER"),
  MASSAGE_ROOM("MASSAGE"),
  WORKSPACE("WORK");

  private final String codePrefix;

  ResourceKind(String codePrefix) {
    this.codePrefix = codePrefix;
  }

  /** Prefix for generated resource codes such as {@code EXPRESS-01}. */
  public String codePrefix() {
    return codePrefix;
  }
}
