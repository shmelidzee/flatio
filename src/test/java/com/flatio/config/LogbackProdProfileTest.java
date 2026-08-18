package com.flatio.config;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.ConsoleAppender;
import com.flatio.repository.AdminAuditLogRepository;
import com.flatio.repository.CityRepository;
import com.flatio.repository.ListingRepository;
import com.flatio.repository.PriceHistoryRepository;
import com.flatio.repository.SourceRepository;
import com.flatio.repository.SubscriptionRepository;
import com.flatio.repository.SyncRunRepository;
import com.flatio.repository.UserAuthProviderRepository;
import com.flatio.repository.UserRepository;
import com.flatio.repository.UserSavedSearchRepository;
import com.flatio.service.ListingIngestionService;
import com.flatio.telegram.handler.FlatioLongPollingBot;
import net.logstash.logback.encoder.LogstashEncoder;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationContext;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.telegram.telegrambots.webhook.starter.SpringTelegramWebhookBot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
            "org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration," +
            "org.telegram.telegrambots.longpolling.starter.TelegramBotStarterConfiguration," +
            "org.telegram.telegrambots.webhook.starter.TelegramBotStarterConfiguration",
        "telegram.bot.token=dummy-test-token",
        "telegram.bot.username=dummy_test_bot",
        // prod profile activates TelegramWebhookConfig, which requires this to build its bean;
        // the webhook starter's own auto-configuration is excluded above, so setWebhook is never called
        "telegram.bot.webhook-url=https://test.example.com",
        "JWT_SECRET_KEY=test-secret-key-for-logback-test-minimum-256-bits-long"
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

  @MockBean
  ListingRepository listingRepository;

  @MockBean
  PriceHistoryRepository priceHistoryRepository;

  @MockBean
  UserSavedSearchRepository userSavedSearchRepository;

  @MockBean
  CityRepository cityRepository;

  @MockBean
  SyncRunRepository syncRunRepository;

  @MockBean
  SubscriptionRepository subscriptionRepository;

  @MockBean
  AdminAuditLogRepository adminAuditLogRepository;

  @Autowired
  private ApplicationContext applicationContext;

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

  @Test
  void should_register_only_webhook_bot_when_prod_profile_active() {
    // Given / When — context loaded with the prod profile (see class annotations)

    // Then — webhook bot is present, no long-polling bot bean was created
    assertThat(applicationContext.getBean(SpringTelegramWebhookBot.class)).isNotNull();
    assertThatThrownBy(() -> applicationContext.getBean(FlatioLongPollingBot.class))
        .isInstanceOf(NoSuchBeanDefinitionException.class);
  }
}
