package com.flatio.config;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

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
 * @param lowMax     upper boundary of the LOW tier; must be positive
 * @param mediumMax  upper boundary of the MEDIUM tier; must be positive
 * @param highMax    upper boundary of the HIGH tier (lower boundary of PREMIUM); must be positive
 */
@Validated
@ConfigurationProperties(prefix = "flatio.filter.price.sell")
public record SellPriceFilterProperties(
    @NotNull @Positive BigDecimal lowMax,
    @NotNull @Positive BigDecimal mediumMax,
    @NotNull @Positive BigDecimal highMax
) {
}
