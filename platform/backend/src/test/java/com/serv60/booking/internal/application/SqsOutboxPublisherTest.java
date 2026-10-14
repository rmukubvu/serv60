package com.serv60.booking.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SqsException;

class SqsOutboxPublisherTest {
  static final String QUEUE = "http://localhost:4566/000000000000/serv60-events";
  final OutboxLeaseService leases = mock(OutboxLeaseService.class);
  final SqsClient sqs = mock(SqsClient.class);
  final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
  final SqsOutboxPublisher publisher = new SqsOutboxPublisher(leases, sqs, QUEUE, 3, metrics);

  @Test
  void publishesAndAcknowledgesClaimedEvent() {
    var event = event(1);
    when(leases.claim(25, Duration.ofSeconds(60))).thenReturn(List.of(event));

    publisher.publishDueEvents();

    verify(sqs).sendMessage(org.mockito.ArgumentMatchers.<SendMessageRequest>argThat(request ->
        request.messageAttributes().get("eventId").stringValue().equals(event.id().toString())));
    verify(leases).markPublished(event.id());
    assertThat(metrics.counter(SqsOutboxPublisher.METRIC, "outcome", "published").count()).isEqualTo(1);
  }

  @Test
  void backsOffABelowLimitFailureInsteadOfRetryingImmediately() {
    var event = event(2);
    when(leases.claim(25, Duration.ofSeconds(60))).thenReturn(List.of(event));
    doThrow(SqsException.builder().statusCode(503).message("temporary failure").build())
        .when(sqs).sendMessage(any(SendMessageRequest.class));

    publisher.publishDueEvents();

    verify(leases).release(eq(event.id()), eq("temporary failure"),
        org.mockito.ArgumentMatchers.argThat(delay ->
            !delay.minusSeconds(60).isNegative() && delay.compareTo(Duration.ofSeconds(75)) <= 0));
    verify(leases, never()).deadLetter(any(), any());
    assertThat(metrics.counter(SqsOutboxPublisher.METRIC, "outcome", "retried").count()).isEqualTo(1);
  }

  @Test
  void deadLettersAnEventThatExhaustedItsAttempts() {
    var event = event(3);
    when(leases.claim(25, Duration.ofSeconds(60))).thenReturn(List.of(event));
    doThrow(SqsException.builder().statusCode(400).message("message too large").build())
        .when(sqs).sendMessage(any(SendMessageRequest.class));

    publisher.publishDueEvents();

    verify(leases).deadLetter(event.id(), "message too large");
    verify(leases, never()).release(any(), any(), any());
    assertThat(metrics.counter(SqsOutboxPublisher.METRIC, "outcome", "dead_lettered").count()).isEqualTo(1);
  }

  @Test
  void growsTheDelayExponentiallyUpToTheCapWithBoundedJitter() {
    assertThat(SqsOutboxPublisher.retryDelay(1, 0)).isEqualTo(Duration.ofSeconds(30));
    assertThat(SqsOutboxPublisher.retryDelay(3, 0)).isEqualTo(Duration.ofSeconds(120));
    assertThat(SqsOutboxPublisher.retryDelay(40, 0)).isEqualTo(Duration.ofMinutes(15));
    assertThat(SqsOutboxPublisher.retryDelay(1, 1)).isEqualTo(Duration.ofMillis(37_500));
  }

  @Test
  void refusesMissingQueueOrAttemptLimit() {
    assertThatThrownBy(() -> new SqsOutboxPublisher(leases, sqs, " ", 3, metrics))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new SqsOutboxPublisher(leases, sqs, QUEUE, 0, metrics))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static OutboxLeaseService.OutboxEvent event(int attempt) {
    return new OutboxLeaseService.OutboxEvent(UUID.randomUUID(), "VisitBundleHeld", UUID.randomUUID(), "{}", attempt,
        OffsetDateTime.now().plusMinutes(1));
  }
}
