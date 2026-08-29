package com.flatio.telegram.callback;

import com.flatio.common.exception.SubscriptionLimitExceededException;
import com.flatio.common.exception.SubscriptionNotFoundException;
import com.flatio.common.util.TelegramHtmlEscaper;
import com.flatio.common.util.TelegramPrivateChatGuard;
import com.flatio.domain.subscription.DeliveryMode;
import com.flatio.domain.subscription.SubscriptionChannelType;
import com.flatio.domain.subscription.TriggerType;
import com.flatio.service.SubscriptionService;
import com.flatio.service.UserService;
import com.flatio.telegram.handler.SearchResultSender;
import com.flatio.telegram.keyboard.MainMenuKeyboardFactory;
import com.flatio.telegram.state.SearchFilterState;
import com.flatio.telegram.state.SearchFilterWizard;
import com.flatio.telegram.state.SubscriptionCreationState;
import com.flatio.web.dto.CreateSubscriptionRequest;
import com.flatio.web.dto.SubscriptionResponse;
import com.flatio.web.dto.SubscriptionSearchCriteria;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

/**
 * Handles the "🔔 Мои подписки" section: creating a subscription from the current search filter,
 * viewing the subscriptions list, and pausing/resuming/deleting subscriptions (issue #458).
 *
 * <p>Reuses {@link SubscriptionService}, the same service backing the REST
 * {@code /api/v1/subscriptions} endpoint (Telegram and REST API share services, per project
 * architecture rules). Unlike the read-only summary shipped in #456, this renders one Telegram
 * message per subscription (with its own pause/resume and delete buttons) followed by a single
 * navigation message, matching the shape already used for search results by
 * {@link SearchResultSender}. Explicit pagination is not required by this issue's acceptance
 * criteria, so the list is rendered as a single page.
 *
 * <p>Restricted to private chats (issue #463): subscriptions are personal data, so a request made
 * from a group/supergroup/channel is answered with a redirect to a private chat instead of the
 * actual list — see {@link TelegramPrivateChatGuard}.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class SubscriptionsCallbackHandler {

  /** Callback data value that opens the subscriptions section. */
  public static final String ACTION_SUBSCRIPTIONS = MainMenuKeyboardFactory.ACTION_SUBSCRIPTIONS;
  /** Callback prefix for every callback this handler owns. */
  public static final String CALLBACK_PREFIX = "SUB:";
  /** Callback data for starting a "subscribe to this search" flow from the search navigation message. */
  public static final String CREATE_FROM_FILTER = "SUB:CREATE_FROM_FILTER";
  /** Callback data for starting the search wizard from the empty subscriptions state (issue #475). */
  public static final String START_SEARCH = "SUB:START_SEARCH";
  /** Callback prefix for pausing a subscription. */
  public static final String PAUSE_PREFIX = "SUB:PAUSE:";
  /** Callback prefix for resuming a subscription. */
  public static final String RESUME_PREFIX = "SUB:RESUME:";
  /** Callback prefix for deleting a subscription. */
  public static final String DELETE_PREFIX = "SUB:DELETE:";

  private static final int MAX_LIST_SIZE = 20;
  private static final int MAX_NAME_LENGTH = 255;

  private static final String EMPTY_TEXT = "🔔 У вас пока нет подписок на поиск.";
  private static final String NO_FILTER_TEXT =
      "Сначала выполните поиск с нужными фильтрами, затем подпишитесь на него.";
  private static final String NAME_PROMPT_TEXT = "Введите название подписки:";
  private static final String INVALID_NAME_TEXT =
      "Название не может быть пустым и должно быть не длиннее 255 символов. Введите название подписки ещё раз:";
  private static final String UNREGISTERED_TEXT = "Сначала запустите бота командой /start.";
  private static final String UNREGISTERED_TOAST = UNREGISTERED_TEXT;
  private static final String LIMIT_EXCEEDED_TEXT =
      "Достигнут лимит активных подписок по вашему тарифу. Приостановите или удалите одну из существующих.";
  private static final String PAUSED_TOAST = "⏸ Подписка поставлена на паузу";
  private static final String RESUMED_TOAST = "▶️ Подписка возобновлена";
  private static final String DELETED_TOAST = "🗑 Подписка удалена";
  private static final String NOT_FOUND_TOAST = "Подписка не найдена.";

  private final UserService userService;
  private final SubscriptionService subscriptionService;
  private final MainMenuKeyboardFactory keyboardFactory;
  private final SearchFilterWizard wizard;
  private final SubscriptionCreationState creationState;
  private final FilterCallbackHandler filterCallbackHandler;
  private final TelegramClient telegramClient;

  /**
   * Renders the user's subscriptions list.
   *
   * @param callbackQuery the incoming callback query, never null
   */
  public void handle(CallbackQuery callbackQuery) {
    renderList(callbackQuery.getFrom().getId(), String.valueOf(callbackQuery.getMessage().getChatId()),
        TelegramPrivateChatGuard.isPrivateChat(callbackQuery));
  }

  /**
   * Renders the user's subscriptions list from the {@code /subscriptions} text command
   * (issue #473) — same rendering as {@link #handle(CallbackQuery)}, no new business logic.
   *
   * @param telegramId    Telegram user identifier, never null
   * @param chatId        target chat identifier, never null
   * @param isPrivateChat whether the command was sent in a private one-on-one chat
   */
  public void handleCommand(Long telegramId, String chatId, boolean isPrivateChat) {
    renderList(telegramId, chatId, isPrivateChat);
  }

  private void renderList(Long telegramId, String chatId, boolean isPrivateChat) {
    if (!isPrivateChat) {
      log.debug("SUB callback rejected outside a private chat: chatId={}", chatId);
      sendText(chatId, TelegramPrivateChatGuard.PRIVATE_CHAT_REQUIRED_TEXT);
      return;
    }

    var userOpt = userService.findByTelegramId(telegramId);
    if (userOpt.isEmpty()) {
      log.warn("SUB callback from unregistered telegramId={}", telegramId);
      sendEmptyState(chatId);
      return;
    }

    var pageable = PageRequest.of(0, MAX_LIST_SIZE, Sort.by(Sort.Direction.DESC, "createdAt"));
    var page = subscriptionService.findByUser(userOpt.get().getId(), pageable);
    if (page.isEmpty()) {
      sendEmptyState(chatId);
      return;
    }

    log.debug("Subscriptions list rendered: telegramId={}, count={}", telegramId, page.getNumberOfElements());
    sendItems(chatId, page.getContent());
    sendNavigation(chatId);
  }

  /**
   * Handles the {@code SUB:CREATE_FROM_FILTER} callback from the search navigation message.
   *
   * <p>Reads the user's current {@link SearchFilterState} (still held by {@link SearchFilterWizard}
   * after a search completes — search does not reset the wizard), maps it to a
   * {@link SubscriptionSearchCriteria}, and prompts for a subscription name via free text.
   *
   * @param callbackQuery the incoming callback query, never null
   */
  public void handleCreateFromFilter(CallbackQuery callbackQuery) {
    Long telegramId = callbackQuery.getFrom().getId();
    String chatId = String.valueOf(callbackQuery.getMessage().getChatId());

    if (!TelegramPrivateChatGuard.isPrivateChat(callbackQuery)) {
      sendPlainText(chatId, TelegramPrivateChatGuard.PRIVATE_CHAT_REQUIRED_TEXT);
      return;
    }
    var stateOpt = wizard.getState(telegramId);
    if (stateOpt.isEmpty()) {
      sendPlainText(chatId, NO_FILTER_TEXT);
      return;
    }
    creationState.await(telegramId, toSubscriptionCriteria(stateOpt.get()));
    sendPlainText(chatId, NAME_PROMPT_TEXT);
  }

  /**
   * Handles the {@code SUB:START_SEARCH} callback from the empty subscriptions state (issue #475).
   *
   * <p>Starts the same filter wizard entry point used by {@code /search}
   * ({@link FilterCallbackHandler#startWizardMessage}), so a user with no subscriptions is never
   * stuck without knowing they must search first. After the search completes, the existing
   * {@link #CREATE_FROM_FILTER} button in the results navigation already offers to subscribe.
   *
   * @param callbackQuery the incoming callback query, never null
   */
  public void handleStartSearch(CallbackQuery callbackQuery) {
    Long telegramId = callbackQuery.getFrom().getId();
    String chatId = String.valueOf(callbackQuery.getMessage().getChatId());
    try {
      telegramClient.execute(filterCallbackHandler.startWizardMessage(telegramId, chatId));
    } catch (TelegramApiException e) {
      log.warn("Failed to start search wizard from empty subscriptions state: chatId={}", chatId, e);
    }
  }

  /**
   * Checks whether the given user is currently expected to reply with a subscription name.
   *
   * @param telegramId Telegram user identifier, never null
   * @return true if a subscription-name prompt is pending for this user
   */
  public boolean isAwaitingSubscriptionName(Long telegramId) {
    return creationState.isAwaitingName(telegramId);
  }

  /**
   * Applies free-text subscription-name input and creates the subscription.
   *
   * <p>Called when the user types a message while {@link #isAwaitingSubscriptionName} is true.
   * An invalid name re-prompts without discarding the pending search criteria.
   *
   * @param telegramId Telegram user identifier, never null
   * @param chatId     target chat identifier, never null
   * @param text       user-provided subscription name
   */
  public void handleSubscriptionNameText(Long telegramId, String chatId, String text) {
    var criteriaOpt = creationState.peek(telegramId);
    if (criteriaOpt.isEmpty()) {
      return;
    }
    String name = text == null ? "" : text.strip();
    if (name.isBlank() || name.length() > MAX_NAME_LENGTH) {
      sendPlainText(chatId, INVALID_NAME_TEXT);
      return;
    }
    var userOpt = userService.findByTelegramId(telegramId);
    if (userOpt.isEmpty()) {
      creationState.clear(telegramId);
      sendPlainText(chatId, UNREGISTERED_TEXT);
      return;
    }
    createSubscription(telegramId, chatId, userOpt.get().getId(), name, criteriaOpt.get());
  }

  private void createSubscription(Long telegramId, String chatId, Long userId, String name,
      SubscriptionSearchCriteria criteria) {
    var request = new CreateSubscriptionRequest(
        name, criteria, Set.of(TriggerType.NEW_LISTING),
        DeliveryMode.REALTIME, SubscriptionChannelType.TELEGRAM, null, null, null
    );
    try {
      subscriptionService.create(userId, request);
      creationState.clear(telegramId);
      sendPlainText(chatId, "🔔 Подписка «" + TelegramHtmlEscaper.escapeHtml(name) + "» создана.");
    } catch (SubscriptionLimitExceededException e) {
      creationState.clear(telegramId);
      sendPlainText(chatId, LIMIT_EXCEEDED_TEXT);
    }
  }

  /**
   * Handles a {@code SUB:PAUSE:<id>} callback.
   *
   * @param callbackQuery the incoming callback query, never null
   * @return toast text to show the user via {@code AnswerCallbackQuery}, never null
   */
  public String handlePause(CallbackQuery callbackQuery) {
    if (!TelegramPrivateChatGuard.isPrivateChat(callbackQuery)) {
      return TelegramPrivateChatGuard.PRIVATE_CHAT_REQUIRED_TEXT;
    }
    var userOpt = userService.findByTelegramId(callbackQuery.getFrom().getId());
    if (userOpt.isEmpty()) {
      return UNREGISTERED_TOAST;
    }
    Long id = parseId(callbackQuery.getData(), PAUSE_PREFIX);
    if (id == null) {
      return NOT_FOUND_TOAST;
    }
    try {
      subscriptionService.pause(userOpt.get().getId(), id);
      return PAUSED_TOAST;
    } catch (SubscriptionNotFoundException e) {
      return NOT_FOUND_TOAST;
    }
  }

  /**
   * Handles a {@code SUB:RESUME:<id>} callback.
   *
   * @param callbackQuery the incoming callback query, never null
   * @return toast text to show the user via {@code AnswerCallbackQuery}, never null
   */
  public String handleResume(CallbackQuery callbackQuery) {
    if (!TelegramPrivateChatGuard.isPrivateChat(callbackQuery)) {
      return TelegramPrivateChatGuard.PRIVATE_CHAT_REQUIRED_TEXT;
    }
    var userOpt = userService.findByTelegramId(callbackQuery.getFrom().getId());
    if (userOpt.isEmpty()) {
      return UNREGISTERED_TOAST;
    }
    Long id = parseId(callbackQuery.getData(), RESUME_PREFIX);
    if (id == null) {
      return NOT_FOUND_TOAST;
    }
    try {
      subscriptionService.resume(userOpt.get().getId(), id);
      return RESUMED_TOAST;
    } catch (SubscriptionNotFoundException e) {
      return NOT_FOUND_TOAST;
    } catch (SubscriptionLimitExceededException e) {
      return LIMIT_EXCEEDED_TEXT;
    }
  }

  /**
   * Handles a {@code SUB:DELETE:<id>} callback.
   *
   * @param callbackQuery the incoming callback query, never null
   * @return toast text to show the user via {@code AnswerCallbackQuery}, never null
   */
  public String handleDelete(CallbackQuery callbackQuery) {
    if (!TelegramPrivateChatGuard.isPrivateChat(callbackQuery)) {
      return TelegramPrivateChatGuard.PRIVATE_CHAT_REQUIRED_TEXT;
    }
    var userOpt = userService.findByTelegramId(callbackQuery.getFrom().getId());
    if (userOpt.isEmpty()) {
      return UNREGISTERED_TOAST;
    }
    Long id = parseId(callbackQuery.getData(), DELETE_PREFIX);
    if (id == null) {
      return NOT_FOUND_TOAST;
    }
    try {
      subscriptionService.delete(userOpt.get().getId(), id);
    } catch (SubscriptionNotFoundException e) {
      log.debug("SUB:DELETE for already-deleted subscription: id={}", id);
    }
    return DELETED_TOAST;
  }

  private SubscriptionSearchCriteria toSubscriptionCriteria(SearchFilterState state) {
    return new SubscriptionSearchCriteria(
        state.getDealType(),
        state.getPropertyType(),
        null,
        null,
        state.getCityId(),
        state.getPriceMin(),
        state.getPriceMax(),
        state.getRooms(),
        state.getQuery(),
        state.getOwnerOnly()
    );
  }

  private void sendItems(String chatId, List<SubscriptionResponse> items) {
    for (var item : items) {
      try {
        sendItem(chatId, item);
      } catch (Exception e) {
        log.error("Unexpected error sending subscription item: subscriptionId={}", item.id(), e);
      }
    }
  }

  private void sendItem(String chatId, SubscriptionResponse item) {
    String text = TelegramHtmlEscaper.escapeHtml(item.name()) + "\n"
        + statusLabel(item.active()) + " · " + deliveryModeLabel(item.deliveryMode());
    var toggleButton = item.active()
        ? navBtn("⏸ Пауза", PAUSE_PREFIX + item.id())
        : navBtn("▶️ Возобновить", RESUME_PREFIX + item.id());
    var deleteButton = navBtn("🗑 Удалить", DELETE_PREFIX + item.id());
    var keyboard = InlineKeyboardMarkup.builder()
        .keyboardRow(new InlineKeyboardRow(toggleButton, deleteButton))
        .build();
    try {
      telegramClient.execute(SendMessage.builder()
          .chatId(chatId)
          .text(text)
          .parseMode("HTML")
          .replyMarkup(keyboard)
          .build());
    } catch (TelegramApiException e) {
      log.warn("Failed to send subscription item: chatId={}", chatId, e);
    }
  }

  private String statusLabel(boolean active) {
    return active ? "активна" : "на паузе";
  }

  private String deliveryModeLabel(DeliveryMode deliveryMode) {
    if (deliveryMode == null) {
      return "—";
    }
    return switch (deliveryMode) {
      case REALTIME -> "мгновенно";
      case DIGEST -> "дайджест";
      case DAILY -> "раз в день";
    };
  }

  private void sendNavigation(String chatId) {
    var menuButton = navBtn("🏠 Главное меню", SearchResultSender.ACTION_MENU);
    var keyboard = InlineKeyboardMarkup.builder()
        .keyboardRow(new InlineKeyboardRow(menuButton))
        .build();
    try {
      telegramClient.execute(SendMessage.builder()
          .chatId(chatId)
          .text("🔔 <b>Мои подписки</b>")
          .parseMode("HTML")
          .replyMarkup(keyboard)
          .build());
    } catch (TelegramApiException e) {
      log.warn("Failed to send subscriptions navigation: chatId={}", chatId, e);
    }
  }

  private void sendText(String chatId, String text) {
    try {
      telegramClient.execute(SendMessage.builder()
          .chatId(chatId)
          .text(text)
          .parseMode("HTML")
          .replyMarkup(keyboardFactory.buildBackToMenu())
          .build());
    } catch (TelegramApiException e) {
      log.warn("Failed to send subscriptions message: chatId={}", chatId, e);
    }
  }

  /**
   * Sends the empty-subscriptions message with a CTA into the search wizard, instead of the
   * plain back-to-menu dead end (issue #475).
   *
   * @param chatId target chat identifier, never null
   */
  private void sendEmptyState(String chatId) {
    var searchButton = navBtn("🔍 Начать поиск и подписаться", START_SEARCH);
    var menuButton = navBtn("🏠 Главное меню", SearchResultSender.ACTION_MENU);
    var keyboard = InlineKeyboardMarkup.builder()
        .keyboardRow(new InlineKeyboardRow(searchButton))
        .keyboardRow(new InlineKeyboardRow(menuButton))
        .build();
    try {
      telegramClient.execute(SendMessage.builder()
          .chatId(chatId)
          .text(EMPTY_TEXT)
          .parseMode("HTML")
          .replyMarkup(keyboard)
          .build());
    } catch (TelegramApiException e) {
      log.warn("Failed to send empty subscriptions state: chatId={}", chatId, e);
    }
  }

  private void sendPlainText(String chatId, String text) {
    try {
      telegramClient.execute(SendMessage.builder().chatId(chatId).text(text).build());
    } catch (TelegramApiException e) {
      log.warn("Failed to send subscriptions prompt: chatId={}", chatId, e);
    }
  }

  private InlineKeyboardButton navBtn(String text, String callbackData) {
    return InlineKeyboardButton.builder().text(text).callbackData(callbackData).build();
  }

  private Long parseId(String data, String prefix) {
    try {
      return Long.valueOf(data.substring(prefix.length()));
    } catch (RuntimeException e) {
      log.warn("Malformed subscriptions callback data: {}", data);
      return null;
    }
  }
}
