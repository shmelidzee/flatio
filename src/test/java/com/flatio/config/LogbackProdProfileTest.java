package com.flatio.config;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.ConsoleAppender;
import com.flatio.repository.SourceRepository;
import com.flatio.repository.UserAuthProviderRepository;
import com.flatio.repository.UserRepository;
import com.flatio.service.ListingIngestionService;
import net.logstash.logback.encoder.LogstashEncoder;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.annotation.DirtiesContext;
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
            "org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration",
        "telegram.bot.token=dummy-test-token",
        "telegram.bot.username=dummy_test_bot"
    }
)
@ActiveProfiles("prod")
// IT tests reset the global LoggerContext on teardown; forces fresh context to re-init Logback with prod profile.
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class LogbackProdProfileTest {

  // Replaces real beans which depend on JPA repositories not available in this test context
  @MockBean
  ListingIngestionService listingIngestionService;

  @MockBean
  SourceRepository sourceRepository;

  @MockBean
  UserRepository userRepository;

  @MockBean
  UserAuthProviderRepository userAuthProviderRepository;

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
