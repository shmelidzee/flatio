package com.flatio.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers subscription tariff limit configuration properties.
 */
@Configuration
@EnableConfigurationProperties(SubscriptionLimitProperties.class)
public class SubscriptionConfig {
}
