package com.flatio.config;

import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * Enables Spring's scheduled task execution.
 */
@Configuration
@EnableScheduling
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
}
