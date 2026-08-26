package com.flatio.config;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Maximum number of blacklisted stop-words ({@code KEYWORD} entries) allowed per user tariff.
 *
 * <p>Only {@code KEYWORD} entries are tariff-limited; {@code LISTING} and {@code SOURCE} entries
 * are unlimited regardless of tariff. {@code USER} role uses {@code userMaxKeywords}. {@code PRO}
 * and {@code ADMIN} roles use {@code proMaxKeywords}, which is {@code null} by default — Spring
 * Boot's relaxed binding treats an empty environment variable value as no value, so leaving
 * {@code BLACKLIST_LIMIT_PRO_MAX_KEYWORDS} unset keeps the PRO/ADMIN tariff uncapped. Override via
 * {@code flatio.blacklist.limits.*} in {@code application.yml} or environment variables when
 * tariff limits change.
 *
 * @param userMaxKeywords max stop-words for the free USER tariff; must be positive
 * @param proMaxKeywords  max stop-words for the PRO/ADMIN tariff; {@code null} means unlimited
 */
@Validated
@ConfigurationProperties(prefix = "flatio.blacklist.limits")
public record BlacklistLimitProperties(
    @NotNull @Positive Integer userMaxKeywords,
    @Positive Integer proMaxKeywords
) {
}
