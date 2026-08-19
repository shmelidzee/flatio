package com.flatio.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies {@link AdminSpaWebConfig} serves the admin SPA shell for {@code GET /admin} without a
 * trailing slash the same way it does for {@code GET /admin/} (issue #349) — previously the
 * exact, non-wildcard resource handler pattern match resolved to an empty resource path, which
 * blew up in {@code PathResourceResolver} and surfaced as a generic 500 instead of the SPA shell.
 *
 * <p>Boots a plain Spring MVC context ({@code AnnotationConfigWebApplicationContext} +
 * {@code @EnableWebMvc}) rather than {@code @SpringBootTest} — this exercises the real
 * {@code DispatcherServlet} resource-handling pipeline without going through
 * {@code SpringApplication.run()}, which would otherwise reinitialize the JVM-global Logback
 * {@code LoggerContext} and risk racing other Logback-sensitive tests (see
 * {@code LogbackProdProfileTest}) in the same suite.
 */
class AdminSpaWebConfigTest {

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    var context = new AnnotationConfigWebApplicationContext();
    context.register(WebMvcTestConfig.class);
    context.setServletContext(new MockServletContext());
    context.refresh();
    mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
  }

  @Test
  void should_return_spa_shell_when_requesting_admin_without_trailing_slash() throws Exception {
    // Given / When
    MvcResult result = mockMvc.perform(get("/admin"))
        .andExpect(status().isOk())
        .andReturn();

    // Then — the SPA shell, not the generic 500 error page previously returned for this path
    String body = result.getResponse().getContentAsString();
    assertThat(body).contains("id=\"root\"");
    assertThat(body).contains("Flatio Admin");
  }

  @Test
  void should_return_spa_shell_when_requesting_admin_with_trailing_slash() throws Exception {
    // Given / When
    MvcResult result = mockMvc.perform(get("/admin/"))
        .andExpect(status().isOk())
        .andReturn();

    // Then
    String body = result.getResponse().getContentAsString();
    assertThat(body).contains("id=\"root\"");
    assertThat(body).contains("Flatio Admin");
  }

  @Test
  void should_return_identical_shell_for_admin_with_and_without_trailing_slash() throws Exception {
    // Given / When
    String withoutSlash = mockMvc.perform(get("/admin")).andReturn().getResponse().getContentAsString();
    String withSlash = mockMvc.perform(get("/admin/")).andReturn().getResponse().getContentAsString();

    // Then
    assertThat(withoutSlash).isEqualTo(withSlash);
  }

  @Test
  void should_return_spa_shell_when_requesting_unknown_client_side_admin_route() throws Exception {
    // Given / When — a React Router route the server knows nothing about
    MvcResult result = mockMvc.perform(get("/admin/listings"))
        .andExpect(status().isOk())
        .andReturn();

    // Then — falls back to the SPA shell so client-side routing can take over
    assertThat(result.getResponse().getContentAsString()).contains("id=\"root\"");
  }

  @EnableWebMvc
  @Import(AdminSpaWebConfig.class)
  static class WebMvcTestConfig {
  }
}
