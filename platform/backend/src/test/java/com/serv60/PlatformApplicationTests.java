package com.serv60;

import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.boot.SpringApplication;
import org.mockito.MockedStatic;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;

class PlatformApplicationTests {
  @Test
  void moduleBoundariesHaveNoCycles() {
    ApplicationModules.of(PlatformApplication.class).verify();
  }

  @Test
  void mainDelegatesToSpringApplication() {
    String[] args = {"--spring.main.web-application-type=none"};
    ConfigurableApplicationContext context = mock(ConfigurableApplicationContext.class);
    try (MockedStatic<SpringApplication> application = org.mockito.Mockito.mockStatic(SpringApplication.class)) {
      application.when(() -> SpringApplication.run(PlatformApplication.class, args)).thenReturn(context);

      PlatformApplication.main(args);

      application.verify(() -> SpringApplication.run(PlatformApplication.class, args), times(1));
    }
  }
}
