package com.serv60;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sqs.SqsClient;

class PlatformAwsConfigurationTest {
  private final PlatformAwsConfiguration configuration = new PlatformAwsConfiguration();

  @Test
  void propertiesCaptureConfiguredTimeoutsAndEndpoint() {
    var properties = configuration.platformAwsProperties("af-south-1", "http://localhost:4566", 5000, 2000);

    assertThat(properties.region()).isEqualTo("af-south-1");
    assertThat(properties.endpointOverride()).isEqualTo("http://localhost:4566");
    assertThat(properties.apiCallTimeout()).isEqualTo(Duration.ofSeconds(5));
    assertThat(properties.apiCallAttemptTimeout()).isEqualTo(Duration.ofSeconds(2));
  }

  @Test
  void sqsClientCanBeBuiltWithoutNetworkAccess() {
    var properties = new PlatformAwsConfiguration.PlatformAwsProperties(
        "af-south-1", "http://localhost:4566", Duration.ofSeconds(5), Duration.ofSeconds(2));
    try (SqsClient client = configuration.sqsClient(properties)) {
      assertThat(client.serviceName()).isEqualTo("sqs");
    }
  }
}
