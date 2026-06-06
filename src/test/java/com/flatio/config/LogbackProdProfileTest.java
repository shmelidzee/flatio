package com.flatio.config;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.ConsoleAppender;
import net.logstash.logback.encoder.LogstashEncoder;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that logback-spring.xml correctly configures LogstashEncoder
 * on the root logger when the prod Spring profile is active.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
        "spring.autoconfigure.exclude=" +
            "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration," +
            "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration," +
            "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration," +
            "org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration"
    }
)
@ActiveProfiles("prod")
class LogbackProdProfileTest {

  @Test
  void should_configure_logstash_encoder_on_root_logger_when_prod_profile_active() {
    // Given
    var context = (LoggerContext) LoggerFactory.getILoggerFactory();

    // When
    var rootLogger = context.getLogger(Logger.ROOT_LOGGER_NAME);
    var jsonAppender = rootLogger.getAppender("JSON_CONSOLE");

    // Then
    assertThat(jsonAppender).isNotNull();
    assertThat(jsonAppender).isInstanceOf(ConsoleAppender.class);

    var consoleAppender = (ConsoleAppender<?>) jsonAppender;
    assertThat(consoleAppender.getEncoder()).isInstanceOf(LogstashEncoder.class);
  }
}
