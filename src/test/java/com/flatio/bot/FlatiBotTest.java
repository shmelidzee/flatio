package com.flatio.bot;

import com.flatio.config.BotConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class FlatiBotTest {

  private FlatioBot flatioBot;

  @BeforeEach
  void setUp() {
    var config = new BotConfig("test_token:123", "test_bot");
    flatioBot = new FlatioBot(config, mock(StartCommandHandler.class));
  }

  @Test
  void should_return_username_from_config() {
    // When
    var result = flatioBot.getBotUsername();

    // Then
    assertThat(result).isEqualTo("test_bot");
  }

  @Test
  void should_return_token_from_config() {
    // When
    var result = flatioBot.getBotToken();

    // Then
    assertThat(result).isEqualTo("test_token:123");
  }
}
