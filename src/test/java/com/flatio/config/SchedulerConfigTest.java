package com.flatio.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the {@code kufarSyncExecutor} bean is sized exactly as documented in its Javadoc
 * (issue #332): {@code core=7} (6 concurrent delta jobs + 1 overlapping full sync),
 * {@code max=12} and {@code queue=20} as headroom above the 12 Kufar sync jobs that exist today.
 * A silent regression here (e.g. someone shrinking the pool while adding a 13th Kufar job) would
 * reintroduce the exact starvation issue #332 fixes without any other test catching it, since
 * {@link KufarSyncExecutorIsolationTest} only exercises actual delegation to whatever pool is
 * wired, not its capacity.
 *
 * <p>Instantiates {@link SchedulerConfig} directly and calls the {@code @Bean} method as a plain
 * object, without booting a Spring context — same lightweight recipe used by
 * {@code KufarClientConfigTest} for another {@code @Configuration} class in this codebase.
 */
class SchedulerConfigTest {

  private final SchedulerConfig config = new SchedulerConfig();

  private ThreadPoolTaskExecutor kufarSyncExecutor;

  @AfterEach
  void tearDown() {
    if (kufarSyncExecutor != null) {
      kufarSyncExecutor.shutdown();
    }
  }

  @Test
  void should_configure_dedicated_pool_sizing_when_kufar_sync_executor_bean_created() {
    // Given / When
    TaskExecutor executor = config.kufarSyncExecutor();
    kufarSyncExecutor = (ThreadPoolTaskExecutor) executor;

    // Then — isolated from the shared ThreadPoolTaskScheduler pool used by Onliner/Realt sync
    // and the health-freshness watchdog, sized to absorb all 12 Kufar sync jobs without rejection
    assertThat(kufarSyncExecutor.getCorePoolSize()).isEqualTo(7);
    assertThat(kufarSyncExecutor.getMaxPoolSize()).isEqualTo(12);
    assertThat(kufarSyncExecutor.getQueueCapacity()).isEqualTo(20);
    assertThat(kufarSyncExecutor.getThreadNamePrefix()).isEqualTo("flatio-kufar-sync-");
  }
}
