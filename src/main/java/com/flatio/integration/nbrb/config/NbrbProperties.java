package com.flatio.integration.nbrb.config;

import jakarta.validation.constraints.NotEmpty;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration for the National Bank of Belarus (NBRb) exchange rate connector.
 *
 * <p>{@code currencyIds} maps an ISO currency code (e.g. {@code "USD"}) to NBRb's internal
 * numeric currency identifier, used in the {@code /exrates/rates/{id}} endpoint path. Only
 * currencies present in this map are synced — never hard-coded in the connector itself, per
 * project convention. Add an entry (and verify the numeric ID against NBRb's own currency
 * directory before enabling it) to sync an additional currency.
 *
 * @param baseUrl     NBRb API base URL
 * @param currencyIds ISO currency code → NBRb numeric currency ID, must not be empty
 */
@Validated
@ConfigurationProperties(prefix = "flatio.nbrb")
public record NbrbProperties(
    String baseUrl,
    @NotEmpty Map<String, Integer> currencyIds
) {
}
