package com.flatio.telegram.handler;

import com.flatio.telegram.config.BotConfig;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.objects.Update;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class FlatioLongPollingBotTest {

  @Test
  void should_return_token_from_config() {
    // Given
    var bot = new FlatioLongPollingBot(new BotConfig("test_token:123", "test_bot", null), mock(FlatioBot.class));

    // When
    var result = bot.getBotToken();

    // Then
    assertThat(result).isEqualTo("test_token:123");
  }

  @Test
  void should_return_self_as_updates_consumer() {
    // Given
    var bot = new FlatioLongPollingBot(new BotConfig("t:1", "bot", null), mock(FlatioBot.class));

    // When
    var consumer = bot.getUpdatesConsumer();

    // Then
    assertThat(consumer).isSameAs(bot);
  }

  @Test
  void should_forward_every_update_to_flatio_bot() {
    // Given
    var flatioBot = mock(FlatioBot.class);
    var bot = new FlatioLongPollingBot(new BotConfig("t:1", "bot", null), flatioBot);
    var update1 = mock(Update.class);
    var update2 = mock(Update.class);

    // When
    bot.consume(List.of(update1, update2));

    // Then
    verify(flatioBot).handleUpdateAsync(update1);
    verify(flatioBot).handleUpdateAsync(update2);
  }
}
