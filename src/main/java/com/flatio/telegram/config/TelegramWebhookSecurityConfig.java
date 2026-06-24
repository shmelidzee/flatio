package com.flatio.telegram.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

/**
 * Dedicated Spring Security filter chain for the Telegram webhook endpoint.
 *
 * <p>The webhook path is {@code /<bot-token>}. This chain is evaluated before the main
 * {@link com.flatio.security.SecurityConfig} chain (via {@link Order}{@code (1)}) and applies
 * only to that path. It permits the request at the HTTP level (Telegram cannot send a JWT) and
 * enforces {@link TelegramWebhookSecretFilter} as a second layer of protection:
 * even if the URL token is leaked from logs, an attacker cannot forge an update without
 * also knowing the {@code X-Telegram-Bot-Api-Secret-Token} value.
 *
 * <p>Active only outside the {@code local} profile where long-polling is used instead.
 */
@Configuration
@Profile("!local")
@RequiredArgsConstructor
public class TelegramWebhookSecurityConfig {

  private final BotConfig botConfig;

  /**
   * Security filter chain that handles only the Telegram webhook path.
   *
   * @param http Spring Security HTTP builder
   * @return configured {@link SecurityFilterChain} for the webhook path, never null
   * @throws Exception if HTTP security configuration fails
   */
  @Bean
  @Order(1)
  public SecurityFilterChain webhookFilterChain(HttpSecurity http) throws Exception {
    TelegramWebhookSecretFilter secretFilter = new TelegramWebhookSecretFilter(botConfig);
    return http
        .securityMatcher(new AntPathRequestMatcher("/" + botConfig.token()))
        .csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
        .addFilterBefore(secretFilter, UsernamePasswordAuthenticationFilter.class)
        .build();
  }
}
