package com.flatio.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers market-specific filter price configuration properties.
 */
@Configuration
@EnableConfigurationProperties(SellPriceFilterProperties.class)
public class FilterPriceConfig {
}
