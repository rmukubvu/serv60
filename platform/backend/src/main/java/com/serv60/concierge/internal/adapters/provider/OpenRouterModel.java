package com.serv60.concierge.internal.adapters.provider;

import com.serv60.concierge.api.*;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.*;

/**
 * OpenRouter credentials stay server-side; redirects, automatic retries and unbounded responses are
 * disabled.
 */
@Component
public class OpenRouterModel implements LanguageModel {
  private final HttpClient client;
  private final URI endpoint;
  private final String key;
  private final String model;
  private final ObjectMapper json;
  private final MeterRegistry metrics;

  @Autowired
  public OpenRouterModel(
      @Value("${serv60.assistant.openrouter-key:}") String key,
      @Value("${serv60.assistant.model:}") String model,
      ObjectMapper json,
      MeterRegistry metrics) {
    this(
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build(),
        URI.create("https://openrouter.ai/api/v1/chat/completions"),
        key,
        model,
        json,
        metrics);
  }

  OpenRouterModel(
      HttpClient client,
      URI endpoint,
      String key,
      String model,
      ObjectMapper json,
      MeterRegistry metrics) {
    this.client = client;
    this.endpoint = endpoint;
    this.key = key;
    this.model = model;
    this.json = json;
    this.metrics = metrics;
  }

  @Override
  public boolean configured() {
    return key != null && !key.isBlank() && model != null && !model.isBlank();
  }

  @Override
  public JsonNode complete(List<Map<String, Object>> messages, List<Map<String, Object>> tools) {
    if (!configured())
      throw new AssistantUnavailable(
          "The assistant is not connected yet. You can still book manually");
    var sample = Timer.start(metrics);
    String outcome = "error";
    try {
      var body =
          json.writeValueAsString(
              Map.of(
                  "model",
                  model,
                  "messages",
                  messages,
                  "tools",
                  tools,
                  "tool_choice",
                  "auto",
                  "max_tokens",
                  700,
                  "provider",
                  Map.of("require_parameters", true, "data_collection", "deny")));
      var request =
          HttpRequest.newBuilder(endpoint)
              .timeout(Duration.ofSeconds(12))
              .header("Authorization", "Bearer " + key)
              .header("Content-Type", "application/json")
              .header("X-Title", "Serv60 booking assistant")
              .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
              .build();
      var response =
          client.send(
              request,
              HttpResponse.BodyHandlers.limiting(
                  HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8), 128_000));
      metrics
          .counter(
              "serv60.assistant.provider.responses",
              "status",
              Integer.toString(response.statusCode()))
          .increment();
      if (response.statusCode() != 200)
        throw new AssistantUnavailable(
            "The assistant is temporarily unavailable. Please try again or book manually");
      var message = json.readTree(response.body()).path("choices").path(0).path("message");
      if (!message.isObject()
          || (!message.path("content").isTextual() && !message.path("tool_calls").isArray())) {
        throw new AssistantUnavailable(
            "The assistant returned an incomplete response. Please try again");
      }
      outcome = "success";
      return message;
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new AssistantUnavailable("The assistant request was interrupted", error);
    } catch (IOException | tools.jackson.core.JacksonException error) {
      throw new AssistantUnavailable(
          "The assistant could not respond in time. Please try again or book manually", error);
    } finally {
      sample.stop(metrics.timer("serv60.assistant.provider.duration", "outcome", outcome));
      metrics.counter("serv60.assistant.provider.requests", "outcome", outcome).increment();
    }
  }
}
