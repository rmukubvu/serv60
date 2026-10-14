package com.serv60.booking.internal.application;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SqsException;

/**
 * Publishes leased outbox events to SQS. Failures back off exponentially with jitter; an event
 * that fails {@code max-attempts} times is dead-lettered instead of retried forever.
 */
@Component
@ConditionalOnProperty(name = "serv60.outbox.sqs.enabled", havingValue = "true")
public class SqsOutboxPublisher {
  private static final Logger log = LoggerFactory.getLogger(SqsOutboxPublisher.class);
  private static final int BATCH_SIZE = 25;
  private static final Duration LEASE = Duration.ofSeconds(60);
  static final Duration BASE_RETRY = Duration.ofSeconds(30);
  static final Duration MAX_RETRY = Duration.ofMinutes(15);
  static final String METRIC = "serv60.outbox.publish";

  private final OutboxLeaseService leases;
  private final SqsClient sqs;
  private final String queueUrl;
  private final int maxAttempts;
  private final MeterRegistry metrics;

  public SqsOutboxPublisher(OutboxLeaseService leases, SqsClient sqs,
                            @Value("${serv60.outbox.sqs.queue-url}") String queueUrl,
                            @Value("${serv60.outbox.sqs.max-attempts:10}") int maxAttempts,
                            MeterRegistry metrics) {
    if (queueUrl == null || queueUrl.isBlank()) throw new IllegalArgumentException("SQS queue URL is required when the publisher is enabled");
    if (maxAttempts < 1) throw new IllegalArgumentException("serv60.outbox.sqs.max-attempts must be positive");
    this.leases = leases;
    this.sqs = sqs;
    this.queueUrl = queueUrl;
    this.maxAttempts = maxAttempts;
    this.metrics = metrics;
  }

  @Scheduled(fixedDelayString = "${serv60.outbox.sqs.poll-ms:1000}")
  public void publishDueEvents() {
    List<OutboxLeaseService.OutboxEvent> events = leases.claim(BATCH_SIZE, LEASE);
    for (var event : events) publishOne(event);
  }

  private void publishOne(OutboxLeaseService.OutboxEvent event) {
    var sample = Timer.start(metrics);
    String outcome = "published";
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
      if (event.attemptCount() >= maxAttempts) {
        outcome = "dead_lettered";
        log.error("Outbox event dead-lettered eventId={} type={} attempts={} error={}",
            event.id(), event.eventType(), event.attemptCount(), error.getMessage());
        leases.deadLetter(event.id(), boundedError(error));
      } else {
        outcome = "retried";
        var delay = retryDelay(event.attemptCount(), ThreadLocalRandom.current().nextDouble());
        log.warn("Outbox event publish failed eventId={} attempt={} retryIn={} error={}",
            event.id(), event.attemptCount(), delay, error.getMessage());
        leases.release(event.id(), boundedError(error), delay);
      }
    } finally {
      sample.stop(metrics.timer(METRIC + ".duration", "outcome", outcome));
      metrics.counter(METRIC, "outcome", outcome).increment();
    }
  }

  /**
   * Exponential backoff from 30 s, capped at 15 min, plus up to 25% jitter so a failed batch does
   * not retry in lockstep.
   */
  static Duration retryDelay(int attempt, double jitterFraction) {
    int exponent = Math.min(Math.max(attempt, 1) - 1, 20);
    long base = Math.min(BASE_RETRY.toMillis() << exponent, MAX_RETRY.toMillis());
    return Duration.ofMillis(base + (long) (base * 0.25 * jitterFraction));
  }

  private String boundedError(RuntimeException error) {
    String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    return message.length() <= 1000 ? message : message.substring(0, 1000);
  }
}
