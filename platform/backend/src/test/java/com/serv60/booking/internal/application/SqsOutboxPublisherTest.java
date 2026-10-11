package com.serv60.booking.internal.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SqsException;

@ExtendWith(MockitoExtension.class)
class SqsOutboxPublisherTest {
  @Mock OutboxLeaseService leases;
  @Mock SqsClient sqs;

  @Test
  void publishesAndAcknowledgesClaimedEvent() {
    var event = event();
    when(leases.claim(25, java.time.Duration.ofSeconds(60))).thenReturn(List.of(event));
    var publisher = new SqsOutboxPublisher(leases, sqs, "http://localhost:4566/000000000000/serv60-events");

    publisher.publishDueEvents();

    verify(sqs).sendMessage(any(SendMessageRequest.class));
    verify(leases).markPublished(event.id());
  }

  @Test
  void releasesLeaseWhenSqsReturnsAServiceFailure() {
    var event = event();
    when(leases.claim(25, java.time.Duration.ofSeconds(60))).thenReturn(List.of(event));
    doThrow(SqsException.builder().statusCode(503).message("temporary failure").build())
        .when(sqs).sendMessage(any(SendMessageRequest.class));
    var publisher = new SqsOutboxPublisher(leases, sqs, "http://localhost:4566/000000000000/serv60-events");

    publisher.publishDueEvents();

    verify(leases).release(event.id(), "temporary failure", java.time.Duration.ofSeconds(30));
  }

  private static OutboxLeaseService.OutboxEvent event() {
    return new OutboxLeaseService.OutboxEvent(UUID.randomUUID(), "VisitBundleHeld", UUID.randomUUID(), "{}", 1,
        OffsetDateTime.now().plusMinutes(1));
  }
}
