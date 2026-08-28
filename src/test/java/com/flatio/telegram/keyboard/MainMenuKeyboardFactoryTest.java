package com.flatio.telegram.keyboard;

import com.flatio.telegram.handler.SearchResultSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link MainMenuKeyboardFactory} (issue #456) — the welcome/main menu keyboard
 * and the "back to main menu" keyboard used by the favorites/subscriptions/blacklist sections.
 */
class MainMenuKeyboardFactoryTest {

  private MainMenuKeyboardFactory factory;

  @BeforeEach
  void setUp() {
    factory = new MainMenuKeyboardFactory();
  }

  // -------------------------------------------------------------------------
  // build() — welcome/main menu keyboard
  // -------------------------------------------------------------------------

  @Test
  void should_return_three_rows_when_building_main_menu() {
    // When
    InlineKeyboardMarkup keyboard = factory.build();

    // Then
    assertThat(keyboard.getKeyboard()).hasSize(3);
  }

  @Test
  void should_set_search_and_help_texts_and_callback_data_on_first_row() {
    // When
    InlineKeyboardMarkup keyboard = factory.build();
    var row = keyboard.getKeyboard().get(0);

    // Then
    assertThat(row).hasSize(2);
    assertThat(row.get(0).getText()).isEqualTo("Искать");
    assertThat(row.get(0).getCallbackData()).isEqualTo("action:search");
    assertThat(row.get(1).getText()).isEqualTo("Помощь");
    assertThat(row.get(1).getCallbackData()).isEqualTo("action:help");
  }

  @Test
  void should_set_favorites_and_subscriptions_texts_and_callback_data_on_second_row() {
    // When
    InlineKeyboardMarkup keyboard = factory.build();
    var row = keyboard.getKeyboard().get(1);

    // Then
    assertThat(row).hasSize(2);
    assertThat(row.get(0).getText()).isEqualTo("⭐ Избранное");
    assertThat(row.get(0).getCallbackData()).isEqualTo(MainMenuKeyboardFactory.ACTION_FAVORITES);
    assertThat(row.get(1).getText()).isEqualTo("🔔 Мои подписки");
    assertThat(row.get(1).getCallbackData()).isEqualTo(MainMenuKeyboardFactory.ACTION_SUBSCRIPTIONS);
  }

  @Test
  void should_set_blacklist_text_and_callback_data_on_third_row() {
    // When
    InlineKeyboardMarkup keyboard = factory.build();
    var row = keyboard.getKeyboard().get(2);

    // Then
    assertThat(row).hasSize(1);
    assertThat(row.get(0).getText()).isEqualTo("🚫 Чёрный список");
    assertThat(row.get(0).getCallbackData()).isEqualTo(MainMenuKeyboardFactory.ACTION_BLACKLIST);
  }

  // -------------------------------------------------------------------------
  // buildBackToMenu() — single-button "return to main menu" keyboard
  // -------------------------------------------------------------------------

  @Test
  void should_return_single_button_with_menu_callback_when_building_back_to_menu() {
    // When
    InlineKeyboardMarkup keyboard = factory.buildBackToMenu();

    // Then
    assertThat(keyboard.getKeyboard()).hasSize(1);
    var row = keyboard.getKeyboard().get(0);
    assertThat(row).hasSize(1);
    assertThat(row.get(0).getText()).isEqualTo("🏠 Главное меню");
    assertThat(row.get(0).getCallbackData()).isEqualTo(SearchResultSender.ACTION_MENU);
  }
}
