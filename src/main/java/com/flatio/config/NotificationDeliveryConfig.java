package com.flatio.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers notification delivery configuration properties (REALTIME, DIGEST, DAILY — issue #410).
 */
@Configuration
@EnableConfigurationProperties({
    NotificationDeliveryProperties.class,
    NotificationDigestProperties.class,
    NotificationDailyProperties.class
})
public class NotificationDeliveryConfig {
}
