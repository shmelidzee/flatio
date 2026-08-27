package com.flatio.integration.nbrb.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.github.resilience4j.retry.annotation.Retry;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * Fetches a single currency's official exchange rate against BYN from the National Bank of
 * Belarus (NBRb) public API.
 *
 * <p>Endpoint: {@code /exrates/rates/{currencyId}?periodicity=0}, where {@code currencyId} is
 * NBRb's own numeric identifier for the currency (see {@link
 * com.flatio.integration.nbrb.config.NbrbProperties#currencyIds}), not its ISO code.
 *
 * <p>Rate limiting, circuit breaker, and retry with exponential backoff are applied via
 * Resilience4j ({@code connector-nbrb}), matching every other connector in the project.
 */
@Service
@Slf4j
public class NbrbClient {

  private static final String RATE_FIELD = "Cur_OfficialRate";
  private static final String SCALE_FIELD = "Cur_Scale";
  private static final int RATE_SCALE = 6;

  private final RestClient restClient;
  private final ObjectMapper objectMapper;

  public NbrbClient(@Qualifier("nbrbRestClient") RestClient restClient, ObjectMapper objectMapper) {
    this.restClient = restClient;
    this.objectMapper = objectMapper;
  }

  /**
   * Fetches the given currency's official rate against BYN.
   *
   * @param nbrbCurrencyId NBRb's internal numeric identifier for the currency
   * @return how many BYN one unit of the currency is worth, or empty if the response was
   *     malformed or the currency ID does not exist at NBRb
   */
  @RateLimiter(name = "connector-nbrb")
  @CircuitBreaker(name = "connector-nbrb")
  @Retry(name = "connector-nbrb", fallbackMethod = "fetchRateFallback")
  public Optional<BigDecimal> fetchRate(int nbrbCurrencyId) {
    String body = restClient.get()
        .uri("/exrates/rates/{id}?periodicity=0", nbrbCurrencyId)
        .retrieve()
        .body(String.class);
    return parseRate(body, nbrbCurrencyId);
  }

  private Optional<BigDecimal> parseRate(String body, int nbrbCurrencyId) {
    try {
      JsonNode node = objectMapper.readTree(body);
      BigDecimal officialRate = node.path(RATE_FIELD).decimalValue();
      BigDecimal scale = node.path(SCALE_FIELD).decimalValue();
      if (officialRate == null || officialRate.compareTo(BigDecimal.ZERO) <= 0
          || scale == null || scale.compareTo(BigDecimal.ZERO) <= 0) {
        log.warn("NBRb returned unexpected rate payload: currencyId={}, body={}", nbrbCurrencyId, node);
        return Optional.empty();
      }
      // Cur_OfficialRate is quoted per Cur_Scale units (usually 1, but not always — e.g. some
      // currencies are quoted per 100). Dividing normalizes to "per 1 unit" regardless.
      return Optional.of(officialRate.divide(scale, RATE_SCALE, RoundingMode.HALF_UP));
    } catch (Exception e) {
      log.warn("Failed to parse NBRb rate response: currencyId={}, error={}", nbrbCurrencyId, e.getMessage());
      return Optional.empty();
    }
  }

  // Package-private: Resilience4j AOP proxy requires fallback methods to be accessible from the same package.
  Optional<BigDecimal> fetchRateFallback(int nbrbCurrencyId, Exception e) {
    log.warn("NBRb rate fetch failed after retries: currencyId={}, error={}", nbrbCurrencyId, e.getMessage());
    return Optional.empty();
  }
}
