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
     * @return executor used by {@code @Async("startupSyncExecutor")} in FullSyncJob classes
     */
    @Bean(name = "startupSyncExecutor")
    public TaskExecutor startupSyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(3);
        executor.setMaxPoolSize(3);
        executor.setThreadNamePrefix("flatio-startup-sync-");
        executor.initialize();
        return executor;
    }
}
