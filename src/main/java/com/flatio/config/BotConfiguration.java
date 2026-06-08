package com.flatio.config;

import com.flatio.bot.FlatioBot;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.telegram.telegrambots.meta.generics.LongPollingBot;

/**
 * Registers Telegram bot configuration properties and wires the bot for long-polling.
 *
 * <p>Provides an explicit {@code List<LongPollingBot>} bean because
 * {@code telegrambots-spring-boot-starter 6.9.0} uses
 * {@code ObjectProvider<List<LongPollingBot>>.getIfAvailable()} which does not
 * auto-aggregate individual {@code LongPollingBot} beans under Spring Boot 3.x.
 * Without this explicit bean the starter silently registers no bots and polling never starts.
 */
@Configuration
@EnableConfigurationProperties(BotConfig.class)
@RequiredArgsConstructor
public class BotConfiguration {

  private final FlatioBot flatioBot;

  /**
   * Exposes all long-polling bots so {@code TelegramBotInitializer} picks them up.
   *
   * @return list containing the single Flatio bot instance
   */
  @Bean
  public List<LongPollingBot> longPollingBots() {
    return List.of(flatioBot);
  }
}
