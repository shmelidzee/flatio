package com.flatio.config;

import java.util.concurrent.ThreadPoolExecutor;
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
 *
 * <p>Uses {@link ThreadPoolExecutor.CallerRunsPolicy} to provide natural back-pressure:
 * when all threads and the queue are full the long-polling thread itself processes the
 * update instead of dropping it or throwing {@link java.util.concurrent.RejectedExecutionException}.
 */
@Configuration
public class TelegramExecutorConfig {

  private static final int DEFAULT_CORE_POOL_SIZE = 10;
  private static final int DEFAULT_MAX_POOL_SIZE = 20;
  private static final int DEFAULT_QUEUE_CAPACITY = 100;
  private static final int AWAIT_TERMINATION_SECONDS = 30;

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
      @Value("${telegram.bot.executor.core-pool-size:" + DEFAULT_CORE_POOL_SIZE + "}") int corePoolSize,
      @Value("${telegram.bot.executor.max-pool-size:" + DEFAULT_MAX_POOL_SIZE + "}") int maxPoolSize,
      @Value("${telegram.bot.executor.queue-capacity:" + DEFAULT_QUEUE_CAPACITY + "}") int queueCapacity) {
    var executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(corePoolSize);
    executor.setMaxPoolSize(maxPoolSize);
    executor.setQueueCapacity(queueCapacity);
    executor.setThreadNamePrefix("tg-update-");
    executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
    executor.setWaitForTasksToCompleteOnShutdown(true);
    executor.setAwaitTerminationSeconds(AWAIT_TERMINATION_SECONDS);
    executor.initialize();
    return executor;
  }
}
