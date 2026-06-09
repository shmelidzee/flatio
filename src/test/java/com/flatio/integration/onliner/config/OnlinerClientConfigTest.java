package com.flatio.integration.onliner.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OnlinerClientConfigTest {

  @Mock
  private RestClient.Builder builder;

  private final OnlinerProperties properties = new OnlinerProperties(
      "https://ak.api.onliner.by",
      "ONLINER",
      "BY",
      "/search/apartments",
      50
  );

  private final OnlinerClientConfig config = new OnlinerClientConfig();

  @Test
  void should_configure_accept_application_json_header() {
    // Given
    when(builder.requestFactory(any())).thenReturn(builder);
    when(builder.baseUrl(anyString())).thenReturn(builder);
    when(builder.defaultHeader(anyString(), anyString())).thenReturn(builder);
    when(builder.build()).thenReturn(mock(RestClient.class));

    // When
    config.onlinerRestClient(builder, properties);

    // Then — Onliner API requires explicit Accept header; without it responds with 406
    verify(builder).defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE);
  }

  @Test
  void should_configure_user_agent_header() {
    // Given
    when(builder.requestFactory(any())).thenReturn(builder);
    when(builder.baseUrl(anyString())).thenReturn(builder);
    when(builder.defaultHeader(anyString(), anyString())).thenReturn(builder);
    when(builder.build()).thenReturn(mock(RestClient.class));

    // When
    config.onlinerRestClient(builder, properties);

    // Then — realistic User-Agent prevents bot-detection blocks
    verify(builder).defaultHeader(eq("User-Agent"), anyString());
  }

  @Test
  void should_use_base_url_from_properties() {
    // Given
    when(builder.requestFactory(any())).thenReturn(builder);
    when(builder.baseUrl(anyString())).thenReturn(builder);
    when(builder.defaultHeader(anyString(), anyString())).thenReturn(builder);
    when(builder.build()).thenReturn(mock(RestClient.class));

    // When
    config.onlinerRestClient(builder, properties);

    // Then — base URL must come from config, never hardcoded
    verify(builder).baseUrl("https://ak.api.onliner.by");
  }
}
