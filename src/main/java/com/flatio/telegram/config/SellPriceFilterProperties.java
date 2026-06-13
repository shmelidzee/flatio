package com.flatio.telegram.config;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Market-specific price tier thresholds for SELL listings shown in the Telegram search filter.
 *
 * <p>Three boundary values divide the price axis into four named tiers:
 * <ul>
 *   <li>LOW     — below {@code lowMax}</li>
 *   <li>MEDIUM  — {@code lowMax} to {@code mediumMax}</li>
 *   <li>HIGH    — {@code mediumMax} to {@code highMax}</li>
 *   <li>PREMIUM — above {@code highMax}</li>
 * </ul>
 *
 * <p>Default values target the Belarusian market (BYN total price).
 * Override via {@code flatio.filter.price.sell.*} in {@code application.yml}
 * or environment variables when deploying to a different market.
 *
 * @param lowMax     upper boundary of the LOW tier
 * @param mediumMax  upper boundary of the MEDIUM tier
 * @param highMax    upper boundary of the HIGH tier (also the lower boundary of PREMIUM)
 */
@ConfigurationProperties(prefix = "flatio.filter.price.sell")
public record SellPriceFilterProperties(BigDecimal lowMax, BigDecimal mediumMax, BigDecimal highMax) {
}
