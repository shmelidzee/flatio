package com.flatio.integration.kufar.config;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@code kufarAdDetailRestClient} does not automatically follow HTTP redirects
 * (issue #315 — redirect-based SSRF hardening). Uses two real local HTTP servers rather than a
 * mocked {@code RestClient}, since only an actual request through the configured client can prove
 * the underlying HTTP layer is configured not to chase a {@code Location} header.
 */
class KufarClientConfigTest {

  private final KufarClientConfig config = new KufarClientConfig();

  private HttpServer redirectSource;
  private HttpServer redirectTarget;

  @AfterEach
  void tearDown() {
    if (redirectSource != null) {
      redirectSource.stop(0);
    }
    if (redirectTarget != null) {
      redirectTarget.stop(0);
    }
  }

  @Test
  void should_not_follow_redirect_and_never_call_the_redirect_target() throws IOException {
    // Given — the redirect target would serve attacker-controlled content if ever reached
    var targetHitCount = new AtomicInteger(0);
    redirectTarget = startServer(exchange -> {
      targetHitCount.incrementAndGet();
      respond(exchange, 200, "should never be reached");
    });
    String targetUrl = "http://127.0.0.1:" + redirectTarget.getAddress().getPort() + "/malicious";

    redirectSource = startServer(exchange -> {
      exchange.getResponseHeaders().add("Location", targetUrl);
      respond(exchange, 302, "");
    });
    String sourceUrl = "http://127.0.0.1:" + redirectSource.getAddress().getPort() + "/ad";

    RestClient restClient = config.kufarAdDetailRestClient(RestClient.builder());

    // When
    var response = restClient.get().uri(sourceUrl).retrieve().toEntity(String.class);

    // Then — the 3xx response is returned as-is, the redirect target never receives a request
    assertThat(response.getStatusCode().value()).isEqualTo(302);
    assertThat(targetHitCount.get()).isZero();
  }

  // -------------------------------------------------------------------------
  // helpers
  // -------------------------------------------------------------------------

  private HttpServer startServer(com.sun.net.httpserver.HttpHandler handler) throws IOException {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", handler);
    server.start();
    return server;
  }

  private void respond(HttpExchange exchange, int status, String body) throws IOException {
    if (body.isEmpty()) {
      exchange.sendResponseHeaders(status, -1);
    } else {
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(status, bytes.length);
      exchange.getResponseBody().write(bytes);
    }
    exchange.close();
  }
}
