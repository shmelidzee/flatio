package com.flatio.telegram.handler;

import com.flatio.telegram.callback.FilterCallbackHandler;
import com.flatio.telegram.command.StartCommandHandler;
import com.flatio.telegram.config.BotConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class FlatiBotTest {

  private FlatioBot flatioBot;

  @BeforeEach
  void setUp() {
    var config = new BotConfig("test_token:123", "test_bot");
    flatioBot = new FlatioBot(
        config,
        mock(TelegramClient.class),
        mock(StartCommandHandler.class),
        mock(FilterCallbackHandler.class)
    );
  }

  @Test
  void should_return_token_from_config() {
    // When
    var result = flatioBot.getBotToken();

    // Then
    assertThat(result).isEqualTo("test_token:123");
  }

  @Test
  void should_return_self_as_updates_consumer() {
    // When
    var consumer = flatioBot.getUpdatesConsumer();

    // Then
    assertThat(consumer).isSameAs(flatioBot);
  }
}
