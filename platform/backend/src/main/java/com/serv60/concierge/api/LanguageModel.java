package com.serv60.concierge.api;

import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/** Boundary for a model that can request only application-defined tools. */
public interface LanguageModel {
  boolean configured();

  JsonNode complete(List<Map<String, Object>> messages, List<Map<String, Object>> tools);
}
