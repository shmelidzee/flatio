package com.flatio.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BotConfigTest {

  @Test
  void should_create_config_when_token_and_username_are_valid() {
    // Given / When
    var config = new BotConfig("valid_token:123", "flatio_bot");

    // Then
    assertThat(config.token()).isEqualTo("valid_token:123");
    assertThat(config.username()).isEqualTo("flatio_bot");
  }

  @Test
  void should_throw_when_token_is_null() {
    assertThatThrownBy(() -> new BotConfig(null, "flatio_bot"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("TELEGRAM_BOT_TOKEN");
  }

  @Test
  void should_throw_when_token_is_blank() {
    assertThatThrownBy(() -> new BotConfig("   ", "flatio_bot"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("TELEGRAM_BOT_TOKEN");
  }

  @Test
  void should_throw_when_username_is_null() {
    assertThatThrownBy(() -> new BotConfig("valid_token:123", null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("TELEGRAM_BOT_USERNAME");
  }

  @Test
  void should_throw_when_username_is_blank() {
    assertThatThrownBy(() -> new BotConfig("valid_token:123", "  "))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("TELEGRAM_BOT_USERNAME");
  }
}
