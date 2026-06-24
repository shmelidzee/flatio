package com.flatio.telegram.command;

import com.flatio.service.UserSavedSearchService;
import com.flatio.service.domain.SearchFilter;
import com.flatio.telegram.callback.FilterCallbackHandler;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SearchCommandHandlerTest {

  @Mock
  private UserSavedSearchService userSavedSearchService;

  @Mock
  private FilterCallbackHandler filterCallbackHandler;

  @InjectMocks
  private SearchCommandHandler searchCommandHandler;

  @Test
  void should_start_wizard_when_no_saved_filter() {
    // Given
    when(userSavedSearchService.getByTelegramUserId(1L)).thenReturn(Optional.empty());
    var wizardMessage = SendMessage.builder().chatId("100").text("Шаг 1").build();
    when(filterCallbackHandler.startWizardMessage(1L, "100")).thenReturn(wizardMessage);

    // When
    var result = searchCommandHandler.handle(1L, "100");

    // Then — wizard started directly, no choice offered
    assertThat(result).isSameAs(wizardMessage);
    verify(filterCallbackHandler).startWizardMessage(1L, "100");
  }

  @Test
  void should_show_choice_keyboard_when_saved_filter_exists() {
    // Given
    var savedFilter = new SearchFilter(null, null, null, null, null, "APARTMENT", null, null);
    when(userSavedSearchService.getByTelegramUserId(1L)).thenReturn(Optional.of(savedFilter));

    // When
    var result = searchCommandHandler.handle(1L, "100");

    // Then — choice message with two buttons is returned; wizard is NOT started
    assertThat(result).isNotNull();
    assertThat(result.getChatId()).isEqualTo("100");
    assertThat(result.getText()).isNotBlank();
    assertThat(result.getReplyMarkup()).isInstanceOf(InlineKeyboardMarkup.class);

    var keyboard = (InlineKeyboardMarkup) result.getReplyMarkup();
    assertThat(keyboard.getKeyboard()).hasSize(2);
    var row1 = keyboard.getKeyboard().get(0);
    var row2 = keyboard.getKeyboard().get(1);
    assertThat(row1.get(0).getCallbackData()).isEqualTo(SearchCommandHandler.ACTION_USE_LAST_SEARCH);
    assertThat(row2.get(0).getCallbackData()).isEqualTo(FilterCallbackHandler.ACTION_SEARCH);

    verifyNoInteractions(filterCallbackHandler);
  }
}
