package com.flatio.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers favorites tariff limit configuration properties.
 */
@Configuration
@EnableConfigurationProperties(FavoriteLimitProperties.class)
public class FavoriteConfig {
}
