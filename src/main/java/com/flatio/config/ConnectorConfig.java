package com.flatio.config;

import com.flatio.connector.onliner.OnlinerProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers connector-related configuration properties.
 */
@Configuration
@EnableConfigurationProperties(OnlinerProperties.class)
public class ConnectorConfig {}
