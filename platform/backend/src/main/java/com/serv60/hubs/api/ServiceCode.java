package com.serv60.hubs.api;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Every service a hub can sell, with the resource kind it occupies. This is the single mapping
 * between service codes and resources; SQL fragments are generated from it rather than repeated.
 */
public enum ServiceCode {
  EXPRESS_OIL(ResourceKind.EXPRESS_BAY, false),
  BARBER(ResourceKind.BARBER_CHAIR, true),
  MASSAGE(ResourceKind.MASSAGE_ROOM, true),
  PRIVATE_WORK(ResourceKind.WORKSPACE, true);

  private final ResourceKind resourceKind;
  private final boolean complimentary;

  ServiceCode(ResourceKind resourceKind, boolean complimentary) {
    this.resourceKind = resourceKind;
    this.complimentary = complimentary;
  }

  /** The resource kind one booking of this service occupies. */
  public ResourceKind resourceKind() {
    return resourceKind;
  }

  /** Whether the service can be added free to an express vehicle visit. */
  public boolean complimentary() {
    return complimentary;
  }

  /** Parses a wire code; empty for null or codes the platform does not offer. */
  public static Optional<ServiceCode> of(String code) {
    return Arrays.stream(values()).filter(value -> value.name().equals(code)).findFirst();
  }

  /** Parses a wire code or rejects it as a bad request. */
  public static ServiceCode require(String code) {
    return of(code).orElseThrow(() -> new IllegalArgumentException("Unknown service: " + code));
  }

  /** Complimentary codes in a stable order, for prompts, tool schemas and filters. */
  public static List<String> complimentaryCodes() {
    return Arrays.stream(values()).filter(ServiceCode::complimentary).map(Enum::name).toList();
  }

  /** SQL list literal of complimentary codes, for example {@code ('BARBER','MASSAGE')}. */
  public static String complimentaryCodesSql() {
    return complimentaryCodes().stream().map(code -> "'" + code + "'")
        .collect(Collectors.joining(",", "(", ")"));
  }

  /** SQL CASE mapping a service-code column to the resource kind it occupies. */
  public static String resourceKindSql(String codeColumn) {
    return Arrays.stream(values())
        .map(code -> "when '" + code.name() + "' then '" + code.resourceKind.name() + "'")
        .collect(Collectors.joining(" ", "case " + codeColumn + " ", " end"));
  }

  /** SQL CASE naming the complimentary service a resource-kind column belongs to, else the fallback. */
  public static String complimentaryCodeSql(String kindColumn, String fallback) {
    return Arrays.stream(values()).filter(ServiceCode::complimentary)
        .map(code -> "when '" + code.resourceKind.name() + "' then '" + code.name() + "'")
        .collect(Collectors.joining(" ", "case " + kindColumn + " ", " else " + fallback + " end"));
  }
}
