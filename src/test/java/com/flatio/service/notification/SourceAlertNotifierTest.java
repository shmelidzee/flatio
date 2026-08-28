package com.flatio.service.notification;

import com.flatio.domain.alert.AlertType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SourceAlertNotifierTest {

  @Mock
  private TelegramClient telegramClient;

  @InjectMocks
  private SourceAlertNotifier notifier;

  @BeforeEach
  void setUp() {
    ReflectionTestUtils.setField(notifier, "chatId", "-100123456789");
  }

  @Test
  void should_send_failure_alert_to_configured_chat() throws Exception {
    // When
    notifier.sendFailureAlert("REALT", AlertType.NO_SUCCESSFUL_SYNC, "Последний успешный синк: давно");

    // Then
    var captor = ArgumentCaptor.forClass(SendMessage.class);
    verify(telegramClient).execute(captor.capture());
    assertThat(captor.getValue().getChatId()).isEqualTo("-100123456789");
    assertThat(captor.getValue().getText()).contains("REALT").contains("успешного синка");
  }

  @Test
  void should_send_recovery_notice_to_configured_chat() throws Exception {
    // When
    notifier.sendRecoveryNotice("REALT", AlertType.HIGH_ERROR_RATE);

    // Then
    var captor = ArgumentCaptor.forClass(SendMessage.class);
    verify(telegramClient).execute(captor.capture());
    assertThat(captor.getValue().getText()).contains("REALT").contains("восстановлен");
  }

  @Test
  void should_not_send_when_chat_id_is_not_configured() throws Exception {
    // Given
    ReflectionTestUtils.setField(notifier, "chatId", "");

    // When
    notifier.sendFailureAlert("REALT", AlertType.NO_SUCCESSFUL_SYNC, "detail");

    // Then
    verify(telegramClient, never()).execute(any(SendMessage.class));
  }

  @Test
  void should_not_propagate_exception_when_telegram_delivery_fails() throws Exception {
    // Given
    when(telegramClient.execute(any(SendMessage.class))).thenThrow(new TelegramApiException("network error"));

    // When / Then
    assertThatNoException().isThrownBy(() ->
        notifier.sendFailureAlert("REALT", AlertType.NO_SUCCESSFUL_SYNC, "detail"));
  }
}
