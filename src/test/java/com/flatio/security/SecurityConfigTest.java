package com.flatio.security;

import com.flatio.repository.AdminAuditLogRepository;
import com.flatio.repository.BlacklistEntryRepository;
import com.flatio.repository.CityRepository;
import com.flatio.repository.CurrencyRepository;
import com.flatio.repository.ExchangeRateRepository;
import com.flatio.repository.FavoriteRepository;
import com.flatio.repository.ListingRepository;
import com.flatio.repository.NotificationRepository;
import com.flatio.repository.PriceHistoryRepository;
import com.flatio.repository.SourceRepository;
import com.flatio.repository.SubscriptionRepository;
import com.flatio.repository.SyncRunRepository;
import com.flatio.repository.UserAuthProviderRepository;
import com.flatio.repository.UserRepository;
import com.flatio.repository.UserSavedSearchRepository;
import com.flatio.service.ListingIngestionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the access rules in {@link SecurityConfig}, in particular that the Telegram webhook
 * endpoint ({@code POST /<bot-token>}) is reachable without authentication — Telegram cannot
 * send a JWT — while everything else not explicitly permitted is still denied (fail-closed).
 *
 * <p>This guards against a regression found in production: the webhook path was not covered by
 * any {@code permitAll()} rule, so {@code anyRequest().denyAll()} rejected every update from
 * Telegram with HTTP 403, even though the webhook itself was registered successfully.
 */
@SpringBootTest(
    properties = {
        "spring.autoconfigure.exclude=" +
            "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration," +
            "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration," +
            "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration," +
            "org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration," +
            "org.telegram.telegrambots.webhook.starter.TelegramBotStarterConfiguration",
        "telegram.bot.token=test_token:123",
        "telegram.bot.username=dummy_test_bot",
        "telegram.bot.webhook-url=https://test.example.com",
        "telegram.bot.webhook-secret-token=test-secret",
        "JWT_SECRET_KEY=test-secret-key-for-security-config-test-minimum-256-bits-long"
    }
)
@AutoConfigureMockMvc
class SecurityConfigTest {

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
  CurrencyRepository currencyRepository;

  @MockBean
  UserSavedSearchRepository userSavedSearchRepository;

  @MockBean
  CityRepository cityRepository;

  @MockBean
  SyncRunRepository syncRunRepository;

  @MockBean
  SubscriptionRepository subscriptionRepository;

  @MockBean
  NotificationRepository notificationRepository;

  @MockBean
  AdminAuditLogRepository adminAuditLogRepository;

  @MockBean
  FavoriteRepository favoriteRepository;

  @MockBean
  BlacklistEntryRepository blacklistEntryRepository;

  @MockBean
  ExchangeRateRepository exchangeRateRepository;

  @Autowired
  private MockMvc mockMvc;

  @Test
  void should_not_return_forbidden_when_posting_to_telegram_webhook_path_without_auth() throws Exception {
    // Given / When — POST to "/<bot-token>" with no Authorization header, mirroring Telegram.
    // The X-Telegram-Bot-Api-Secret-Token header matches the "telegram.bot.webhook-secret-token"
    // test property so TelegramWebhookSecretFilter lets the request through — this test verifies
    // the SecurityConfig permitAll() rule, not the secret-token filter (issue #374).
    // Then — must not be rejected by Spring Security (403); any other status is the webhook
    // starter's own concern, not this test's
    mockMvc.perform(post("/test_token:123")
            .header("X-Telegram-Bot-Api-Secret-Token", "test-secret")
            .contentType("application/json").content("{}"))
        .andExpect(result -> {
          int status = result.getResponse().getStatus();
          if (status == 403) {
            throw new AssertionError("Webhook path was rejected by Spring Security with 403 Forbidden");
          }
        });
  }

  @Test
  void should_return_forbidden_when_posting_to_an_unmapped_path_without_auth() throws Exception {
    // Given / When / Then — fail-closed default still applies to everything else
    mockMvc.perform(post("/some-random-unmapped-path"))
        .andExpect(status().isForbidden());
  }

  @Test
  void should_not_return_forbidden_when_posting_to_telegram_auth_endpoint_without_token() throws Exception {
    // Given / When — /api/v1/auth/** must be reachable without a JWT, since it is where JWTs
    // are issued. The request itself is rejected further downstream (401, invalid initData),
    // but Spring Security must not block it with 403 before it reaches the controller.
    // Then
    mockMvc.perform(post("/api/v1/auth/telegram")
            .contentType("application/json")
            .content("{\"initData\":\"irrelevant\"}"))
        .andExpect(result -> {
          int status = result.getResponse().getStatus();
          if (status == 403) {
            throw new AssertionError("/api/v1/auth/telegram was rejected by Spring Security with 403 Forbidden");
          }
        });
  }

  @Test
  void should_not_return_forbidden_when_posting_to_telegram_login_widget_endpoint_without_token() throws Exception {
    // Given / When — same rule as /api/v1/auth/telegram: this is also where a JWT is issued,
    // so it cannot itself require one.
    // Then
    mockMvc.perform(post("/api/v1/auth/telegram-login-widget")
            .contentType("application/json")
            .content("{\"id\":1,\"first_name\":\"Test\",\"auth_date\":1700000000,\"hash\":\"irrelevant\"}"))
        .andExpect(result -> {
          int status = result.getResponse().getStatus();
          if (status == 403) {
            throw new AssertionError(
                "/api/v1/auth/telegram-login-widget was rejected by Spring Security with 403 Forbidden");
          }
        });
  }

  @Test
  void should_allow_patch_method_in_cors_preflight_response() throws Exception {
    // Given / When — CORS preflight (OPTIONS) for a cross-origin PATCH request, mirroring a
    // browser client on another origin calling SubscriptionController#pause (issue #421)
    // Then — Access-Control-Allow-Methods must include PATCH, otherwise the browser blocks the
    // real PATCH request before it ever reaches Spring Security or the JWT filter
    mockMvc.perform(options("/api/v1/subscriptions/1/pause")
            .header("Origin", "http://localhost:3000")
            .header("Access-Control-Request-Method", "PATCH"))
        .andExpect(status().isOk())
        .andExpect(header().string("Access-Control-Allow-Methods", containsString("PATCH")));
  }
}
