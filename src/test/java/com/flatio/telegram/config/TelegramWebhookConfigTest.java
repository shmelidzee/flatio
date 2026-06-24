package com.flatio.telegram.config;

import com.flatio.telegram.handler.FlatioBot;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.updates.SetWebhook;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TelegramWebhookConfigTest {

  @Test
  void should_throw_when_webhook_url_is_blank() {
    // Given
    var config = new TelegramWebhookConfig(
        new BotConfig("token:1", "bot", "  ", null), mock(TelegramClient.class), mock(FlatioBot.class));

    // When / Then
    assertThatThrownBy(config::flatioWebhookBot)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("TELEGRAM_WEBHOOK_URL");
  }

  @Test
  void should_throw_when_webhook_url_is_null() {
    // Given
    var config = new TelegramWebhookConfig(
        new BotConfig("token:1", "bot", null, null), mock(TelegramClient.class), mock(FlatioBot.class));

    // When / Then
    assertThatThrownBy(config::flatioWebhookBot)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("TELEGRAM_WEBHOOK_URL");
  }

  @Test
  void should_build_bot_with_token_as_bot_path() {
    // Given
    var config = new TelegramWebhookConfig(
        new BotConfig("token:1", "bot", "https://api.flatio.by", null), mock(TelegramClient.class), mock(FlatioBot.class));

    // When
    var webhookBot = config.flatioWebhookBot();

    // Then
    assertThat(webhookBot.getBotPath()).isEqualTo("token:1");
  }

  @Test
  void should_forward_update_to_flatio_bot_when_consuming() {
    // Given
    var flatioBot = mock(FlatioBot.class);
    var config = new TelegramWebhookConfig(
        new BotConfig("token:1", "bot", "https://api.flatio.by", null), mock(TelegramClient.class), flatioBot);
    var webhookBot = config.flatioWebhookBot();
    var update = mock(Update.class);

    // When
    BotApiMethod<?> result = webhookBot.consumeUpdate(update);

    // Then
    verify(flatioBot).handleUpdateAsync(update);
    assertThat(result).isNull();
  }

  @Test
  void should_call_set_webhook_with_url_and_token_path_when_registering() throws TelegramApiException {
    // Given
    var telegramClient = mock(TelegramClient.class);
    var config = new TelegramWebhookConfig(
        new BotConfig("token:1", "bot", "https://api.flatio.by", null), telegramClient, mock(FlatioBot.class));
    var webhookBot = config.flatioWebhookBot();

    // When
    webhookBot.runSetWebhook();

    // Then
    verify(telegramClient).execute(argThat((SetWebhook setWebhook) ->
        "https://api.flatio.by/token:1".equals(setWebhook.getUrl())));
  }

  @Test
  void should_call_set_webhook_with_secret_token_when_configured() throws TelegramApiException {
    // Given
    var telegramClient = mock(TelegramClient.class);
    var config = new TelegramWebhookConfig(
        new BotConfig("token:1", "bot", "https://api.flatio.by", "my-secret-token"),
        telegramClient, mock(FlatioBot.class));
    var webhookBot = config.flatioWebhookBot();

    // When
    webhookBot.runSetWebhook();

    // Then — secret token is forwarded to Telegram so it echoes it back as a header on every update
    verify(telegramClient).execute(argThat((SetWebhook setWebhook) ->
        "my-secret-token".equals(setWebhook.getSecretToken())));
  }

  @Test
  void should_call_set_webhook_without_secret_token_when_not_configured() throws TelegramApiException {
    // Given
    var telegramClient = mock(TelegramClient.class);
    var config = new TelegramWebhookConfig(
        new BotConfig("token:1", "bot", "https://api.flatio.by", null), telegramClient, mock(FlatioBot.class));
    var webhookBot = config.flatioWebhookBot();

    // When
    webhookBot.runSetWebhook();

    // Then — no secret token in SetWebhook (backward-compatible mode)
    verify(telegramClient).execute(argThat((SetWebhook setWebhook) ->
        setWebhook.getSecretToken() == null || setWebhook.getSecretToken().isBlank()));
  }

  @Test
  void should_strip_trailing_slash_when_webhook_url_has_one() throws TelegramApiException {
    // Given — operator misconfigured TELEGRAM_WEBHOOK_URL with a trailing slash
    var telegramClient = mock(TelegramClient.class);
    var config = new TelegramWebhookConfig(
        new BotConfig("token:1", "bot", "https://api.flatio.by/", null), telegramClient, mock(FlatioBot.class));
    var webhookBot = config.flatioWebhookBot();

    // When
    webhookBot.runSetWebhook();

    // Then — no double slash between the base URL and the bot path
    verify(telegramClient).execute(argThat((SetWebhook setWebhook) ->
        "https://api.flatio.by/token:1".equals(setWebhook.getUrl())));
  }

  // -------------------------------------------------------------------------
  // failure handling — neither call should crash the application
  // -------------------------------------------------------------------------

  @Test
  void should_not_throw_when_set_webhook_call_fails() throws TelegramApiException {
    // Given
    var telegramClient = mock(TelegramClient.class);
    when(telegramClient.execute(any(SetWebhook.class)))
        .thenThrow(new TelegramApiException("network error"));
    var config = new TelegramWebhookConfig(
        new BotConfig("token:1", "bot", "https://api.flatio.by", null), telegramClient, mock(FlatioBot.class));
    var webhookBot = config.flatioWebhookBot();

    // When / Then — registration failure must not crash startup
    assertThatNoException().isThrownBy(webhookBot::runSetWebhook);
  }

  @Test
  void should_not_call_telegram_when_delete_webhook_runs() throws TelegramApiException {
    // Given — no deleteWebhook callback is wired (see class Javadoc): a rolling deploy's old
    // instance shutting down must not erase the webhook the new instance just registered
    var telegramClient = mock(TelegramClient.class);
    var config = new TelegramWebhookConfig(
        new BotConfig("token:1", "bot", "https://api.flatio.by", null), telegramClient, mock(FlatioBot.class));
    var webhookBot = config.flatioWebhookBot();

    // When
    webhookBot.runDeleteWebhook();

    // Then — no-op, no API call made, no exception
    verifyNoInteractions(telegramClient);
  }
}
