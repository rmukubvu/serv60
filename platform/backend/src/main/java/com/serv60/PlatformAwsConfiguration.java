package com.serv60;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;

@Configuration
@ConditionalOnProperty(name = "serv60.outbox.sqs.enabled", havingValue = "true")
public class PlatformAwsConfiguration {
  @Bean
  SqsClient sqsClient(PlatformAwsProperties properties) {
    var builder = SqsClient.builder()
        .region(Region.of(properties.region()))
        .overrideConfiguration(ClientOverrideConfiguration.builder()
            .apiCallTimeout(properties.apiCallTimeout())
            .apiCallAttemptTimeout(properties.apiCallAttemptTimeout())
            .build());
    if (properties.endpointOverride() != null && !properties.endpointOverride().isBlank()) {
      builder.endpointOverride(URI.create(properties.endpointOverride()));
    }
    return builder.build();
  }

  @Bean
  PlatformAwsProperties platformAwsProperties(
      @org.springframework.beans.factory.annotation.Value("${AWS_REGION:af-south-1}") String region,
      @org.springframework.beans.factory.annotation.Value("${SERV60_SQS_ENDPOINT:}") String endpoint,
      @org.springframework.beans.factory.annotation.Value("${SERV60_AWS_API_TIMEOUT_MS:5000}") long apiTimeoutMs,
      @org.springframework.beans.factory.annotation.Value("${SERV60_AWS_ATTEMPT_TIMEOUT_MS:2000}") long attemptTimeoutMs) {
    return new PlatformAwsProperties(region, endpoint, Duration.ofMillis(apiTimeoutMs), Duration.ofMillis(attemptTimeoutMs));
  }

  public record PlatformAwsProperties(String region, String endpointOverride,
                                      Duration apiCallTimeout, Duration apiCallAttemptTimeout) {}
}
