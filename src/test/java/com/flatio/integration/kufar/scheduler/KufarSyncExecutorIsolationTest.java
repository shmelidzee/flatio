package com.flatio.integration.kufar.scheduler;

import com.flatio.domain.country.Country;
import com.flatio.domain.source.Source;
import com.flatio.integration.kufar.client.KufarApartmentRentConnector;
import com.flatio.integration.onliner.scheduler.OnlinerFullSyncJob;
import com.flatio.integration.onliner.scheduler.OnlinerSaleFullSyncJob;
import com.flatio.integration.realt.scheduler.RealtFullSyncJob;
import com.flatio.integration.realt.scheduler.RealtHouseRentFullSyncJob;
import com.flatio.integration.realt.scheduler.RealtHouseSaleFullSyncJob;
import com.flatio.integration.realt.scheduler.RealtRoomFullSyncJob;
import com.flatio.integration.realt.scheduler.RealtRoomSaleFullSyncJob;
import com.flatio.integration.realt.scheduler.RealtSaleFullSyncJob;
import com.flatio.repository.AdminAuditLogRepository;
import com.flatio.repository.BlacklistEntryRepository;
import com.flatio.repository.CityRepository;
import com.flatio.repository.CurrencyRepository;
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
import com.flatio.service.SourceService;
import com.flatio.service.SyncRunService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Verifies, through the <b>real</b> Spring AOP {@code @Async} proxy, the key property required by
 * issue #332: a Kufar {@code @Scheduled} sync job's entry method hands its (potentially
 * {@code connector-kufar-detail} RateLimiter-blocking, see #328) body off to the dedicated
 * {@code kufarSyncExecutor} thread pool and returns control to its caller — the shared
 * {@code ThreadPoolTaskScheduler} cron thread used by Onliner/Realt sync and the
 * health-freshness watchdog — without waiting for that body to finish.
 *
 * <p>Follows the same lightweight full-context recipe as
 * {@code KufarAdDetailClientResilienceTest}: DataSource/JPA/Flyway autoconfiguration is excluded
 * and the repositories other beans in the context depend on are mocked, since only the real
 * {@code @Async}/{@code @Scheduled} AOP proxy chain around {@link KufarApartmentRentDeltaSyncJob}
 * and {@link KufarApartmentRentFullSyncJob} is under test here — calling
 * {@code new KufarApartmentRentDeltaSyncJob(...)} directly would bypass that proxy entirely, the
 * same gap {@code KufarAdDetailClientResilienceTest} closed for the RateLimiter/CircuitBreaker/
 * Retry chain.
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
        "JWT_SECRET_KEY=test-secret-key-for-kufar-executor-isolation-test-min-256-bits-long",
        // The real ScheduledAnnotationBeanPostProcessor registers KufarApartmentRentDeltaSyncJob
        // / FullSyncJob's actual @Scheduled cron triggers in this full context. Left at their
        // production defaults (e.g. every 10 minutes), a trigger can fire mid-test — racing the
        // manual runDeltaSync()/runFullSync() calls below and the Mockito stubbing that precedes
        // them (observed once on a loaded `clean build` machine: a genuine cron-fired invocation
        // hit sourceService.findByCodeOrThrow() in the split second between two Given-block
        // when(...) calls, before it was stubbed, causing a spurious NPE unrelated to the
        // property under test). A future calendar date is not reliable here — Spring's own
        // Scheduled#CRON_DISABLED sentinel ("-") is the documented way to make
        // ScheduledAnnotationBeanPostProcessor skip registering the trigger altogether, so the
        // real scheduler can never fire during this test; only the explicit calls below invoke
        // the method.
        "flatio.sync.kufar-apartment-rent.delta.cron=-",
        "flatio.sync.kufar-apartment-rent.full.cron=-"
    }
)
class KufarSyncExecutorIsolationTest {

  private static final String SOURCE_CODE = "KUFAR_APARTMENT_RENT";

  // Replaces real beans which depend on JPA repositories not available in this test context
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

  @MockBean
  private FavoriteRepository favoriteRepository;

  @MockBean
  private BlacklistEntryRepository blacklistEntryRepository;

  @MockBean(name = "kufarAdDetailRestClient")
  private RestClient restClient;

  @MockBean
  private KufarApartmentRentConnector connector;

  @MockBean
  private SourceService sourceService;

  @MockBean
  private SyncRunService syncRunService;

  @MockBean
  private ListingIngestionService listingIngestionService;

  // Every other *FullSyncJob bean in this full context has its own onApplicationReady()
  // (ApplicationReadyEvent -> @Async("startupSyncExecutor")) that calls sourceService /
  // syncRunService / listingIngestionService — the SAME shared @MockBean instances this test
  // stubs and asserts against. Technical Reviewer reproduced a genuine intermittent failure
  // (issue #332 PR review) caused by exactly this: those 13 other jobs' startup threads invoking
  // the shared mocks concurrently with this test's own Given-block stubbing and
  // runDeltaSync()/runFullSync() call — Mockito mocks are not thread-safe against concurrent
  // stub-registration-vs-invocation from different threads. Mocking these beans outright (rather
  // than only scoping stubs to SOURCE_CODE, which narrows the effect of the race but does not
  // remove the concurrent access itself) makes their onApplicationReady() a no-op: Spring still
  // invokes it via the registered @EventListener, but calling a method on a Mockito mock never
  // executes the real body, so it never touches sourceService/syncRunService/
  // listingIngestionService at all.
  @MockBean
  private KufarApartmentSaleFullSyncJob kufarApartmentSaleFullSyncJob;

  @MockBean
  private KufarHouseRentFullSyncJob kufarHouseRentFullSyncJob;

  @MockBean
  private KufarHouseSaleFullSyncJob kufarHouseSaleFullSyncJob;

  @MockBean
  private KufarRoomRentFullSyncJob kufarRoomRentFullSyncJob;

  @MockBean
  private KufarRoomSaleFullSyncJob kufarRoomSaleFullSyncJob;

  @MockBean
  private OnlinerFullSyncJob onlinerFullSyncJob;

  @MockBean
  private OnlinerSaleFullSyncJob onlinerSaleFullSyncJob;

  @MockBean
  private RealtFullSyncJob realtFullSyncJob;

  @MockBean
  private RealtSaleFullSyncJob realtSaleFullSyncJob;

  @MockBean
  private RealtRoomFullSyncJob realtRoomFullSyncJob;

  @MockBean
  private RealtRoomSaleFullSyncJob realtRoomSaleFullSyncJob;

  @MockBean
  private RealtHouseRentFullSyncJob realtHouseRentFullSyncJob;

  @MockBean
  private RealtHouseSaleFullSyncJob realtHouseSaleFullSyncJob;

  @Autowired
  private KufarApartmentRentDeltaSyncJob deltaSyncJob;

  @Autowired
  private KufarApartmentRentFullSyncJob fullSyncJob;

  @Test
  void should_return_control_to_caller_immediately_when_delta_sync_scheduled_method_invoked() throws InterruptedException {
    // Given — connector.fetchDelta blocks until explicitly released, capturing the thread it
    // actually runs on; the real work is deliberately held open so a synchronous (non-@Async)
    // invocation would be caught still waiting on it
    CountDownLatch workStarted = new CountDownLatch(1);
    CountDownLatch releaseWork = new CountDownLatch(1);
    AtomicReference<String> executingThreadName = new AtomicReference<>();
    String callingThreadName = Thread.currentThread().getName();

    when(connector.getSourceId()).thenReturn(SOURCE_CODE);
    // Scoped to SOURCE_CODE (not anyString()) as defense in depth on top of the 13
    // @MockBean(...FullSyncJob) fields above, which already stop those beans' onApplicationReady
    // from calling sourceService/listingIngestionService for any other source at all. Kept narrow
    // regardless, so this stub can never accidentally resolve an unrelated source to "active".
    when(sourceService.findByCodeOrThrow(eq(SOURCE_CODE))).thenReturn(activeSource());
    when(syncRunService.findLastSuccessfulRunAt(eq(SOURCE_CODE)))
        .thenReturn(Optional.of(Instant.now().minusSeconds(900)));
    when(connector.fetchDelta(any())).thenAnswer(invocation -> {
      executingThreadName.set(Thread.currentThread().getName());
      workStarted.countDown();
      releaseWork.await(3, TimeUnit.SECONDS);
      return List.of();
    });

    try {
      // When — invoked through the real Spring-managed bean (@Async AOP proxy), not a plain
      // in-process call
      long before = System.nanoTime();
      deltaSyncJob.runDeltaSync();
      long elapsedMs = (System.nanoTime() - before) / 1_000_000;

      // Then — the call returns almost instantly, well before connector.fetchDelta's 3s block
      // could possibly have elapsed, proving the scheduler-calling thread was not held open
      assertThat(elapsedMs).isLessThan(500);

      // And — the offloaded work genuinely started (proves the job actually ran, not merely
      // returned early due to some short-circuit) and did so on the dedicated pool, not on the
      // thread that called runDeltaSync(). 10s margin (not a tight bound) — under a loaded
      // `clean build` (frontend lint/build/npm tasks running just before :test) core-thread
      // startup on kufarSyncExecutor can take longer than a couple hundred ms; this only bounds
      // "eventually", it does not weaken the actual property under test (the calling thread
      // already returned above, well before this wait even starts).
      assertThat(workStarted.await(10, TimeUnit.SECONDS)).isTrue();
      assertThat(executingThreadName.get()).startsWith("flatio-kufar-sync-");
      assertThat(executingThreadName.get()).isNotEqualTo(callingThreadName);
    } finally {
      releaseWork.countDown();
    }
  }

  @Test
  void should_execute_full_sync_on_kufar_sync_executor_thread_when_run_full_sync_invoked() throws InterruptedException {
    // Given — connector.fetch blocks until explicitly released, capturing the thread it runs on
    CountDownLatch workStarted = new CountDownLatch(1);
    CountDownLatch releaseWork = new CountDownLatch(1);
    AtomicReference<String> executingThreadName = new AtomicReference<>();
    String callingThreadName = Thread.currentThread().getName();

    when(connector.getSourceId()).thenReturn(SOURCE_CODE);
    // Scoped to SOURCE_CODE — see the identical comment in the delta-sync test above.
    when(sourceService.findByCodeOrThrow(eq(SOURCE_CODE))).thenReturn(activeSource());
    when(connector.fetch()).thenAnswer(invocation -> {
      executingThreadName.set(Thread.currentThread().getName());
      workStarted.countDown();
      releaseWork.await(3, TimeUnit.SECONDS);
      return List.of();
    });

    try {
      // When
      long before = System.nanoTime();
      fullSyncJob.runFullSync();
      long elapsedMs = (System.nanoTime() - before) / 1_000_000;

      // Then — same non-blocking guarantee as the delta job, for the full-sync job. 10s margin on
      // workStarted for the same reason as the delta-sync test above (loaded `clean build`
      // environment, not a change to the property under test).
      assertThat(elapsedMs).isLessThan(500);
      assertThat(workStarted.await(10, TimeUnit.SECONDS)).isTrue();
      assertThat(executingThreadName.get()).startsWith("flatio-kufar-sync-");
      assertThat(executingThreadName.get()).isNotEqualTo(callingThreadName);
    } finally {
      releaseWork.countDown();
    }
  }

  private Source activeSource() {
    Source source = new Source();
    source.setId(1L);
    source.setCode(SOURCE_CODE);
    source.setName("Kufar Apartment Rent");
    source.setUrl("https://www.kufar.by");
    source.setActive(true);
    source.setCountry(new Country());
    return source;
  }
}
