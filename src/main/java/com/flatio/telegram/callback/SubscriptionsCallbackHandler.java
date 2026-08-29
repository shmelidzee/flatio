package com.flatio.telegram.callback;

import com.flatio.common.exception.SubscriptionLimitExceededException;
import com.flatio.common.exception.SubscriptionNotFoundException;
import com.flatio.common.util.TelegramHtmlEscaper;
import com.flatio.common.util.TelegramPrivateChatGuard;
import com.flatio.domain.city.City;
import com.flatio.domain.listing.DealType;
import com.flatio.domain.subscription.DeliveryMode;
import com.flatio.domain.subscription.SubscriptionChannelType;
import com.flatio.domain.subscription.TriggerType;
import com.flatio.service.CityService;
import com.flatio.service.SubscriptionService;
import com.flatio.service.UserService;
import com.flatio.telegram.handler.SearchResultSender;
import com.flatio.telegram.keyboard.MainMenuKeyboardFactory;
import com.flatio.telegram.state.SearchFilterState;
import com.flatio.telegram.state.SearchFilterWizard;
import com.flatio.telegram.state.SubscriptionCreationState;
import com.flatio.telegram.state.SubscriptionEditState;
import com.flatio.web.dto.CreateSubscriptionRequest;
import com.flatio.web.dto.SubscriptionResponse;
import com.flatio.web.dto.SubscriptionSearchCriteria;
import com.flatio.web.dto.UpdateSubscriptionRequest;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 * viewing the paginated subscriptions list, and pausing/resuming/deleting/editing subscriptions
 * (issues #458, #478, #479).
 *
 * <p>Reuses {@link SubscriptionService}, the same service backing the REST
 * {@code /api/v1/subscriptions} endpoint (Telegram and REST API share services, per project
 * architecture rules). A page of subscriptions is rendered as a single Telegram message — one line
 * per subscription (name, status, and a short summary of its search criteria) with one row of
 * pause/resume + delete buttons per subscription — followed by a single pagination/navigation
 * message, matching the shape already used for favorites by {@link FavoritesCallbackHandler}
 * (issue #478 replaced the earlier one-message-per-subscription rendering shipped in #458).
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
  /** Callback prefix for pausing a subscription. */
  public static final String PAUSE_PREFIX = "SUB:PAUSE:";
  /** Callback prefix for resuming a subscription. */
  public static final String RESUME_PREFIX = "SUB:RESUME:";
  /** Callback prefix for deleting a subscription. */
  public static final String DELETE_PREFIX = "SUB:DELETE:";
  /** Callback prefix for starting the edit-criteria wizard on a subscription. */
  public static final String EDIT_PREFIX = "SUB:EDIT:";
  /** Callback prefix for subscriptions-list pagination. */
  public static final String PAGE_PREFIX = "SUB:PAGE:";
  /** Callback data for advancing to the next subscriptions page. */
  public static final String PAGE_NEXT = "SUB:PAGE:NEXT";
  /** Callback data for going back to the previous subscriptions page. */
  public static final String PAGE_PREV = "SUB:PAGE:PREV";

  private static final int PAGE_SIZE = 5;
  private static final long SESSION_TTL_MINUTES = 30;
  private static final long MAX_SESSIONS = 10_000;
  private static final int MAX_ROOMS_LABEL = 4;
  private static final int MAX_NAME_LENGTH = 255;

  private static final String EMPTY_TEXT = "🔔 У вас пока нет подписок на поиск.";
  private static final String SESSION_EXPIRED_TEXT = "Список устарел. Откройте раздел «🔔 Мои подписки» заново.";
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
  private static final String EDIT_SESSION_EXPIRED_TEXT =
      "Сессия редактирования устарела. Откройте раздел «🔔 Мои подписки» и нажмите «✏️ Изменить» ещё раз.";
  private static final String EDIT_NOT_FOUND_TEXT =
      "Эта подписка была удалена и не может быть сохранена.";

  private final UserService userService;
  private final SubscriptionService subscriptionService;
  private final CityService cityService;
  private final MainMenuKeyboardFactory keyboardFactory;
  private final SearchFilterWizard wizard;
  private final FilterCallbackHandler filterCallbackHandler;
  private final SubscriptionCreationState creationState;
  private final SubscriptionEditState editState;
  private final TelegramClient telegramClient;

  private record PageState(int page, int totalPages) {}

  // Caffeine, not a plain map, so an abandoned pagination session is evicted instead of occupying
  // memory for the lifetime of the JVM — same rationale as FavoritesCallbackHandler#pageSessions.
  private final Map<Long, PageState> pageSessions = Caffeine.newBuilder()
      .expireAfterAccess(Duration.ofMinutes(SESSION_TTL_MINUTES))
      .maximumSize(MAX_SESSIONS)
      .<Long, PageState>build()
      .asMap();

  /**
   * Renders the first page of the user's subscriptions list.
   *
   * @param callbackQuery the incoming callback query, never null
   */
  public void handle(CallbackQuery callbackQuery) {
    renderPage(callbackQuery, 0);
  }

  /**
   * Handles a {@code SUB:PAGE:NEXT}/{@code SUB:PAGE:PREV} pagination callback.
   *
   * @param callbackQuery the incoming callback query, never null
   */
  public void handlePage(CallbackQuery callbackQuery) {
    Long telegramId = callbackQuery.getFrom().getId();
    String chatId = String.valueOf(callbackQuery.getMessage().getChatId());
    var session = pageSessions.get(telegramId);
    if (session == null) {
      sendText(chatId, SESSION_EXPIRED_TEXT);
      return;
    }
    int next = PAGE_NEXT.equals(callbackQuery.getData())
        ? Math.min(session.page() + 1, session.totalPages() - 1)
        : Math.max(session.page() - 1, 0);
    renderPage(callbackQuery, next);
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

  /**
   * Handles a {@code SUB:EDIT:<id>} callback — starts the filter wizard pre-filled with the
   * subscription's current criteria (issue #479).
   *
   * @param callbackQuery the incoming callback query, never null
   */
  public void handleEdit(CallbackQuery callbackQuery) {
    Long telegramId = callbackQuery.getFrom().getId();
    String chatId = String.valueOf(callbackQuery.getMessage().getChatId());

    if (!TelegramPrivateChatGuard.isPrivateChat(callbackQuery)) {
      sendPlainText(chatId, TelegramPrivateChatGuard.PRIVATE_CHAT_REQUIRED_TEXT);
      return;
    }
    var userOpt = userService.findByTelegramId(telegramId);
    if (userOpt.isEmpty()) {
      sendPlainText(chatId, UNREGISTERED_TEXT);
      return;
    }
    Long id = parseId(callbackQuery.getData(), EDIT_PREFIX);
    if (id == null) {
      sendPlainText(chatId, NOT_FOUND_TOAST);
      return;
    }
    try {
      var subscription = subscriptionService.findByIdForUser(userOpt.get().getId(), id);
      editState.start(telegramId, subscription);
      var prefilled = toSearchFilterState(subscription.searchCriteria());
      prefilled.setEditingSubscriptionId(id);
      telegramClient.execute(filterCallbackHandler.startWizardMessageForEdit(telegramId, chatId, prefilled));
    } catch (SubscriptionNotFoundException e) {
      sendPlainText(chatId, NOT_FOUND_TOAST);
    } catch (TelegramApiException e) {
      log.warn("Failed to send edit wizard message: chatId={}", chatId, e);
    }
  }

  /**
   * Handles the {@code FILTER:SEARCH} callback when the wizard is in subscription-edit mode
   * (issue #479) — saves the edited criteria via {@link SubscriptionService#update} instead of
   * running a listing search. {@link com.flatio.telegram.handler.FlatioBot} routes here based on
   * {@link SearchFilterState#getEditingSubscriptionId()}.
   *
   * <p>Name, triggers, and delivery settings are carried over unchanged from the snapshot taken
   * when editing started ({@link SubscriptionEditState}) — the wizard only collects criteria.
   *
   * @param callbackQuery the incoming callback query, never null
   */
  public void handleSaveEdit(CallbackQuery callbackQuery) {
    Long telegramId = callbackQuery.getFrom().getId();
    String chatId = String.valueOf(callbackQuery.getMessage().getChatId());

    if (!TelegramPrivateChatGuard.isPrivateChat(callbackQuery)) {
      sendPlainText(chatId, TelegramPrivateChatGuard.PRIVATE_CHAT_REQUIRED_TEXT);
      return;
    }
    var stateOpt = wizard.getState(telegramId);
    var originalOpt = editState.get(telegramId);
    if (stateOpt.isEmpty() || originalOpt.isEmpty() || stateOpt.get().getEditingSubscriptionId() == null) {
      sendPlainText(chatId, EDIT_SESSION_EXPIRED_TEXT);
      return;
    }
    var userOpt = userService.findByTelegramId(telegramId);
    if (userOpt.isEmpty()) {
      sendPlainText(chatId, UNREGISTERED_TEXT);
      return;
    }
    var state = stateOpt.get();
    var original = originalOpt.get();
    var request = buildUpdateRequest(original, state);
    try {
      subscriptionService.update(userOpt.get().getId(), state.getEditingSubscriptionId(), request);
      sendPlainText(chatId, "💾 Подписка «" + TelegramHtmlEscaper.escapeHtml(original.name()) + "» обновлена.");
    } catch (SubscriptionNotFoundException e) {
      sendPlainText(chatId, EDIT_NOT_FOUND_TEXT);
    } finally {
      wizard.reset(telegramId);
      editState.clear(telegramId);
    }
  }

  private UpdateSubscriptionRequest buildUpdateRequest(SubscriptionResponse original, SearchFilterState state) {
    return new UpdateSubscriptionRequest(
        original.name(), toSubscriptionCriteria(state), original.triggers(),
        original.deliveryMode(), original.channelType(), original.priceDropThreshold(),
        original.quietHoursStart(), original.quietHoursEnd()
    );
  }

  private SearchFilterState toSearchFilterState(SubscriptionSearchCriteria criteria) {
    var state = new SearchFilterState();
    state.setDealType(criteria.dealType());
    state.setPropertyType(criteria.propertyType());
    state.setCityId(criteria.cityId());
    state.setPriceMin(criteria.priceMin());
    state.setPriceMax(criteria.priceMax());
    state.setRooms(criteria.rooms());
    state.setQuery(criteria.query());
    state.setOwnerOnly(criteria.ownerOnly());
    return state;
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

  private void renderPage(CallbackQuery callbackQuery, int page) {
    Long telegramId = callbackQuery.getFrom().getId();
    String chatId = String.valueOf(callbackQuery.getMessage().getChatId());

    if (!TelegramPrivateChatGuard.isPrivateChat(callbackQuery)) {
      log.debug("SUB callback rejected outside a private chat: chatId={}", chatId);
      sendText(chatId, TelegramPrivateChatGuard.PRIVATE_CHAT_REQUIRED_TEXT);
      return;
    }

    var userOpt = userService.findByTelegramId(telegramId);
    if (userOpt.isEmpty()) {
      log.warn("SUB callback from unregistered telegramId={}", telegramId);
      sendText(chatId, EMPTY_TEXT);
      return;
    }

    var pageable = PageRequest.of(page, PAGE_SIZE, Sort.by(Sort.Direction.DESC, "createdAt"));
    var result = subscriptionService.findByUser(userOpt.get().getId(), pageable);
    if (result.isEmpty()) {
      pageSessions.remove(telegramId);
      sendText(chatId, EMPTY_TEXT);
      return;
    }

    pageSessions.put(telegramId, new PageState(page, result.getTotalPages()));
    log.debug("Subscriptions page rendered: telegramId={}, page={}, totalPages={}",
        telegramId, page, result.getTotalPages());
    sendItemsPage(chatId, result.getContent());
    sendNavigation(chatId, page, result.getTotalPages());
  }

  /**
   * Renders one page of subscriptions as a single Telegram message — one text line per
   * subscription and one button row per subscription — instead of a message per subscription.
   *
   * <p>Formatting one subscription's line/buttons is isolated: if it throws, that subscription is
   * skipped and the rest of the page still renders (issue #478 AC).
   *
   * @param chatId target chat identifier, never null
   * @param items  subscriptions on this page, never null
   */
  private void sendItemsPage(String chatId, List<SubscriptionResponse> items) {
    var textBuilder = new StringBuilder();
    var markupBuilder = InlineKeyboardMarkup.builder();
    boolean any = false;
    for (var item : items) {
      try {
        String line = formatItemLine(item);
        var buttonsRow = buildItemButtons(item);
        if (any) {
          textBuilder.append("\n\n");
        }
        textBuilder.append(line);
        markupBuilder.keyboardRow(buttonsRow);
        any = true;
      } catch (Exception e) {
        log.error("Unexpected error formatting subscription item: subscriptionId={}", item.id(), e);
      }
    }
    if (!any) {
      log.warn("All subscription items on page failed to format: chatId={}", chatId);
      sendText(chatId, EMPTY_TEXT);
      return;
    }
    try {
      telegramClient.execute(SendMessage.builder()
          .chatId(chatId)
          .text(textBuilder.toString())
          .parseMode("HTML")
          .replyMarkup(markupBuilder.build())
          .build());
    } catch (TelegramApiException e) {
      log.warn("Failed to send subscriptions page: chatId={}", chatId, e);
    }
  }

  private String formatItemLine(SubscriptionResponse item) {
    var sb = new StringBuilder();
    sb.append("<b>").append(TelegramHtmlEscaper.escapeHtml(item.name())).append("</b>\n")
        .append(statusLabel(item.active())).append(" · ").append(deliveryModeLabel(item.deliveryMode()));
    String summary = buildCriteriaSummary(item.searchCriteria());
    if (!summary.isBlank()) {
      sb.append("\n").append(summary);
    }
    return sb.toString();
  }

  private InlineKeyboardRow buildItemButtons(SubscriptionResponse item) {
    var toggleButton = item.active()
        ? navBtn("⏸ Пауза", PAUSE_PREFIX + item.id())
        : navBtn("▶️ Возобновить", RESUME_PREFIX + item.id());
    var editButton = navBtn("✏️ Изменить", EDIT_PREFIX + item.id());
    var deleteButton = navBtn("🗑 Удалить", DELETE_PREFIX + item.id());
    return new InlineKeyboardRow(toggleButton, editButton, deleteButton);
  }

  /**
   * Builds a compact one-line summary of a subscription's search criteria, e.g.
   * «Аренда · Минск · 400–700 BYN · 2 комн.» — only the fields actually set on the criteria are
   * included (issue #478 FR-NAV-7).
   *
   * @param criteria the subscription's search criteria, may be null
   * @return summary string, empty (never null) if criteria is null or every field is unset
   */
  private String buildCriteriaSummary(SubscriptionSearchCriteria criteria) {
    if (criteria == null) {
      return "";
    }
    var parts = new ArrayList<String>();
    if (criteria.dealType() != null) {
      parts.add(dealTypeLabel(criteria.dealType()));
    }
    String city = resolveCityLabel(criteria);
    if (city != null) {
      parts.add(city);
    }
    String price = priceRangeLabel(criteria.priceMin(), criteria.priceMax());
    if (price != null) {
      parts.add(price);
    }
    if (criteria.rooms() != null) {
      parts.add(roomsLabel(criteria.rooms()));
    }
    return String.join(" · ", parts);
  }

  private String resolveCityLabel(SubscriptionSearchCriteria criteria) {
    String city = resolveCityName(criteria);
    return city == null ? null : TelegramHtmlEscaper.escapeHtml(city);
  }

  private String resolveCityName(SubscriptionSearchCriteria criteria) {
    if (criteria.city() != null && !criteria.city().isBlank()) {
      return criteria.city();
    }
    if (criteria.cityId() != null) {
      return cityService.findById(criteria.cityId()).map(City::getNameRu).orElse(null);
    }
    return null;
  }

  private String priceRangeLabel(BigDecimal priceMin, BigDecimal priceMax) {
    if (priceMin == null && priceMax == null) {
      return null;
    }
    if (priceMin == null) {
      return "до " + priceMax.stripTrailingZeros().toPlainString() + " BYN";
    }
    if (priceMax == null) {
      return "от " + priceMin.stripTrailingZeros().toPlainString() + " BYN";
    }
    return priceMin.stripTrailingZeros().toPlainString() + "–" + priceMax.stripTrailingZeros().toPlainString() + " BYN";
  }

  private String roomsLabel(Integer rooms) {
    String value = rooms >= MAX_ROOMS_LABEL ? MAX_ROOMS_LABEL + "+" : rooms.toString();
    return value + " комн.";
  }

  private String dealTypeLabel(DealType dealType) {
    return switch (dealType) {
      case RENT -> "Аренда";
      case SELL -> "Продажа";
      case RENT_DAILY -> "Посуточно";
    };
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

  private void sendNavigation(String chatId, int page, int totalPages) {
    var navButtons = new ArrayList<InlineKeyboardButton>();
    if (page > 0) {
      navButtons.add(navBtn("← Предыдущие", PAGE_PREV));
    }
    if (page < totalPages - 1) {
      navButtons.add(navBtn("Ещё →", PAGE_NEXT));
    }
    var markupBuilder = InlineKeyboardMarkup.builder();
    if (!navButtons.isEmpty()) {
      markupBuilder.keyboardRow(new InlineKeyboardRow(navButtons));
    }
    markupBuilder.keyboardRow(new InlineKeyboardRow(navBtn("🏠 Главное меню", SearchResultSender.ACTION_MENU)));

    try {
      telegramClient.execute(SendMessage.builder()
          .chatId(chatId)
          .text("🔔 <b>Мои подписки</b>\n📄 Страница " + (page + 1) + " из " + totalPages)
          .parseMode("HTML")
          .replyMarkup(markupBuilder.build())
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
