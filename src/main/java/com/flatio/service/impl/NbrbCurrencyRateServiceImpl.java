package com.flatio.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flatio.service.CurrencyRateService;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Fetches currency exchange rates from the National Bank of Belarus (NBRb) public API.
 *
 * <p>The rate is cached in memory for {@link #CACHE_TTL} to avoid querying NBRb on every
 * ingestion run. A stale or missing cache results in an empty Optional; callers must
 * treat absent rates as "BYN unknown" and store {@code null} for {@code priceByn}.
 *
 * <p>NBRb API endpoint: {@code /exrates/rates/431?periodicity=0}
 * where 431 is the NBRb internal code for USD.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class NbrbCurrencyRateServiceImpl implements CurrencyRateService {

  private static final Duration CACHE_TTL = Duration.ofHours(6);
  private static final String RATE_FIELD = "Cur_OfficialRate";

  @Qualifier("nbrbRestClient")
  private final RestClient nbrbRestClient;
  private final ObjectMapper objectMapper;

  private final AtomicReference<BigDecimal> cachedRate = new AtomicReference<>();
  private volatile Instant cacheExpiresAt = Instant.EPOCH;

  @Override
  public Optional<BigDecimal> getUsdToByn() {
    if (Instant.now().isBefore(cacheExpiresAt)) {
      BigDecimal rate = cachedRate.get();
      if (rate != null) {
        return Optional.of(rate);
      }
    }
    return fetchAndCache();
  }

  private synchronized Optional<BigDecimal> fetchAndCache() {
    if (Instant.now().isBefore(cacheExpiresAt)) {
      BigDecimal rate = cachedRate.get();
      if (rate != null) {
        return Optional.of(rate);
      }
    }
    try {
      String body = nbrbRestClient.get()
          .uri("/exrates/rates/431?periodicity=0")
          .retrieve()
          .body(String.class);
      JsonNode node = objectMapper.readTree(body);
      BigDecimal rate = node.path(RATE_FIELD).decimalValue();
      if (rate == null || rate.compareTo(BigDecimal.ZERO) <= 0) {
        log.warn("NBRb returned unexpected rate value: {}", node);
        return Optional.empty();
      }
      cachedRate.set(rate);
      cacheExpiresAt = Instant.now().plus(CACHE_TTL);
      log.debug("NBRb USD/BYN rate fetched and cached: rate={}, expiresAt={}", rate, cacheExpiresAt);
      return Optional.of(rate);
    } catch (RestClientException e) {
      log.warn("Failed to fetch USD/BYN rate from NBRb: {}", e.getMessage());
      return Optional.empty();
    } catch (Exception e) {
      log.error("Unexpected error fetching USD/BYN rate from NBRb", e);
      return Optional.empty();
    }
  }
}
