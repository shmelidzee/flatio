package com.flatio.telegram.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.meta.generics.TelegramClient;

/**
 * Registers Telegram bot configuration properties and provides the HTTP client bean
 * used for outbound API calls (sending messages, etc.).
 *
 * <p>Long-polling registration is handled automatically by
 * {@code telegrambots-springboot-longpolling-starter}, which discovers all
 * {@link org.telegram.telegrambots.longpolling.starter.SpringLongPollingBot} beans.
 */
@Configuration
@EnableConfigurationProperties(BotConfig.class)
public class BotConfiguration {

  /**
   * Creates the Telegram HTTP client used by bot handlers to send messages.
   *
   * @param botConfig bot credentials from environment variables
   * @return configured {@link OkHttpTelegramClient}, never null
   */
  @Bean
  public TelegramClient telegramClient(BotConfig botConfig) {
    return new OkHttpTelegramClient(botConfig.token());
  }
}
