package com.flatio.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers Telegram bot configuration properties.
 */
@Configuration
@EnableConfigurationProperties(BotConfig.class)
public class BotConfiguration {
}
