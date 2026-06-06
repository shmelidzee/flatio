package com.flatio.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import net.logstash.logback.encoder.LogstashEncoder;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class LogbackConfigurationTest {

  @Test
  void should_find_logback_spring_xml_on_classpath() {
    // Given / When
    var resource = getClass().getClassLoader().getResource("logback-spring.xml");

    // Then
    assertThat(resource).isNotNull();
  }

  @Test
  void should_produce_json_with_required_fields_when_logstash_encoder_used() {
    // Given
    var context = (LoggerContext) LoggerFactory.getILoggerFactory();

    var listAppender = new ListAppender<ILoggingEvent>();
    listAppender.setContext(context);
    listAppender.start();

    var testLogger = (Logger) LoggerFactory.getLogger("com.flatio.test.LogbackConfigurationTest");
    var originalLevel = testLogger.getLevel();
    testLogger.setLevel(Level.INFO);
    testLogger.addAppender(listAppender);

    // When
    testLogger.info("structured log message key=value");

    // Then
    assertThat(listAppender.list).hasSize(1);

    var encoder = new LogstashEncoder();
    encoder.setContext(context);
    encoder.start();

    var encoded = encoder.encode(listAppender.list.get(0));
    var json = new String(encoded, StandardCharsets.UTF_8);

    assertThat(json).contains("@timestamp");
    assertThat(json).contains("\"level\"");
    assertThat(json).contains("\"logger_name\"");
    assertThat(json).contains("\"thread_name\"");
    assertThat(json).contains("\"message\"");
    assertThat(json).contains("structured log message key=value");

    // Cleanup
    testLogger.detachAppender(listAppender);
    testLogger.setLevel(originalLevel);
  }
}
