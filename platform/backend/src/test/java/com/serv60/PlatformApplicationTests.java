package com.serv60;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class PlatformApplicationTests {
  @Test
  void moduleBoundariesHaveNoCycles() {
    ApplicationModules.of(PlatformApplication.class).verify();
  }
}
