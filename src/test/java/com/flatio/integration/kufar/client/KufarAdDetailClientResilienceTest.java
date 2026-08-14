package com.flatio.integration.kufar.client;

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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies that {@link KufarAdDetailClient#fetchPreciseAddress(String)} degrades to {@code null}
 * through the <b>real</b> Resilience4j AOP proxy chain ({@code @RateLimiter} &rarr;
 * {@code @CircuitBreaker} &rarr; {@code @Retry} with {@code fallbackMethod}), rather than by
 * calling {@code fetchPreciseAddressFallback} directly as a plain method.
 *
 * <p>Technical Reviewer flagged that {@link KufarAdDetailClientTest} constructs the client via
 * {@code new KufarAdDetailClient(...)}, which bypasses the Spring AOP proxy entirely — the
 * fallback wiring declared on the annotations (config lookup by name, exception-type matching,
 * method-signature resolution via reflection) was never exercised end-to-end. This test closes
 * that gap by resolving the client as an actual Spring-managed (proxied) bean.
 *
 * <p>DataSource/JPA/Flyway autoconfiguration is excluded — this client needs no database — and
 * the repositories that other beans in the application context depend on are replaced with
 * mocks instead of a real Testcontainers-backed database, following the same lightweight
 * full-context recipe already used by {@code com.flatio.security.SecurityConfigTest} and
 * {@code com.flatio.config.LogbackProdProfileTest}.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
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
        "JWT_SECRET_KEY=test-secret-key-for-kufar-resilience-test-minimum-256-bits-long"
    }
)
class KufarAdDetailClientResilienceTest {

  private static final String AD_LINK = "https://re.kufar.by/vi/1067400926";

  // Replaces real beans which depend on JPA repositories not available in this test context
  @MockBean
  private ListingIngestionService listingIngestionService;

  @MockBean
  private SourceRepository sourceRepository;

  @MockBean
  private UserRepository userRepository;

  @MockBean
  private UserAuthProviderRepository userAuthProviderRepository;

  @MockBean
  private ListingRepository listingRepository;

  @MockBean
  private PriceHistoryRepository priceHistoryRepository;

  @MockBean
  private UserSavedSearchRepository userSavedSearchRepository;

  @MockBean
  private CityRepository cityRepository;

  @MockBean
  private SyncRunRepository syncRunRepository;

  @MockBean
  private SubscriptionRepository subscriptionRepository;

  @MockBean(name = "kufarAdDetailRestClient")
  private RestClient restClient;

  @Autowired
  private KufarAdDetailClient adDetailClient;

  @Test
  @SuppressWarnings({"rawtypes", "unchecked"})
  void should_return_null_through_real_aop_proxy_when_http_call_always_fails() {
    // Given — the mocked RestClient throws a plain RuntimeException (not one of the configured
    // connector-kufar-detail retry-exceptions), simulating a persistently unreachable ad detail
    // page. This exercises the real Resilience4j Retry -> fallbackMethod AOP chain end-to-end,
    // instead of calling fetchPreciseAddressFallback() directly as a plain method.
    RestClient.RequestHeadersUriSpec uriSpec = mock(RestClient.RequestHeadersUriSpec.class);
    RestClient.RequestHeadersSpec headersSpec = mock(RestClient.RequestHeadersSpec.class);
    RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);
    when(restClient.get()).thenReturn(uriSpec);
    doReturn(headersSpec).when(uriSpec).uri(anyString());
    when(headersSpec.retrieve()).thenReturn(responseSpec);
    when(responseSpec.body(String.class)).thenThrow(new RuntimeException("simulated network failure"));

    // When — invoked through the real Spring-managed bean (AOP proxy), not `new KufarAdDetailClient(...)`
    String result = adDetailClient.fetchPreciseAddress(AD_LINK);

    // Then — the proxy chain (RateLimiter -> CircuitBreaker -> Retry) routes the failure to
    // fetchPreciseAddressFallback via real AOP, degrading to null instead of propagating
    assertThat(result).isNull();
  }
}
