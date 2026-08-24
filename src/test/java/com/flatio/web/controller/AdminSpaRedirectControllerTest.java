package com.flatio.web.controller;

import com.flatio.config.AdminSpaWebConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies {@link AdminSpaRedirectController} resolves the routing conflict with
 * {@code telegrambots-springboot-webhook-starter}'s auto-registered {@code POST /{botPath}}
 * handler (issue #361). Registers a stand-in for that handler alongside the real production
 * config to reproduce the exact conflict — {@code AdminSpaWebConfigTest} alone cannot catch this
 * bug, since it never includes a competing mapping on the same path.
 */
class AdminSpaRedirectControllerTest {

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
  void should_redirect_to_trailing_slash_when_requesting_admin_despite_webhook_path_conflict() throws Exception {
    // Given / When / Then
    mockMvc.perform(get("/admin"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/admin/"));
  }

  @Test
  void should_still_serve_spa_shell_when_requesting_admin_with_trailing_slash() throws Exception {
    // Given / When / Then
    mockMvc.perform(get("/admin/")).andExpect(status().isOk());
  }

  @Test
  void should_still_reach_webhook_handler_when_posting_to_admin_path() throws Exception {
    // Given / When / Then — POST /admin is not real traffic for either the SPA or the webhook,
    // but must keep resolving to the webhook mapping, not this redirect (GET-only)
    mockMvc.perform(post("/admin")).andExpect(status().isOk());
  }

  @EnableWebMvc
  @Import({AdminSpaWebConfig.class, AdminSpaRedirectController.class, StubWebhookController.class})
  static class WebMvcTestConfig {
  }

  /** Stand-in for {@code TelegramBotsSpringWebhookApplication}'s real {@code POST /{botPath}} mapping. */
  @RestController
  static class StubWebhookController {

    @PostMapping("/{botPath}")
    public String receiveUpdate(@PathVariable String botPath, @RequestBody(required = false) String body) {
      return "ok";
    }
  }
}
