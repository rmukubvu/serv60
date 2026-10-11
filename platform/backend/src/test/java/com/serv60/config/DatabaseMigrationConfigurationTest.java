package com.serv60.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class DatabaseMigrationConfigurationTest {
  @Test
  void createsFlywayAgainstTheApplicationDataSource() {
    DataSource dataSource = mock(DataSource.class);

    Flyway flyway = new DatabaseMigrationConfiguration().serv60Flyway(dataSource);

    assertThat(flyway.getConfiguration().getDataSource()).isSameAs(dataSource);
    assertThat(flyway.getConfiguration().getLocations())
        .anyMatch(location -> location.getDescriptor().equals("classpath:db/migration"));
  }
}
