package com.flatio.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Configures the thread pool used for concurrent Telegram update processing.
 *
 * <p>Each incoming update is dispatched to this executor so that slow handlers
 * (e.g. search with multiple {@code SendPhoto} calls) do not block other users.
 * Pool size and queue depth are tunable via {@code telegram.bot.executor.*} in
 * {@code application.yml}.
 */
@Configuration
public class TelegramExecutorConfig {

  /**
   * Creates the executor bean for Telegram update dispatch.
   *
   * @param corePoolSize   number of threads always kept alive
   * @param maxPoolSize    maximum number of threads
   * @param queueCapacity  capacity of the task queue before new threads are spawned
   * @return configured and initialized {@link ThreadPoolTaskExecutor}
   */
  @Bean
  public ThreadPoolTaskExecutor telegramUpdateExecutor(
      @Value("${telegram.bot.executor.core-pool-size:10}") int corePoolSize,
      @Value("${telegram.bot.executor.max-pool-size:20}") int maxPoolSize,
      @Value("${telegram.bot.executor.queue-capacity:100}") int queueCapacity) {
    var executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(corePoolSize);
    executor.setMaxPoolSize(maxPoolSize);
    executor.setQueueCapacity(queueCapacity);
    executor.setThreadNamePrefix("tg-update-");
    executor.initialize();
    return executor;
  }
}
