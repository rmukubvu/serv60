package com.serv60.concierge.internal.adapters.provider;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.serv60.concierge.api.AssistantUnavailable;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class OpenRouterModelTest {
  final JsonMapper json = JsonMapper.builder().build();
  final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
  final HttpClient client = mock(HttpClient.class);

  OpenRouterModel model() {
    return new OpenRouterModel(
        client,
        URI.create("https://example.test/chat"),
        "test-secret",
        "test-model",
        json,
        metrics);
  }

  @Test
  void shouldDisableWithoutCredentialsAndUseBoundedRequest() throws Exception {
    var disabled = new OpenRouterModel("", "", json, metrics);
    assertThat(disabled.configured()).isFalse();
    assertThatThrownBy(() -> disabled.complete(List.of(), List.of()))
        .isInstanceOf(AssistantUnavailable.class);
    HttpResponse<String> response = mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(200);
    when(response.body()).thenReturn("{\"choices\":[{\"message\":{\"content\":\"Ready\"}}]}");
    when(client.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(response);
    assertThat(
            model()
                .complete(List.of(Map.of("role", "user", "content", "Hello")), List.of())
                .path("content")
                .asText())
        .isEqualTo("Ready");
    var request = org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
    verify(client).send(request.capture(), any());
    assertThat(request.getValue().timeout().orElseThrow())
        .isEqualTo(java.time.Duration.ofSeconds(12));
    assertThat(metrics.counter("serv60.assistant.provider.requests", "outcome", "success").count())
        .isEqualTo(1);
  }

  @Test
  void shouldTranslateHttpMalformedIoAndInterruptFailures() throws Exception {
    HttpResponse<String> response = mock(HttpResponse.class);
    when(client.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(response);
    when(response.statusCode()).thenReturn(429);
    assertThatThrownBy(() -> model().complete(List.of(), List.of()))
        .isInstanceOf(AssistantUnavailable.class)
        .hasMessageNotContaining("test-secret");
    when(response.statusCode()).thenReturn(200);
    when(response.body()).thenReturn("{}", "broken");
    assertThatThrownBy(() -> model().complete(List.of(), List.of()))
        .isInstanceOf(AssistantUnavailable.class);
    assertThatThrownBy(() -> model().complete(List.of(), List.of()))
        .isInstanceOf(AssistantUnavailable.class);
    when(client.send(any(), any())).thenThrow(new IOException("network"));
    assertThatThrownBy(() -> model().complete(List.of(), List.of()))
        .isInstanceOf(AssistantUnavailable.class);
    doThrow(new InterruptedException()).when(client).send(any(), any());
    try {
      assertThatThrownBy(() -> model().complete(List.of(), List.of()))
          .isInstanceOf(AssistantUnavailable.class);
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted();
    }
  }
}
