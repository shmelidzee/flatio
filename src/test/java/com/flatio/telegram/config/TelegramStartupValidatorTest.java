package com.flatio.telegram.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.telegram.telegrambots.meta.api.methods.updates.GetWebhookInfo;
import org.telegram.telegrambots.meta.api.objects.WebhookInfo;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TelegramStartupValidatorTest {

  @Mock
  private TelegramClient telegramClient;

  @InjectMocks
  private TelegramStartupValidator validator;

  // -------------------------------------------------------------------------
  // happy path — no webhook configured
  // -------------------------------------------------------------------------

  @Test
  void should_not_throw_when_webhook_is_empty() throws TelegramApiException {
    // Given — bot is properly configured, no webhook active
    WebhookInfo info = new WebhookInfo();
    info.setUrl("");
    when(telegramClient.execute(any(GetWebhookInfo.class))).thenReturn(info);

    // When / Then — startup proceeds without exception
    assertThatNoException().isThrownBy(() -> validator.validate());
  }

  @Test
  void should_not_throw_when_webhook_url_is_null() throws TelegramApiException {
    // Given — getUrl() returns null (Telegram API omits the field when no webhook set)
    WebhookInfo info = new WebhookInfo();
    info.setUrl(null);
    when(telegramClient.execute(any(GetWebhookInfo.class))).thenReturn(info);

    // When / Then
    assertThatNoException().isThrownBy(() -> validator.validate());
  }

  // -------------------------------------------------------------------------
  // webhook conflict — should warn but not throw
  // -------------------------------------------------------------------------

  @Test
  void should_not_throw_when_active_webhook_detected() throws TelegramApiException {
    // Given — webhook is configured (conflicts with long-polling)
    WebhookInfo info = new WebhookInfo();
    info.setUrl("https://example.com/webhook");
    when(telegramClient.execute(any(GetWebhookInfo.class))).thenReturn(info);

    // When / Then — WARN is logged but startup is not blocked
    assertThatNoException().isThrownBy(() -> validator.validate());
  }

  // -------------------------------------------------------------------------
  // invalid token — must fail startup
  // -------------------------------------------------------------------------

  @Test
  void should_throw_illegal_state_when_token_is_invalid() throws TelegramApiException {
    // Given — Telegram returns 401 Unauthorized for bad token
    when(telegramClient.execute(any(GetWebhookInfo.class)))
        .thenThrow(new TelegramApiException("Error executing: getWebhookInfo. 401 Unauthorized"));

    // When / Then — application must not start with an invalid token
    assertThatThrownBy(() -> validator.validate())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("invalid or expired");
  }

  // -------------------------------------------------------------------------
  // transient API error — should warn, not block startup
  // -------------------------------------------------------------------------

  @Test
  void should_not_throw_when_telegram_api_returns_transient_error() throws TelegramApiException {
    // Given — non-auth error (e.g. rate limit or network issue)
    when(telegramClient.execute(any(GetWebhookInfo.class)))
        .thenThrow(new TelegramApiException("Error executing: getWebhookInfo. 429 Too Many Requests"));

    // When / Then — transient errors must not block startup
    assertThatNoException().isThrownBy(() -> validator.validate());
  }
}
