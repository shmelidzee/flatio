package com.flatio.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import net.logstash.logback.encoder.LogstashEncoder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class LogbackConfigurationTest {

  private Logger testLogger;
  private ListAppender<ILoggingEvent> listAppender;
  private Level originalLevel;

  @BeforeEach
  void setUp() {
    var context = (LoggerContext) LoggerFactory.getILoggerFactory();
    listAppender = new ListAppender<>();
    listAppender.setContext(context);
    listAppender.start();

    testLogger = (Logger) LoggerFactory.getLogger("com.flatio.test.LogbackConfigurationTest");
    originalLevel = testLogger.getLevel();
    testLogger.setLevel(Level.INFO);
    testLogger.addAppender(listAppender);
  }

  @AfterEach
  void tearDown() {
    testLogger.detachAppender(listAppender);
    testLogger.setLevel(originalLevel);
    listAppender.stop();
  }

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

    var encoder = new LogstashEncoder();
    encoder.setContext(context);
    encoder.start();

    // When
    testLogger.info("structured log message key=value");
    assertThat(listAppender.list).hasSize(1);

    var encoded = encoder.encode(listAppender.list.get(0));
    var json = new String(encoded, StandardCharsets.UTF_8);

    // Then
    assertThat(json).contains("@timestamp");
    assertThat(json).contains("\"level\"");
    assertThat(json).contains("\"logger_name\"");
    assertThat(json).contains("\"thread_name\"");
    assertThat(json).contains("\"message\"");
    assertThat(json).contains("structured log message key=value");
  }
}
