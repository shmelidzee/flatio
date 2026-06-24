package com.flatio.telegram.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Validates the {@code X-Telegram-Bot-Api-Secret-Token} header on incoming Telegram webhook
 * requests. When {@code TELEGRAM_WEBHOOK_SECRET_TOKEN} is configured, Telegram includes this
 * header on every update (set via {@code SetWebhook.secretToken}). Requests that are missing
 * the header or carry the wrong value are rejected with HTTP 403 before the bot handler runs.
 *
 * <p>The filter is opt-in: if no secret token is configured it passes all requests through
 * unchanged, preserving backward compatibility with deployments that have not yet set the
 * environment variable.
 *
 * <p>This class is instantiated directly by {@link TelegramWebhookSecurityConfig} and is
 * not registered as a Servlet filter — it only runs inside the dedicated webhook
 * {@link org.springframework.security.web.SecurityFilterChain}.
 */
@Slf4j
@RequiredArgsConstructor
public class TelegramWebhookSecretFilter extends OncePerRequestFilter {

  private static final String SECRET_TOKEN_HEADER = "X-Telegram-Bot-Api-Secret-Token";

  private final BotConfig botConfig;

  /**
   * Checks the {@code X-Telegram-Bot-Api-Secret-Token} header and either passes the request
   * through or responds with HTTP 403.
   *
   * @param request  the incoming HTTP request
   * @param response the HTTP response
   * @param chain    the remaining filter chain
   * @throws ServletException if a servlet error occurs
   * @throws IOException      if an I/O error occurs
   */
  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String configured = botConfig.webhookSecretToken();
    if (configured == null || configured.isBlank()) {
      chain.doFilter(request, response);
      return;
    }
    String received = request.getHeader(SECRET_TOKEN_HEADER);
    if (!configured.equals(received)) {
      log.warn("Webhook request rejected: invalid or missing {}", SECRET_TOKEN_HEADER);
      response.setStatus(HttpServletResponse.SC_FORBIDDEN);
      return;
    }
    chain.doFilter(request, response);
  }
}
