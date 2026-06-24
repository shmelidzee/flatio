package com.flatio.telegram.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BotConfigTest {

  @Test
  void should_create_config_when_token_and_username_are_valid() {
    // Given / When
    var config = new BotConfig("valid_token:123", "flatio_bot", "https://api.flatio.by", null);

    // Then
    assertThat(config.token()).isEqualTo("valid_token:123");
    assertThat(config.username()).isEqualTo("flatio_bot");
    assertThat(config.webhookUrl()).isEqualTo("https://api.flatio.by");
  }

  @Test
  void should_create_config_when_webhook_url_is_null() {
    // Given / When — local profile never sets TELEGRAM_WEBHOOK_URL
    var config = new BotConfig("valid_token:123", "flatio_bot", null, null);

    // Then
    assertThat(config.webhookUrl()).isNull();
  }

  @Test
  void should_create_config_with_webhook_secret_token() {
    // Given / When
    var config = new BotConfig("valid_token:123", "flatio_bot", "https://api.flatio.by", "my-secret");

    // Then
    assertThat(config.webhookSecretToken()).isEqualTo("my-secret");
  }

  @Test
  void should_throw_when_token_is_null() {
    assertThatThrownBy(() -> new BotConfig(null, "flatio_bot", null, null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("TELEGRAM_BOT_TOKEN");
  }

  @Test
  void should_throw_when_token_is_blank() {
    assertThatThrownBy(() -> new BotConfig("   ", "flatio_bot", null, null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("TELEGRAM_BOT_TOKEN");
  }

  @Test
  void should_throw_when_username_is_null() {
    assertThatThrownBy(() -> new BotConfig("valid_token:123", null, null, null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("TELEGRAM_BOT_USERNAME");
  }

  @Test
  void should_throw_when_username_is_blank() {
    assertThatThrownBy(() -> new BotConfig("valid_token:123", "  ", null, null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("TELEGRAM_BOT_USERNAME");
  }
}
