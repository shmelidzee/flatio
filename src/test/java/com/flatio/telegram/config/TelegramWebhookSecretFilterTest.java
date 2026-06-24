package com.flatio.telegram.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class TelegramWebhookSecretFilterTest {

  private static final String SECRET_HEADER = "X-Telegram-Bot-Api-Secret-Token";

  private TelegramWebhookSecretFilter filterWithSecret(String secret) {
    return new TelegramWebhookSecretFilter(new BotConfig("t:1", "bot", "https://api.flatio.by", secret));
  }

  @Test
  void should_pass_request_when_secret_token_matches() throws Exception {
    // Given
    var filter = filterWithSecret("correct-secret");
    var request = new MockHttpServletRequest();
    request.addHeader(SECRET_HEADER, "correct-secret");
    var response = new MockHttpServletResponse();
    var chain = mock(FilterChain.class);

    // When
    filter.doFilter(request, response, chain);

    // Then
    verify(chain).doFilter(request, response);
    assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
  }

  @Test
  void should_reject_request_when_secret_token_is_wrong() throws Exception {
    // Given
    var filter = filterWithSecret("correct-secret");
    var request = new MockHttpServletRequest();
    request.addHeader(SECRET_HEADER, "wrong-secret");
    var response = new MockHttpServletResponse();
    var chain = mock(FilterChain.class);

    // When
    filter.doFilter(request, response, chain);

    // Then
    assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
    verifyNoInteractions(chain);
  }

  @Test
  void should_reject_request_when_secret_token_header_is_absent() throws Exception {
    // Given
    var filter = filterWithSecret("correct-secret");
    var request = new MockHttpServletRequest();
    var response = new MockHttpServletResponse();
    var chain = mock(FilterChain.class);

    // When
    filter.doFilter(request, response, chain);

    // Then
    assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
    verifyNoInteractions(chain);
  }

  @Test
  void should_pass_request_when_secret_token_not_configured() throws Exception {
    // Given — operator has not set TELEGRAM_WEBHOOK_SECRET_TOKEN (backward-compatible mode)
    var filter = filterWithSecret(null);
    var request = new MockHttpServletRequest();
    var response = new MockHttpServletResponse();
    var chain = mock(FilterChain.class);

    // When
    filter.doFilter(request, response, chain);

    // Then — filter is a no-op without configuration
    verify(chain).doFilter(request, response);
  }

  @Test
  void should_pass_request_when_secret_token_is_blank() throws Exception {
    // Given — webhook-secret-token is set to empty string (default from application.yml)
    var filter = filterWithSecret("");
    var request = new MockHttpServletRequest();
    var response = new MockHttpServletResponse();
    var chain = mock(FilterChain.class);

    // When
    filter.doFilter(request, response, chain);

    // Then — blank token treated as unconfigured
    verify(chain).doFilter(request, response);
  }

  @Test
  void should_reject_request_when_token_shares_prefix_with_configured_secret() throws Exception {
    // Given — "correct" is a prefix of "correct-secret": timing-safe comparison must still reject
    var filter = filterWithSecret("correct-secret");
    var request = new MockHttpServletRequest();
    request.addHeader(SECRET_HEADER, "correct");
    var response = new MockHttpServletResponse();
    var chain = mock(FilterChain.class);

    // When
    filter.doFilter(request, response, chain);

    // Then — prefix match must be rejected, not accepted
    assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
    verifyNoInteractions(chain);
  }
}
