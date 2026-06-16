package com.flatio.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.telegram.telegrambots.longpolling.starter.TelegramBotStarterConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reproduces and guards against a Spring bean conflict between the two Telegram delivery
 * starters: {@code telegrambots-springboot-longpolling-starter} and
 * {@code telegrambots-springboot-webhook-starter} each auto-configure a bean named
 * {@code telegramBotsApplication}. Having both active at once fails startup with
 * {@code BeanDefinitionOverrideException} — regardless of which {@code @Profile} our own
 * beans ({@code FlatioLongPollingBot}, {@code TelegramWebhookConfig}) use, since
 * {@code @Profile} does not gate the starters' own auto-configuration classes.
 *
 * <p>{@code application.yml} resolves this by excluding exactly one starter's auto-configuration
 * per active profile (see the profile-activated documents at the bottom of the file).
 */
class TelegramBotStarterAutoConfigurationTest {

  @Test
  void should_fail_to_load_context_when_both_starters_are_active_without_exclusion() {
    // Given — neither starter excluded, reproducing the original production bug
    var contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(
            TelegramBotStarterConfiguration.class,
            org.telegram.telegrambots.webhook.starter.TelegramBotStarterConfiguration.class));

    // When / Then
    contextRunner.run(context -> assertThat(context).hasFailed());
  }

  @Test
  void should_load_context_when_only_longpolling_starter_is_active() {
    // Given — mirrors application.yml excluding the webhook starter for the local profile
    var contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(TelegramBotStarterConfiguration.class));

    // When / Then
    contextRunner.run(context -> assertThat(context).hasNotFailed());
  }

  @Test
  void should_load_context_when_only_webhook_starter_is_active() {
    // Given — mirrors application.yml excluding the longpolling starter outside the local profile
    var contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(
            org.telegram.telegrambots.webhook.starter.TelegramBotStarterConfiguration.class));

    // When / Then
    contextRunner.run(context -> assertThat(context).hasNotFailed());
  }
}
