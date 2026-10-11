package com.serv60.booking.internal.application;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SqsException;

@Component
@ConditionalOnProperty(name = "serv60.outbox.sqs.enabled", havingValue = "true")
public class SqsOutboxPublisher {
  private static final Logger log = LoggerFactory.getLogger(SqsOutboxPublisher.class);
  private static final int BATCH_SIZE = 25;
  private static final Duration LEASE = Duration.ofSeconds(60);
  private static final Duration RETRY_AFTER = Duration.ofSeconds(30);

  private final OutboxLeaseService leases;
  private final SqsClient sqs;
  private final String queueUrl;

  public SqsOutboxPublisher(OutboxLeaseService leases, SqsClient sqs,
                            @org.springframework.beans.factory.annotation.Value("${serv60.outbox.sqs.queue-url}") String queueUrl) {
    if (queueUrl == null || queueUrl.isBlank()) throw new IllegalArgumentException("SQS queue URL is required when the publisher is enabled");
    this.leases = leases;
    this.sqs = sqs;
    this.queueUrl = queueUrl;
  }

  @Scheduled(fixedDelayString = "${serv60.outbox.sqs.poll-ms:1000}")
  public void publishDueEvents() {
    List<OutboxLeaseService.OutboxEvent> events = leases.claim(BATCH_SIZE, LEASE);
    for (var event : events) publishOne(event);
  }

  private void publishOne(OutboxLeaseService.OutboxEvent event) {
    try {
      sqs.sendMessage(SendMessageRequest.builder()
          .queueUrl(queueUrl)
          .messageBody(event.payload())
          .messageAttributes(Map.of(
              "eventType", MessageAttributeValue.builder().dataType("String").stringValue(event.eventType()).build(),
              "aggregateId", MessageAttributeValue.builder().dataType("String").stringValue(event.aggregateId().toString()).build()))
          .build());
      leases.markPublished(event.id());
    } catch (SqsException | SdkClientException error) {
      log.warn("Outbox event publish failed eventId={} attempt={} error={}", event.id(), event.attemptCount(), error.getMessage());
      leases.release(event.id(), boundedError(error), RETRY_AFTER);
    }
  }

  private String boundedError(RuntimeException error) {
    String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    return message.length() <= 1000 ? message : message.substring(0, 1000);
  }
}
