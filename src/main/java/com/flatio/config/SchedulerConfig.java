package com.flatio.config;

import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * Enables Spring's scheduled task execution and async support for startup syncs.
 */
@Configuration
@EnableScheduling
@EnableAsync
public class SchedulerConfig implements SchedulingConfigurer {

    /**
     * Grace period given to {@link #startupSyncExecutor} and {@link #kufarSyncExecutor} to finish
     * in-flight tasks on shutdown, before Spring interrupts remaining threads and continues the
     * shutdown regardless. Sized above the {@code connector-kufar-detail} RateLimiter's 15s
     * {@code timeout-duration} (see {@code application.yml}) so a single ad's precise-address
     * fetch that is mid-wait when shutdown starts gets a realistic chance to complete, without
     * holding up application shutdown for the entire remaining backlog of an interrupted job —
     * each listing in {@code ListingIngestionServiceImpl} is ingested in its own
     * {@code @Transactional}, so any task still running once this window elapses is interrupted
     * and its unprocessed listings are simply picked up by the next sync cycle via dedup on
     * {@code sourceId + externalId}.
     */
    private static final int SHUTDOWN_AWAIT_TERMINATION_SECONDS = 20;

    @Value("${flatio.scheduler.pool-size}")
    private int poolSize;

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(poolSize);
        scheduler.setThreadNamePrefix("flatio-scheduler-");
        scheduler.setErrorHandler(t -> LoggerFactory.getLogger(SchedulerConfig.class).error("Scheduled task failed", t));
        scheduler.initialize();
        registrar.setTaskScheduler(scheduler);
    }

    /**
     * Dedicated thread pool for startup full syncs triggered by {@code ApplicationReadyEvent}.
     *
     * <p>Each connector runs its startup sync in its own thread so they proceed in parallel
     * and do not block the main application thread — allowing the healthcheck to pass immediately.
     *
     * <p>On graceful shutdown, waits for in-flight startup syncs to finish (up to
     * {@link #SHUTDOWN_AWAIT_TERMINATION_SECONDS}) instead of interrupting them immediately —
     * see {@link #kufarSyncExecutor} for why this matters and what happens if the wait expires.
     *
     * @return executor used by {@code @Async("startupSyncExecutor")} in FullSyncJob classes
     */
    @Bean(name = "startupSyncExecutor")
    public TaskExecutor startupSyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(3);
        executor.setMaxPoolSize(3);
        executor.setThreadNamePrefix("flatio-startup-sync-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(SHUTDOWN_AWAIT_TERMINATION_SECONDS);
        executor.initialize();
        return executor;
    }

    /**
     * Dedicated thread pool for the recurring {@code @Scheduled} Kufar delta/full sync jobs.
     *
     * <p>All 12 Kufar sync jobs (6 categories x delta/full) call {@code KufarAdDetailClient
     * #fetchPreciseAddress} once per ad, which blocks its calling thread on the shared
     * {@code connector-kufar-detail} RateLimiter for up to its {@code timeout-duration} (15s,
     * see {@code application.yml}) whenever sibling Kufar jobs contend for the same permit
     * (issue #328). Without this executor, that blocking happens directly on the shared
     * {@link ThreadPoolTaskScheduler} thread configured in {@link #configureTasks}
     * (size {@code flatio.scheduler.pool-size}, default 5) — the same pool that runs the
     * unrelated Onliner/Realt sync jobs and the health-freshness watchdog, so Kufar-detail
     * contention could delay those cron ticks (issue #332).
     *
     * <p>Every Kufar {@code @Scheduled} entry method is additionally annotated with
     * {@code @Async("kufarSyncExecutor")}: the cron trigger still fires on the shared scheduler
     * thread, but the {@code @Async} proxy immediately hands the actual (blocking) job body off
     * to this pool and returns, freeing the scheduler thread in effectively zero time. Kufar
     * jobs therefore never occupy a shared scheduler thread for the RateLimiter wait, and
     * Onliner/Realt sync and the health watchdog are unaffected by Kufar-detail contention
     * regardless of how long that wait is.
     *
     * <p>Sized for the worst case of all 6 delta jobs firing on the same cron tick (they share
     * common divisors of 60 — see {@code flatio.sync.kufar-*.delta.cron}) plus one full sync
     * overlapping (full syncs run at distinct hours but delta jobs run every 10-20 minutes
     * around the clock, so an overlap with some full sync is possible) — 6 + 1 = 7 core threads.
     * Per {@link ThreadPoolTaskExecutor}/{@link java.util.concurrent.ThreadPoolExecutor} semantics,
     * threads beyond {@code corePoolSize} are only created once the queue is completely full, so
     * at today's expected parallelism (~6-7 concurrent Kufar jobs, all absorbed by the 7 core
     * threads) the pool is not expected to grow past {@code corePoolSize} in practice —
     * {@code maxPoolSize=12} is a safety ceiling, not a routinely-used capacity, reached only if
     * the 20-slot queue backs up (e.g. an unusually slow Kufar response holding threads longer
     * than usual). {@code queueCapacity} bounds how many pending submissions are held before that
     * ceiling is tested, so a burst is queued rather than rejected outright.
     *
     * <p>On graceful shutdown (e.g. a Railway deploy/restart), waits up to
     * {@link #SHUTDOWN_AWAIT_TERMINATION_SECONDS} for in-flight Kufar jobs to finish their current
     * listing rather than interrupting them mid-request — see the constant's Javadoc for the
     * timeout rationale and what happens to unprocessed listings once it elapses (issue #337).
     *
     * @return executor used by {@code @Async("kufarSyncExecutor")} on every Kufar sync job's
     *     {@code @Scheduled} method
     */
    @Bean(name = "kufarSyncExecutor")
    public TaskExecutor kufarSyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(7);
        executor.setMaxPoolSize(12);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("flatio-kufar-sync-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(SHUTDOWN_AWAIT_TERMINATION_SECONDS);
        executor.initialize();
        return executor;
    }
}
