package com.flatio.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers blacklist tariff limit configuration properties.
 */
@Configuration
@EnableConfigurationProperties(BlacklistLimitProperties.class)
public class BlacklistConfig {
}
