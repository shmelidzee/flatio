package com.flatio.telegram.callback;

import com.flatio.common.exception.BlacklistEntryNotFoundException;
import com.flatio.common.exception.BlacklistKeywordLimitExceededException;
import com.flatio.common.exception.ListingNotFoundException;
import com.flatio.common.exception.SourceNotFoundException;
import com.flatio.domain.blacklist.BlacklistEntryType;
import com.flatio.domain.user.User;
import com.flatio.service.BlacklistService;
import com.flatio.service.ListingService;
import com.flatio.service.UserService;
import com.flatio.telegram.config.SourceDisplayProperties;
import com.flatio.telegram.handler.SearchResultSender;
import com.flatio.telegram.keyboard.MainMenuKeyboardFactory;
import com.flatio.telegram.state.BlacklistKeywordPromptState;
import com.flatio.web.dto.BlacklistEntryResponse;
import com.flatio.web.dto.CreateBlacklistEntryRequest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link BlacklistCallbackHandler} (issue #459).
 */
@ExtendWith(MockitoExtension.class)
class BlacklistCallbackHandlerTest {

  @Mock
  private UserService userService;

  @Mock
  private BlacklistService blacklistService;

  @Mock
  private ListingService listingService;

  @Mock
  private SourceDisplayProperties sourceDisplayProperties;

  @Mock
  private MainMenuKeyboardFactory keyboardFactory;

  @Mock
  private BlacklistKeywordPromptState keywordPromptState;

  @Mock
  private TelegramClient telegramClient;

  @InjectMocks
  private BlacklistCallbackHandler handler;

  @Test
  void should_send_single_message_with_entry_and_delete_button_when_user_has_entries() throws Exception {
    // Given — issue #477: one message per page instead of one message per entry
    var user = buildUser(7L);
    when(userService.findByTelegramId(1L)).thenReturn(Optional.of(user));
    var entry = buildBlacklistEntry(3L, BlacklistEntryType.KEYWORD, "новостройка");
    when(blacklistService.findByUser(eq(7L), isNull(), any())).thenReturn(new PageImpl<>(List.of(entry)));
    var callback = buildCallback(1L, 100L, true, BlacklistCallbackHandler.ACTION_BLACKLIST);

    // When
    handler.handle(callback);

    // Then
    var captor = ArgumentCaptor.forClass(SendMessage.class);
    verify(telegramClient, times(1)).execute(captor.capture());
    var message = captor.getValue();
    assertThat(message.getText()).contains("Фильтр: Все").contains("Стоп-слово").contains("новостройка");
    // issue #524: the list's delete button now leads to a confirmation prompt, not straight to deletion
    assertThat(extractCallbackData(message)).contains("BL:DELCONF:3");
  }

  @Test
  void should_include_hint_in_list_message_when_blacklist_has_entries() throws Exception {
    // Given — issue #474 (FR-NAV-10): explain where LISTING/SOURCE entries come from, now folded
    // into the single consolidated list message introduced by #477
    var user = buildUser(7L);
    when(userService.findByTelegramId(1L)).thenReturn(Optional.of(user));
    var entry = buildBlacklistEntry(3L, BlacklistEntryType.KEYWORD, "новостройка");
    when(blacklistService.findByUser(eq(7L), isNull(), any())).thenReturn(new PageImpl<>(List.of(entry)));
    var callback = buildCallback(1L, 100L, true, BlacklistCallbackHandler.ACTION_BLACKLIST);

    // When
    handler.handle(callback);

    // Then
    var captor = ArgumentCaptor.forClass(SendMessage.class);
    verify(telegramClient, times(1)).execute(captor.capture());
    assertThat(captor.getValue().getText())
        .contains("Объявления и источники скрываются кнопкой «🚫 Скрыть» с карточки в поиске")
        .contains("Стоп-слова добавляются кнопкой «➕ Добавить стоп-слово» ниже");
  }

  @Test
  void should_explain_delete_button_numbering_in_hint_when_blacklist_has_entries() throws Exception {
    // Given — issue #507: numbered delete buttons (#498) were never explained in the UI
    var user = buildUser(7L);
    when(userService.findByTelegramId(1L)).thenReturn(Optional.of(user));
    var entry = buildBlacklistEntry(3L, BlacklistEntryType.KEYWORD, "новостройка");
    when(blacklistService.findByUser(eq(7L), isNull(), any())).thenReturn(new PageImpl<>(List.of(entry)));
    var callback = buildCallback(1L, 100L, true, BlacklistCallbackHandler.ACTION_BLACKLIST);

    // When
    handler.handle(callback);

    // Then
    var captor = ArgumentCaptor.forClass(SendMessage.class);
    verify(telegramClient, times(1)).execute(captor.capture());
    assertThat(captor.getValue().getText())
        .contains("Номер на кнопке «Удалить» соответствует порядку записи в списке выше");
  }

  @Test
  void should_mark_active_filter_with_checkmark_when_filter_selected() throws Exception {
    // Given — issue #507: "• " prefix was too subtle as an active-filter marker
    var user = buildUser(7L);
    when(userService.findByTelegramId(1L)).thenReturn(Optional.of(user));
    var entry = buildBlacklistEntry(3L, BlacklistEntryType.KEYWORD, "новостройка");
    when(blacklistService.findByUser(eq(7L), eq(BlacklistEntryType.KEYWORD), any()))
        .thenReturn(new PageImpl<>(List.of(entry)));
    var callback = buildCallback(1L, 100L, true, "BL:FILTER:KEYWORD");

    // When
    handler.handleFilter(callback);

    // Then
    var captor = ArgumentCaptor.forClass(SendMessage.class);
    verify(telegramClient, times(1)).execute(captor.capture());
    assertThat(extractButtonLabels(captor.getValue())).contains("✅ Стоп-слова").doesNotContain("• Стоп-слова");
  }

  @Test
  void should_send_empty_state_with_search_shortcut_when_user_has_no_entries() throws Exception {
    // Given — issue #474: empty blacklist must not be a dead end
    var user = buildUser(8L);
    when(userService.findByTelegramId(2L)).thenReturn(Optional.of(user));
    when(blacklistService.findByUser(eq(8L), isNull(), any())).thenReturn(Page.empty());
    var callback = buildCallback(2L, 200L, true, BlacklistCallbackHandler.ACTION_BLACKLIST);

    // When
    handler.handle(callback);

    // Then — a single empty-state message (issue #477 folded the old separate nav message away)
    var captor = ArgumentCaptor.forClass(SendMessage.class);
    verify(telegramClient, times(1)).execute(captor.capture());
    var emptyMessage = captor.getValue();
    assertThat(emptyMessage.getText()).isEqualTo("🚫 Ваш чёрный список пока пуст."
        + "\n\nСкрывайте объявления и источники прямо с их карточки в поиске, либо добавьте стоп-слово кнопкой ниже.");
    var keyboard = (InlineKeyboardMarkup) emptyMessage.getReplyMarkup();
    assertThat(keyboard.getKeyboard().get(1).get(0).getText()).isEqualTo("🔍 Перейти к поиску");
    assertThat(keyboard.getKeyboard().get(1).get(0).getCallbackData()).isEqualTo(FilterCallbackHandler.ACTION_SEARCH);
    assertThat(keyboard.getKeyboard().get(2).get(0).getCallbackData()).isEqualTo(SearchResultSender.ACTION_MENU);
  }

  @Test
  void should_show_type_specific_message_and_keep_filter_row_when_filtered_result_is_empty() throws Exception {
    // Given — issue #506: filtering to a type with zero entries must not look like the whole
    // blacklist is empty when other types still have entries
    var user = buildUser(7L);
    when(userService.findByTelegramId(1L)).thenReturn(Optional.of(user));
    when(blacklistService.findByUser(eq(7L), eq(BlacklistEntryType.SOURCE), any())).thenReturn(Page.empty());
    var callback = buildCallback(1L, 100L, true, "BL:FILTER:SOURCE");

    // When
    handler.handleFilter(callback);

    // Then
    var captor = ArgumentCaptor.forClass(SendMessage.class);
    verify(telegramClient, times(1)).execute(captor.capture());
    var message = captor.getValue();
    assertThat(message.getText()).isEqualTo("Записей типа «Источники» нет.");
    assertThat(extractButtonLabels(message)).contains("Все", "✅ Источники", "Стоп-слова");
  }

  @Test
  void should_show_generic_empty_message_without_filter_row_when_blacklist_is_fully_empty() throws Exception {
    // Given — issue #506: the fully-empty case (no type filter active) keeps its original shape
    var user = buildUser(8L);
    when(userService.findByTelegramId(2L)).thenReturn(Optional.of(user));
    when(blacklistService.findByUser(eq(8L), isNull(), any())).thenReturn(Page.empty());
    var callback = buildCallback(2L, 200L, true, BlacklistCallbackHandler.ACTION_BLACKLIST);

    // When
    handler.handle(callback);

    // Then
    var captor = ArgumentCaptor.forClass(SendMessage.class);
    verify(telegramClient, times(1)).execute(captor.capture());
    var message = captor.getValue();
    assertThat(message.getText()).isEqualTo("🚫 Ваш чёрный список пока пуст."
        + "\n\nСкрывайте объявления и источники прямо с их карточки в поиске, либо добавьте стоп-слово кнопкой ниже.");
    assertThat(extractButtonLabels(message)).doesNotContain("Все", "Объявления", "Источники", "Стоп-слова");
  }

  @Test
  void should_include_add_keyword_button_when_blacklist_is_empty() throws Exception {
    // Given — issue #492: EMPTY_TEXT promises an "add stop-word" button that the keyboard lacked
    var user = buildUser(8L);
    when(userService.findByTelegramId(2L)).thenReturn(Optional.of(user));
    when(blacklistService.findByUser(eq(8L), isNull(), any())).thenReturn(Page.empty());
    var callback = buildCallback(2L, 200L, true, BlacklistCallbackHandler.ACTION_BLACKLIST);

    // When
    handler.handle(callback);

    // Then
    var captor = ArgumentCaptor.forClass(SendMessage.class);
    verify(telegramClient, times(1)).execute(captor.capture());
    var keyboard = (InlineKeyboardMarkup) captor.getValue().getReplyMarkup();
    assertThat(keyboard.getKeyboard().get(0).get(0).getText()).isEqualTo("➕ Добавить стоп-слово");
    assertThat(keyboard.getKeyboard().get(0).get(0).getCallbackData()).isEqualTo(BlacklistCallbackHandler.ADD_KEYWORD);
  }

  @Test
  void should_render_entry_with_blank_value_without_crashing_when_value_is_empty() throws Exception {
    // Given — boundary: an entry with an empty value must not break rendering (FR-NAV-6 error isolation)
    var user = buildUser(7L);
    when(userService.findByTelegramId(1L)).thenReturn(Optional.of(user));
    var entry = buildBlacklistEntry(4L, BlacklistEntryType.SOURCE, "");
    when(blacklistService.findByUser(eq(7L), isNull(), any())).thenReturn(new PageImpl<>(List.of(entry)));
    var callback = buildCallback(1L, 100L, true, BlacklistCallbackHandler.ACTION_BLACKLIST);

    // When
    handler.handle(callback);

    // Then
    var captor = ArgumentCaptor.forClass(SendMessage.class);
    verify(telegramClient, times(1)).execute(captor.capture());
    assertThat(captor.getValue().getText()).contains("Источник: ");
  }

  @Test
  void should_show_delete_row_per_entry_when_multiple_entries_present() throws Exception {
    // Given
    var user = buildUser(7L);
    when(userService.findByTelegramId(1L)).thenReturn(Optional.of(user));
    var first = buildBlacklistEntry(1L, BlacklistEntryType.KEYWORD, "аренда");
    var second = buildBlacklistEntry(2L, BlacklistEntryType.SOURCE, "realt");
    when(blacklistService.findByUser(eq(7L), isNull(), any())).thenReturn(new PageImpl<>(List.of(first, second)));
    var callback = buildCallback(1L, 100L, true, BlacklistCallbackHandler.ACTION_BLACKLIST);

    // When
    handler.handle(callback);

    // Then
    var captor = ArgumentCaptor.forClass(SendMessage.class);
    verify(telegramClient, times(1)).execute(captor.capture());
    assertThat(extractCallbackData(captor.getValue())).contains("BL:DELCONF:1", "BL:DELCONF:2");
  }

  @Test
  void should_number_delete_buttons_matching_line_order_when_multiple_entries_present() throws Exception {
    // Given — issue #498: identical "🗑 Удалить" buttons made it impossible to tell which button
    // deletes which line when the page has more than one entry
    var user = buildUser(7L);
    when(userService.findByTelegramId(1L)).thenReturn(Optional.of(user));
    var first = buildBlacklistEntry(1L, BlacklistEntryType.KEYWORD, "аренда");
    var second = buildBlacklistEntry(2L, BlacklistEntryType.SOURCE, "realt");
    when(blacklistService.findByUser(eq(7L), isNull(), any())).thenReturn(new PageImpl<>(List.of(first, second)));
    var callback = buildCallback(1L, 100L, true, BlacklistCallbackHandler.ACTION_BLACKLIST);

    // When
    handler.handle(callback);

    // Then — button (1) is the first entry's delete button, (2) is the second's, same order as the text lines
    var captor = ArgumentCaptor.forClass(SendMessage.class);
    verify(telegramClient, times(1)).execute(captor.capture());
    var keyboard = captor.getValue().getReplyMarkup();
    var rows = ((org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup) keyboard).getKeyboard();
    assertThat(rows.get(0).get(0).getText()).isEqualTo("🗑 Удалить (1)");
    assertThat(rows.get(0).get(0).getCallbackData()).isEqualTo("BL:DELCONF:1");
    assertThat(rows.get(1).get(0).getText()).isEqualTo("🗑 Удалить (2)");
    assertThat(rows.get(1).get(0).getCallbackData()).isEqualTo("BL:DELCONF:2");
  }

  @Test
  void should_number_list_lines_matching_delete_buttons_when_multiple_entries_present() throws Exception {
    // Given — issue #513: only the delete button was numbered, the list text itself was not
    var user = buildUser(7L);
    when(userService.findByTelegramId(1L)).thenReturn(Optional.of(user));
    var first = buildBlacklistEntry(1L, BlacklistEntryType.KEYWORD, "аренда");
    var second = buildBlacklistEntry(2L, BlacklistEntryType.SOURCE, "realt");
    when(blacklistService.findByUser(eq(7L), isNull(), any())).thenReturn(new PageImpl<>(List.of(first, second)));
    var callback = buildCallback(1L, 100L, true, BlacklistCallbackHandler.ACTION_BLACKLIST);

    // When
    handler.handle(callback);

    // Then — line numbers match the delete buttons' numbers, same order
    var captor = ArgumentCaptor.forClass(SendMessage.class);
    verify(telegramClient, times(1)).execute(captor.capture());
    assertThat(captor.getValue().getText())
        .contains("1. Стоп-слово: аренда")
        .contains("2. Источник: realt");
  }

  @Test
  void should_not_show_pagination_row_when_only_one_page_exists() throws Exception {
    // Given
    var user = buildUser(7L);
    when(userService.findByTelegramId(1L)).thenReturn(Optional.of(user));
    var entry = buildBlacklistEntry(3L, BlacklistEntryType.KEYWORD, "новостройка");
    when(blacklistService.findByUser(eq(7L), isNull(), any())).thenReturn(new PageImpl<>(List.of(entry)));
    var callback = buildCallback(1L, 100L, true, BlacklistCallbackHandler.ACTION_BLACKLIST);

    // When
    handler.handle(callback);

    // Then
    var captor = ArgumentCaptor.forClass(SendMessage.class);
    verify(telegramClient, times(1)).execute(captor.capture());
    assertThat(extractButtonLabels(captor.getValue())).doesNotContain("Ещё →", "← Предыдущие");
  }

  @Test
  void should_send_session_expired_message_when_page_callback_has_no_active_session() throws Exception {
    // Given — BL:PAGE:* received without a preceding handle()/handleFilter() call
    var callback = buildCallback(1L, 100L, true, "BL:PAGE:NEXT");

    // When
    handler.handlePage(callback);

    // Then
    var captor = ArgumentCaptor.forClass(SendMessage.class);
    verify(telegramClient).execute(captor.capture());
    assertThat(captor.getValue().getText()).contains("устарел");
    verify(userService, never()).findByTelegramId(any());
  }

  @Test
  void should_render_next_page_with_same_filter_when_bl_page_next_callback_received_after_filter() throws Exception {
    // Given — total=6 with pageSize=5 yields totalPages=2 (PageImpl computes totalPages from
    // total/pageSize, not from the content list size); the active KEYWORD filter must carry over
    var user = buildUser(7L);
    when(userService.findByTelegramId(1L)).thenReturn(Optional.of(user));
    var page1Entry = buildBlacklistEntry(1L, BlacklistEntryType.KEYWORD, "аренда");
    var page2Entry = buildBlacklistEntry(2L, BlacklistEntryType.KEYWORD, "продажа");
    var sort = org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "createdAt");
    when(blacklistService.findByUser(eq(7L), eq(BlacklistEntryType.KEYWORD),
        eq(org.springframework.data.domain.PageRequest.of(0, 5, sort))))
        .thenReturn(new PageImpl<>(List.of(page1Entry), org.springframework.data.domain.PageRequest.of(0, 5), 6));
    when(blacklistService.findByUser(eq(7L), eq(BlacklistEntryType.KEYWORD),
        eq(org.springframework.data.domain.PageRequest.of(1, 5, sort))))
        .thenReturn(new PageImpl<>(List.of(page2Entry), org.springframework.data.domain.PageRequest.of(1, 5), 6));
    var filterCallback = buildCallback(1L, 100L, true, "BL:FILTER:KEYWORD");
    handler.handleFilter(filterCallback);
    var pageCallback = buildCallback(1L, 100L, true, "BL:PAGE:NEXT");

    // When
    handler.handlePage(pageCallback);

    // Then — the second render carries the KEYWORD filter and shows the second page's entry
    var captor = ArgumentCaptor.forClass(SendMessage.class);
    verify(telegramClient, times(2)).execute(captor.capture());
    assertThat(captor.getAllValues().get(1).getText()).contains("продажа").contains("Фильтр: Стоп-слово");
  }

  @Test
  void should_send_empty_message_when_user_is_not_registered() throws Exception {
    // Given
    when(userService.findByTelegramId(3L)).thenReturn(Optional.empty());
    lenient().when(keyboardFactory.buildBackToMenu()).thenReturn(mock(InlineKeyboardMarkup.class));
    var callback = buildCallback(3L, 300L, true, BlacklistCallbackHandler.ACTION_BLACKLIST);

    // When
    handler.handle(callback);

    // Then
    verify(blacklistService, never()).findByUser(any(), any(), any());
  }

  @Test
  void should_send_private_chat_required_message_when_chat_is_not_private() throws Exception {
    // Given — issue #463
    lenient().when(keyboardFactory.buildBackToMenu()).thenReturn(mock(InlineKeyboardMarkup.class));
    var callback = buildCallback(1L, 100L, false, BlacklistCallbackHandler.ACTION_BLACKLIST);

    // When
    handler.handle(callback);

    // Then
    var captor = ArgumentCaptor.forClass(SendMessage.class);
    verify(telegramClient).execute(captor.capture());
    assertThat(captor.getValue().getText()).contains("личные данные");
    verify(userService, never()).findByTelegramId(any());
  }

  @Test
  void should_render_filtered_list_when_filter_callback_received() {
    // Given
    var user = buildUser(7L);
    when(userService.findByTelegramId(1L)).thenReturn(Optional.of(user));
    when(blacklistService.findByUser(eq(7L), eq(BlacklistEntryType.KEYWORD), any())).thenReturn(Page.empty());
    var callback = buildCallback(1L, 100L, true, "BL:FILTER:KEYWORD");

    // When
    handler.handleFilter(callback);

    // Then
    verify(blacklistService).findByUser(eq(7L), eq(BlacklistEntryType.KEYWORD), any());
  }

  @Test
  void should_prompt_for_keyword_when_add_keyword_callback_received() throws Exception {
    // Given
    var callback = buildCallback(1L, 100L, true, BlacklistCallbackHandler.ADD_KEYWORD);

    // When
    handler.handleAddKeywordPrompt(callback);

    // Then
    verify(keywordPromptState).await(1L);
    var captor = ArgumentCaptor.forClass(SendMessage.class);
    verify(telegramClient).execute(captor.capture());
    assertThat(captor.getValue().getText()).isEqualTo("Введите стоп-слово:");
  }

  @Test
  void should_include_cancel_button_when_prompting_for_keyword() throws Exception {
    // Given — issue #508: the prompt had no way out short of typing something
    var callback = buildCallback(1L, 100L, true, BlacklistCallbackHandler.ADD_KEYWORD);

    // When
    handler.handleAddKeywordPrompt(callback);

    // Then
    var captor = ArgumentCaptor.forClass(SendMessage.class);
    verify(telegramClient).execute(captor.capture());
    var message = captor.getValue();
    assertThat(extractButtonLabels(message)).contains("Отмена");
    assertThat(extractCallbackData(message)).contains(BlacklistCallbackHandler.CANCEL_KEYWORD);
  }

  @Test
  void should_clear_prompt_state_and_render_list_when_cancel_keyword_received() throws Exception {
    // Given — issue #508
    var user = buildUser(7L);
    when(userService.findByTelegramId(1L)).thenReturn(Optional.of(user));
    when(blacklistService.findByUser(eq(7L), isNull(), any())).thenReturn(Page.empty());
    var callback = buildCallback(1L, 100L, true, BlacklistCallbackHandler.CANCEL_KEYWORD);

    // When
    handler.handleCancelKeyword(callback);

    // Then
    verify(keywordPromptState).clear(1L);
    var captor = ArgumentCaptor.forClass(SendMessage.class);
    verify(telegramClient, times(1)).execute(captor.capture());
    assertThat(captor.getValue().getText()).contains("Ваш чёрный список пока пуст.");
  }

  @Test
  void should_send_private_chat_required_message_when_add_keyword_from_non_private_chat() throws Exception {
    // Given
    var callback = buildCallback(1L, 100L, false, BlacklistCallbackHandler.ADD_KEYWORD);

    // When
    handler.handleAddKeywordPrompt(callback);

    // Then
    verify(keywordPromptState, never()).await(any());
    var captor = ArgumentCaptor.forClass(SendMessage.class);
    verify(telegramClient).execute(captor.capture());
    assertThat(captor.getValue().getText()).contains("личные данные");
  }

  @Test
  void should_add_keyword_when_valid_text_provided() {
    // Given
    when(userService.findByTelegramId(1L)).thenReturn(Optional.of(buildUser(7L)));

    // When
    handler.handleKeywordText(1L, "100", "новостройка");

    // Then
    verify(blacklistService).create(7L, new CreateBlacklistEntryRequest(BlacklistEntryType.KEYWORD, "новостройка"));
    verify(keywordPromptState).clear(1L);
  }

  @Test
  void should_reprompt_when_keyword_is_blank() throws Exception {
    // When
    handler.handleKeywordText(1L, "100", "   ");

    // Then
    verify(blacklistService, never()).create(any(), any());
    verify(keywordPromptState, never()).clear(any());
  }

  @Test
  void should_reprompt_when_keyword_exceeds_max_length() throws Exception {
    // Given — 101 characters, one over the FR-BL-3 limit
    String tooLong = "a".repeat(101);

    // When
    handler.handleKeywordText(1L, "100", tooLong);

    // Then
    verify(blacklistService, never()).create(any(), any());
  }

  @Test
  void should_include_cancel_button_when_reprompting_for_invalid_keyword() throws Exception {
    // Given — issue #508: the re-prompt after invalid input must also offer a way out
    // When
    handler.handleKeywordText(1L, "100", "   ");

    // Then
    var captor = ArgumentCaptor.forClass(SendMessage.class);
    verify(telegramClient).execute(captor.capture());
    var message = captor.getValue();
    assertThat(extractButtonLabels(message)).contains("Отмена");
    assertThat(extractCallbackData(message)).contains(BlacklistCallbackHandler.CANCEL_KEYWORD);
  }

  @Test
  void should_send_limit_exceeded_message_when_keyword_limit_exceeded() {
    // Given
    when(userService.findByTelegramId(1L)).thenReturn(Optional.of(buildUser(7L)));
    doThrow(new BlacklistKeywordLimitExceededException(20)).when(blacklistService).create(eq(7L), any());

    // When / Then — no exception propagates
    handler.handleKeywordText(1L, "100", "новостройка");
    verify(keywordPromptState).clear(1L);
  }

  @Test
  void should_return_deleted_toast_when_delete_succeeds() {
    // Given
    when(userService.findByTelegramId(1L)).thenReturn(Optional.of(buildUser(7L)));
    var callback = buildCallback(1L, 100L, true, "BL:DELETE:5");

    // When
    var toast = handler.handleDelete(callback);

    // Then
    assertThat(toast).isEqualTo("🗑 Запись удалена из чёрного списка");
    verify(blacklistService).delete(7L, 5L);
  }

  // -------------------------------------------------------------------------
  // Deletion confirmation (issue #524)
  // -------------------------------------------------------------------------

  @Test
  void should_send_confirmation_prompt_with_keyword_value_when_delete_confirm_received() throws Exception {
    // Given
    when(userService.findByTelegramId(1L)).thenReturn(Optional.of(buildUser(7L)));
    var entry = buildBlacklistEntry(5L, BlacklistEntryType.KEYWORD, "новостройка");
    when(blacklistService.findByIdForUser(7L, 5L)).thenReturn(entry);
    var callback = buildCallback(1L, 100L, true, "BL:DELCONF:5");

    // When
    handler.handleDeleteConfirmPrompt(callback);

    // Then — deletion has NOT happened yet, only the prompt was sent
    verify(blacklistService, never()).delete(any(), any());
    var captor = ArgumentCaptor.forClass(SendMessage.class);
    verify(telegramClient).execute(captor.capture());
    var sent = captor.getValue();
    assertThat(sent.getText()).contains("новостройка");
    var buttons = ((InlineKeyboardMarkup) sent.getReplyMarkup()).getKeyboard().get(0);
    assertThat(buttons.get(0).getCallbackData()).isEqualTo("BL:DELETE:5");
    assertThat(buttons.get(1).getCallbackData()).isEqualTo("BL:DELCANCEL");
  }

  @Test
  void should_send_confirmation_prompt_with_configured_source_name_when_type_is_source() throws Exception {
    // Given — same display-name resolution as the list itself (issue #525)
    when(userService.findByTelegramId(1L)).thenReturn(Optional.of(buildUser(7L)));
    var entry = buildBlacklistEntry(6L, BlacklistEntryType.SOURCE, "KUFAR_APARTMENT_RENT");
    when(blacklistService.findByIdForUser(7L, 6L)).thenReturn(entry);
    var sourceEntry = new SourceDisplayProperties.Entry();
    sourceEntry.setPrefix("KUFAR");
    sourceEntry.setDisplayName("Kufar");
    when(sourceDisplayProperties.findBySourceId("KUFAR_APARTMENT_RENT")).thenReturn(Optional.of(sourceEntry));
    var callback = buildCallback(1L, 100L, true, "BL:DELCONF:6");

    // When
    handler.handleDeleteConfirmPrompt(callback);

    // Then
    var captor = ArgumentCaptor.forClass(SendMessage.class);
    verify(telegramClient).execute(captor.capture());
    assertThat(captor.getValue().getText()).contains("Kufar").doesNotContain("KUFAR_APARTMENT_RENT");
  }

  @Test
  void should_send_not_found_message_when_confirming_delete_for_missing_entry() throws Exception {
    // Given
    when(userService.findByTelegramId(1L)).thenReturn(Optional.of(buildUser(7L)));
    when(blacklistService.findByIdForUser(7L, 5L)).thenThrow(new BlacklistEntryNotFoundException(5L));
    var callback = buildCallback(1L, 100L, true, "BL:DELCONF:5");

    // When
    handler.handleDeleteConfirmPrompt(callback);

    // Then
    var captor = ArgumentCaptor.forClass(SendMessage.class);
    verify(telegramClient).execute(captor.capture());
    assertThat(captor.getValue().getText()).isEqualTo("Запись не найдена.");
  }

  @Test
  void should_rerender_list_without_deleting_when_delete_cancel_received() throws Exception {
    // Given
    when(userService.findByTelegramId(1L)).thenReturn(Optional.of(buildUser(7L)));
    when(blacklistService.findByUser(eq(7L), isNull(), any()))
        .thenReturn(new PageImpl<>(List.of(buildBlacklistEntry(1L, BlacklistEntryType.KEYWORD, "аренда"))));
    var callback = buildCallback(1L, 100L, true, "BL:DELCANCEL");

    // When
    handler.handleDeleteCancel(callback);

    // Then — same as re-opening the list; nothing is deleted
    verify(blacklistService, never()).delete(any(), any());
    verify(telegramClient).execute(any(SendMessage.class));
  }

  // -------------------------------------------------------------------------
  // Human-readable display values (issue #525)
  // -------------------------------------------------------------------------

  @Test
  void should_show_listing_title_when_type_is_listing_and_title_available() throws Exception {
    // Given
    when(userService.findByTelegramId(1L)).thenReturn(Optional.of(buildUser(7L)));
    var entry = buildBlacklistEntry(1L, BlacklistEntryType.LISTING, "1105");
    when(blacklistService.findByUser(eq(7L), isNull(), any())).thenReturn(new PageImpl<>(List.of(entry)));
    when(listingService.findDisplayLabelsByIds(List.of(1105L))).thenReturn(Map.of(1105L, "2-комнатная, Немига 5"));
    var callback = buildCallback(1L, 100L, true, BlacklistCallbackHandler.ACTION_BLACKLIST);

    // When
    handler.handle(callback);

    // Then
    var captor = ArgumentCaptor.forClass(SendMessage.class);
    verify(telegramClient).execute(captor.capture());
    assertThat(captor.getValue().getText()).contains("2-комнатная, Немига 5").doesNotContain("1105");
  }

  @Test
  void should_fallback_to_listing_id_label_when_listing_has_no_resolved_label() throws Exception {
    // Given — listing not found, or found but with neither title nor address
    when(userService.findByTelegramId(1L)).thenReturn(Optional.of(buildUser(7L)));
    var entry = buildBlacklistEntry(1L, BlacklistEntryType.LISTING, "1105");
    when(blacklistService.findByUser(eq(7L), isNull(), any())).thenReturn(new PageImpl<>(List.of(entry)));
    when(listingService.findDisplayLabelsByIds(List.of(1105L))).thenReturn(Map.of());
    var callback = buildCallback(1L, 100L, true, BlacklistCallbackHandler.ACTION_BLACKLIST);

    // When
    handler.handle(callback);

    // Then
    var captor = ArgumentCaptor.forClass(SendMessage.class);
    verify(telegramClient).execute(captor.capture());
    assertThat(captor.getValue().getText()).contains("Объявление #1105");
  }

  @Test
  void should_fallback_to_raw_source_code_when_source_not_configured() throws Exception {
    // Given — no SourceDisplayProperties entry configured for this prefix
    when(userService.findByTelegramId(1L)).thenReturn(Optional.of(buildUser(7L)));
    var entry = buildBlacklistEntry(1L, BlacklistEntryType.SOURCE, "unknown_source");
    when(blacklistService.findByUser(eq(7L), isNull(), any())).thenReturn(new PageImpl<>(List.of(entry)));
    var callback = buildCallback(1L, 100L, true, BlacklistCallbackHandler.ACTION_BLACKLIST);

    // When
    handler.handle(callback);

    // Then — sourceDisplayProperties mock returns empty by default; falls back to the raw code
    var captor = ArgumentCaptor.forClass(SendMessage.class);
    verify(telegramClient).execute(captor.capture());
    assertThat(captor.getValue().getText()).contains("unknown_source");
  }

  @Test
  void should_return_hidden_listing_toast_when_hide_listing_succeeds() {
    // Given
    when(userService.findByTelegramId(1L)).thenReturn(Optional.of(buildUser(7L)));
    var callback = buildCallback(1L, 100L, true, "BL:HIDE_LISTING:42");

    // When
    var toast = handler.handleHideListing(callback);

    // Then
    assertThat(toast).isEqualTo("🚫 Объявление скрыто");
    verify(blacklistService).create(7L, new CreateBlacklistEntryRequest(BlacklistEntryType.LISTING, "42"));
  }

  @Test
  void should_return_not_found_toast_when_hide_listing_target_missing() {
    // Given
    when(userService.findByTelegramId(1L)).thenReturn(Optional.of(buildUser(7L)));
    doThrow(new ListingNotFoundException(42L)).when(blacklistService).create(eq(7L), any());
    var callback = buildCallback(1L, 100L, true, "BL:HIDE_LISTING:42");

    // When
    var toast = handler.handleHideListing(callback);

    // Then
    assertThat(toast).isEqualTo("Запись не найдена.");
  }

  @Test
  void should_return_hidden_source_toast_when_hide_source_succeeds() {
    // Given
    when(userService.findByTelegramId(1L)).thenReturn(Optional.of(buildUser(7L)));
    var callback = buildCallback(1L, 100L, true, "BL:HIDE_SOURCE:realt");

    // When
    var toast = handler.handleHideSource(callback);

    // Then
    assertThat(toast).isEqualTo("🚫 Источник скрыт");
    verify(blacklistService).create(7L, new CreateBlacklistEntryRequest(BlacklistEntryType.SOURCE, "realt"));
  }

  @Test
  void should_return_not_found_toast_when_hide_source_target_missing() {
    // Given
    when(userService.findByTelegramId(1L)).thenReturn(Optional.of(buildUser(7L)));
    doThrow(new SourceNotFoundException("realt")).when(blacklistService).create(eq(7L), any());
    var callback = buildCallback(1L, 100L, true, "BL:HIDE_SOURCE:realt");

    // When
    var toast = handler.handleHideSource(callback);

    // Then
    assertThat(toast).isEqualTo("Запись не найдена.");
  }

  @Test
  void should_return_private_chat_required_toast_when_delete_from_non_private_chat() {
    // Given
    var callback = buildCallback(1L, 100L, false, "BL:DELETE:5");

    // When
    var toast = handler.handleDelete(callback);

    // Then
    assertThat(toast).contains("личные данные");
    verify(userService, never()).findByTelegramId(any());
  }

  @Test
  void should_render_blacklist_when_command_invoked_with_telegram_id_and_chat_id() throws Exception {
    // Given — issue #473: /blacklist text command reuses the same rendering as the callback
    var user = buildUser(7L);
    when(userService.findByTelegramId(1L)).thenReturn(Optional.of(user));
    when(blacklistService.findByUser(eq(7L), isNull(), any())).thenReturn(Page.empty());

    // When
    handler.handleCommand(1L, "100", true);

    // Then — single empty-state message, same shape as handle(CallbackQuery)
    var captor = ArgumentCaptor.forClass(SendMessage.class);
    verify(telegramClient, times(1)).execute(captor.capture());
    assertThat(captor.getValue().getText()).contains("Ваш чёрный список пока пуст.");
  }

  @Test
  void should_send_private_chat_required_message_when_command_invoked_outside_private_chat() throws Exception {
    // Given — issue #473
    lenient().when(keyboardFactory.buildBackToMenu()).thenReturn(mock(InlineKeyboardMarkup.class));

    // When
    handler.handleCommand(1L, "100", false);

    // Then
    var captor = ArgumentCaptor.forClass(SendMessage.class);
    verify(telegramClient).execute(captor.capture());
    assertThat(captor.getValue().getText()).contains("личные данные");
    verify(userService, never()).findByTelegramId(any());
  }

  // -------------------------------------------------------------------------
  // helpers
  // -------------------------------------------------------------------------

  private User buildUser(Long id) {
    var user = new User();
    user.setId(id);
    return user;
  }

  private BlacklistEntryResponse buildBlacklistEntry(Long id, BlacklistEntryType type, String value) {
    return new BlacklistEntryResponse(id, type, value, Instant.now());
  }

  private List<String> extractCallbackData(SendMessage message) {
    return ((InlineKeyboardMarkup) message.getReplyMarkup()).getKeyboard().stream()
        .flatMap(row -> row.stream())
        .map(InlineKeyboardButton::getCallbackData)
        .toList();
  }

  private List<String> extractButtonLabels(SendMessage message) {
    return ((InlineKeyboardMarkup) message.getReplyMarkup()).getKeyboard().stream()
        .flatMap(row -> row.stream())
        .map(InlineKeyboardButton::getText)
        .toList();
  }

  private CallbackQuery buildCallback(Long telegramId, Long chatId, boolean isPrivateChat, String data) {
    var from = mock(org.telegram.telegrambots.meta.api.objects.User.class);
    lenient().when(from.getId()).thenReturn(telegramId);

    var chat = mock(Chat.class);
    lenient().when(chat.isUserChat()).thenReturn(isPrivateChat);

    var message = mock(Message.class);
    lenient().when(message.getChatId()).thenReturn(chatId);
    lenient().when(message.getChat()).thenReturn(chat);

    var callback = mock(CallbackQuery.class);
    lenient().when(callback.getFrom()).thenReturn(from);
    lenient().when(callback.getMessage()).thenReturn(message);
    lenient().when(callback.getData()).thenReturn(data);
    return callback;
  }
}
