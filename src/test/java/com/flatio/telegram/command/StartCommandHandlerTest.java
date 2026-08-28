package com.flatio.telegram.command;

import com.flatio.common.exception.ListingNotFoundException;
import com.flatio.service.ListingService;
import com.flatio.service.UserService;
import com.flatio.telegram.formatter.ListingFormatter;
import com.flatio.telegram.keyboard.MainMenuKeyboardFactory;
import com.flatio.web.dto.ListingResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StartCommandHandlerTest {

  @Mock
  private UserService userService;

  @Mock
  private ListingService listingService;

  @Mock
  private ListingFormatter listingFormatter;

  @Mock
  private MainMenuKeyboardFactory mainMenuKeyboardFactory;

  @InjectMocks
  private StartCommandHandler handler;

  @Test
  void should_call_find_or_create_with_telegram_user_data() {
    // Given
    var update = buildUpdate(100L, "alice", "Alice", 42L);
    when(userService.findOrCreate(anyLong(), any(), any()))
        .thenReturn(new com.flatio.domain.user.User());

    // When
    handler.handle(update);

    // Then
    verify(userService).findOrCreate(100L, "alice", "Alice");
  }

  @Test
  void should_return_send_message_with_correct_chat_id() {
    // Given
    var update = buildUpdate(200L, "bob", "Bob", 99L);
    when(userService.findOrCreate(anyLong(), any(), any()))
        .thenReturn(new com.flatio.domain.user.User());

    // When
    SendMessage result = handler.handle(update);

    // Then
    assertThat(result).isNotNull();
    assertThat(result.getChatId()).isEqualTo("99");
  }

  @Test
  void should_include_first_name_in_greeting_when_present() {
    // Given
    var update = buildUpdate(300L, "carol", "Carol", 55L);
    when(userService.findOrCreate(anyLong(), any(), any()))
        .thenReturn(new com.flatio.domain.user.User());

    // When
    SendMessage result = handler.handle(update);

    // Then
    assertThat(result.getText()).contains("Carol");
  }

  @Test
  void should_use_generic_greeting_when_first_name_is_null() {
    // Given
    var update = buildUpdate(400L, "dave", null, 77L);
    when(userService.findOrCreate(anyLong(), any(), any()))
        .thenReturn(new com.flatio.domain.user.User());

    // When
    SendMessage result = handler.handle(update);

    // Then
    assertThat(result.getText()).startsWith("Привет!");
  }

  // -------------------------------------------------------------------------
  // Main menu keyboard delegation (issue #456) — the keyboard's own structure
  // (rows, texts, callback data) is covered by MainMenuKeyboardFactoryTest;
  // here we only verify StartCommandHandler wires the factory's output in.
  // -------------------------------------------------------------------------

  @Test
  void should_use_main_menu_keyboard_when_welcome_message_built() {
    // Given
    var update = buildUpdate(500L, "eve", "Eve", 88L);
    when(userService.findOrCreate(anyLong(), any(), any()))
        .thenReturn(new com.flatio.domain.user.User());
    var expectedKeyboard = mock(InlineKeyboardMarkup.class);
    when(mainMenuKeyboardFactory.build()).thenReturn(expectedKeyboard);

    // When
    SendMessage result = handler.handle(update);

    // Then
    assertThat(result.getReplyMarkup()).isSameAs(expectedKeyboard);
  }

  @Test
  void should_use_main_menu_keyboard_when_building_menu_message() {
    // Given
    var expectedKeyboard = mock(InlineKeyboardMarkup.class);
    when(mainMenuKeyboardFactory.build()).thenReturn(expectedKeyboard);

    // When
    SendMessage result = handler.buildMenuMessage("100");

    // Then
    assertThat(result.getChatId()).isEqualTo("100");
    assertThat(result.getReplyMarkup()).isSameAs(expectedKeyboard);
  }

  // -------------------------------------------------------------------------
  // Deep link — /start listing_<id> (issue #418)
  // -------------------------------------------------------------------------

  @Test
  void should_open_listing_card_when_start_payload_is_listing_deep_link() {
    // Given
    var update = buildUpdateWithText(700L, "grace", "Grace", 22L, "/start listing_42");
    when(userService.findOrCreate(anyLong(), any(), any())).thenReturn(new com.flatio.domain.user.User());
    var listing = buildListingResponse(42L, "https://realt.by/42");
    when(listingService.findById(42L, null)).thenReturn(listing);
    when(listingFormatter.buildDeepLinkCaption(listing)).thenReturn("caption");
    when(listingFormatter.buildKeyboard("https://realt.by/42")).thenReturn(mock(InlineKeyboardMarkup.class));

    // When
    SendMessage result = handler.handle(update);

    // Then — the listing card is sent, not the welcome message
    assertThat(result.getChatId()).isEqualTo("22");
    assertThat(result.getText()).isEqualTo("caption");
    assertThat(result.getReplyMarkup()).isNotNull();
  }

  @Test
  void should_still_register_user_when_deep_link_payload_present() {
    // Given — registration happens regardless of payload (OQ-25)
    var update = buildUpdateWithText(701L, "heidi", "Heidi", 23L, "/start listing_42");
    when(userService.findOrCreate(anyLong(), any(), any())).thenReturn(new com.flatio.domain.user.User());
    var listing = buildListingResponse(42L, "https://realt.by/42");
    when(listingService.findById(42L, null)).thenReturn(listing);
    when(listingFormatter.buildDeepLinkCaption(listing)).thenReturn("caption");
    when(listingFormatter.buildKeyboard(any())).thenReturn(mock(InlineKeyboardMarkup.class));

    // When
    handler.handle(update);

    // Then
    verify(userService).findOrCreate(701L, "heidi", "Heidi");
  }

  @Test
  void should_show_unavailable_message_when_deep_link_listing_not_found() {
    // Given
    var update = buildUpdateWithText(702L, "ivan", "Ivan", 24L, "/start listing_999");
    when(userService.findOrCreate(anyLong(), any(), any())).thenReturn(new com.flatio.domain.user.User());
    when(listingService.findById(999L, null)).thenThrow(new ListingNotFoundException(999L));

    // When
    SendMessage result = handler.handle(update);

    // Then — graceful message, no exception, listingFormatter never consulted
    assertThat(result.getChatId()).isEqualTo("24");
    assertThat(result.getText()).contains("не найдено");
    verify(listingFormatter, never()).buildDeepLinkCaption(any());
  }

  @Test
  void should_show_unavailable_message_when_deep_link_id_is_not_numeric() {
    // Given — malformed payload, not a valid listing ID
    var update = buildUpdateWithText(703L, "judy", "Judy", 25L, "/start listing_abc");
    when(userService.findOrCreate(anyLong(), any(), any())).thenReturn(new com.flatio.domain.user.User());

    // When
    SendMessage result = handler.handle(update);

    // Then — graceful message, no exception, listingService never consulted
    assertThat(result.getChatId()).isEqualTo("25");
    assertThat(result.getText()).contains("не найдено");
    verify(listingService, never()).findById(any(), any());
  }

  @Test
  void should_return_welcome_message_when_start_payload_does_not_match_listing_prefix() {
    // Given — a payload that isn't a listing deep link falls back to the normal welcome flow
    var update = buildUpdateWithText(704L, "kevin", "Kevin", 26L, "/start something-else");
    when(userService.findOrCreate(anyLong(), any(), any())).thenReturn(new com.flatio.domain.user.User());

    // When
    SendMessage result = handler.handle(update);

    // Then
    assertThat(result.getText()).contains("Kevin");
    verify(listingService, never()).findById(any(), any());
  }

  // -------------------------------------------------------------------------
  // helpers
  // -------------------------------------------------------------------------

  private ListingResponse buildListingResponse(Long id, String sourceUrl) {
    return new ListingResponse(
        id,                          // id
        "ext-" + id,                 // externalId
        "realt",                     // sourceId
        "Квартира",                  // title
        null,                        // description
        null,                        // dealType
        null,                        // priceUnit
        "APARTMENT",                 // propertyType
        BigDecimal.valueOf(50_000),  // price
        null,                        // priceLabel
        "USD",                       // currency
        2,                           // rooms
        null,                        // floorNumber
        null,                        // floorsTotal
        null,                        // areaTotalM2
        "ул. Пушкина, 1",            // address
        "Минск",                     // city
        null,                        // district
        null,                        // latitude
        null,                        // longitude
        false,                       // isOwner
        false,                       // isNegotiable
        com.flatio.domain.listing.ListingStatus.ACTIVE, // status
        sourceUrl,                   // sourceUrl
        null,                        // publishedAt
        null,                        // createdAt
        java.util.List.of(),         // priceHistory
        false                        // hasDuplicates
    );
  }

  private Update buildUpdateWithText(long fromId, String username, String firstName, long chatId, String text) {
    var from = mock(org.telegram.telegrambots.meta.api.objects.User.class);
    when(from.getId()).thenReturn(fromId);
    when(from.getUserName()).thenReturn(username);
    when(from.getFirstName()).thenReturn(firstName);

    var message = mock(Message.class);
    when(message.getFrom()).thenReturn(from);
    when(message.getChatId()).thenReturn(chatId);
    when(message.getText()).thenReturn(text);

    var update = mock(Update.class);
    when(update.getMessage()).thenReturn(message);
    return update;
  }

  private Update buildUpdate(long fromId, String username, String firstName, long chatId) {
    var from = mock(org.telegram.telegrambots.meta.api.objects.User.class);
    when(from.getId()).thenReturn(fromId);
    when(from.getUserName()).thenReturn(username);
    when(from.getFirstName()).thenReturn(firstName);

    var message = mock(Message.class);
    when(message.getFrom()).thenReturn(from);
    when(message.getChatId()).thenReturn(chatId);

    var update = mock(Update.class);
    when(update.getMessage()).thenReturn(message);
    return update;
  }
}
