package com.flatio.telegram.handler;

import com.flatio.telegram.config.BotConfig;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer;
import org.telegram.telegrambots.longpolling.starter.SpringLongPollingBot;
import org.telegram.telegrambots.meta.api.objects.Update;

/**
 * Long-polling delivery transport for {@link FlatioBot}, active only in the {@code local} profile.
 *
 * <p>Implements {@link SpringLongPollingBot} so the
 * {@code telegrambots-springboot-longpolling-starter} auto-configuration discovers and registers
 * it automatically. All Update-processing logic lives in {@link FlatioBot} — this class only
 * adapts the long-polling delivery mechanism to it.
 */
@Component
@Profile("local")
@RequiredArgsConstructor
public class FlatioLongPollingBot implements SpringLongPollingBot, LongPollingUpdateConsumer {

  private final BotConfig botConfig;
  private final FlatioBot flatioBot;

  /**
   * Returns the bot API token used for long-polling authentication.
   *
   * @return bot token from {@code TELEGRAM_BOT_TOKEN}, never null
   */
  @Override
  public String getBotToken() {
    return botConfig.token();
  }

  /**
   * Returns this instance as the update consumer; all updates are handled in-place.
   *
   * @return this bot instance, never null
   */
  @Override
  public LongPollingUpdateConsumer getUpdatesConsumer() {
    return this;
  }

  /**
   * Forwards every received update to {@link FlatioBot} for asynchronous processing.
   *
   * @param updates list of updates received from Telegram, never null
   */
  @Override
  public void consume(List<Update> updates) {
    updates.forEach(flatioBot::handleUpdateAsync);
  }
}
