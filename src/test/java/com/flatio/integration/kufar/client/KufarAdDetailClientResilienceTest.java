package com.flatio.integration.kufar.client;

import com.flatio.repository.AdminAuditLogRepository;
import com.flatio.repository.CityRepository;
import com.flatio.repository.CurrencyRepository;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
        "telegram.bot.webhook-secret-token=test-secret",
        "JWT_SECRET_KEY=test-secret-key-for-kufar-resilience-test-minimum-256-bits-long",
        // Issue #328: connector-kufar-detail is one RateLimiter instance shared by up to 6
        // concurrent Kufar sync jobs (limit-for-period=1). The production config
        // (limit-refresh-period=2s, timeout-duration=15s) covers the worst-case queue of
        // (6 - 1) * 2s = 10s plus margin. These overrides scale that same ratio down 10x
        // (limit-refresh-period=200ms, timeout-duration=1500ms -> worst case queue
        // (6 - 1) * 200ms = 1000ms, still comfortably under the 1500ms timeout) purely so the
        // concurrency test below runs in ~1s of wall-clock time instead of ~10s, keeping the
        // fast unit-test suite fast; the mechanism under test — threads queueing for a shared
        // permit and the timeout needing to exceed the worst-case queue depth — is identical.
        "resilience4j.ratelimiter.instances.connector-kufar-detail.limit-for-period=1",
        "resilience4j.ratelimiter.instances.connector-kufar-detail.limit-refresh-period=200ms",
        "resilience4j.ratelimiter.instances.connector-kufar-detail.timeout-duration=1500ms"
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
  private CurrencyRepository currencyRepository;

  @MockBean
  private UserSavedSearchRepository userSavedSearchRepository;

  @MockBean
  private CityRepository cityRepository;

  @MockBean
  private SyncRunRepository syncRunRepository;

  @MockBean
  private SubscriptionRepository subscriptionRepository;

  @MockBean
  private NotificationRepository notificationRepository;

  @MockBean
  private AdminAuditLogRepository adminAuditLogRepository;

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

  /**
   * Regression test for issue #328: with a single {@code connector-kufar-detail} RateLimiter
   * instance shared by all Kufar sync jobs (up to 6 running concurrently), a thread must be able
   * to queue for its permit and still get served within {@code timeout-duration} — it must not
   * be pushed into {@link KufarAdDetailClient#fetchPreciseAddressFallback} purely because
   * sibling jobs got the permit first.
   */
  @Test
  @SuppressWarnings({"rawtypes", "unchecked"})
  void should_grant_permit_to_all_threads_when_kufar_sync_jobs_contend_for_shared_rate_limiter()
      throws InterruptedException {
    // Given — six concurrent Kufar sync jobs (apartment/room/house rent/sale — see #328) share
    // the single connector-kufar-detail RateLimiter bean. The mocked detail page always returns
    // a valid __NEXT_DATA__ address instantly, so any observed fallback (null) can only be
    // caused by rate-limiter contention, not by a slow or failing HTTP call.
    int concurrentSyncJobs = 6;
    String expectedAddress = "Минск";
    String html = "<script id=\"__NEXT_DATA__\" type=\"application/json\">"
        + "{\"props\":{\"initialState\":{\"adView\":{\"data\":{\"address\":\"" + expectedAddress + "\"}}}}}"
        + "</script>";
    RestClient.RequestHeadersUriSpec uriSpec = mock(RestClient.RequestHeadersUriSpec.class);
    RestClient.RequestHeadersSpec headersSpec = mock(RestClient.RequestHeadersSpec.class);
    RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);
    when(restClient.get()).thenReturn(uriSpec);
    doReturn(headersSpec).when(uriSpec).uri(anyString());
    when(headersSpec.retrieve()).thenReturn(responseSpec);
    when(responseSpec.body(String.class)).thenReturn(html);

    // When — all six calls are released at the same instant, so they genuinely contend for the
    // single shared permit instead of merely running one after another
    List<String> results = fetchConcurrently(concurrentSyncJobs);

    // Then — every thread receives the real address; none is pushed into
    // fetchPreciseAddressFallback purely for having to wait behind sibling sync jobs
    assertThat(results).hasSize(concurrentSyncJobs);
    assertThat(results).allMatch(expectedAddress::equals);
  }

  /**
   * Submits {@code callCount} concurrent {@link KufarAdDetailClient#fetchPreciseAddress(String)}
   * calls, releasing them all at the same instant via a latch so they genuinely contend for the
   * shared {@code connector-kufar-detail} permit, and returns their results in submission order.
   */
  private List<String> fetchConcurrently(int callCount) throws InterruptedException {
    CountDownLatch readyLatch = new CountDownLatch(callCount);
    CountDownLatch startLatch = new CountDownLatch(1);
    List<Callable<String>> tasks = new ArrayList<>();
    for (int i = 0; i < callCount; i++) {
      String adLink = "https://re.kufar.by/vi/" + (1067400900 + i);
      tasks.add(() -> {
        readyLatch.countDown();
        startLatch.await();
        return adDetailClient.fetchPreciseAddress(adLink);
      });
    }

    ExecutorService pool = Executors.newFixedThreadPool(callCount);
    try {
      List<Future<String>> futures = new ArrayList<>();
      for (Callable<String> task : tasks) {
        futures.add(pool.submit(task));
      }
      readyLatch.await();
      startLatch.countDown();

      List<String> results = new ArrayList<>();
      for (Future<String> future : futures) {
        results.add(future.get(10, TimeUnit.SECONDS));
      }
      return results;
    } catch (ExecutionException | TimeoutException e) {
      throw new AssertionError("Concurrent fetchPreciseAddress calls did not complete as expected", e);
    } finally {
      pool.shutdownNow();
    }
  }
}
