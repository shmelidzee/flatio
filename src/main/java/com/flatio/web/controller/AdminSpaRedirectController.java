package com.flatio.web.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.servlet.view.RedirectView;

/**
 * Redirects {@code GET /admin} (no trailing slash) to {@code /admin/}.
 *
 * <p>Without this literal, method-specific mapping, {@code GET /admin} was intercepted by
 * {@code telegrambots-springboot-webhook-starter}'s auto-registered {@code POST /{botPath}}
 * handler (issue #361, root-caused by reading that library's source directly — the earlier
 * assumption that a misconfigured {@code TELEGRAM_BOT_TOKEN} value collided with the literal path
 * {@code /admin} was wrong and has been retracted in the issue). Spring's single, app-wide
 * {@code RequestMappingHandlerMapping} indexes every {@code @RequestMapping} across the whole
 * context, found that generic single-path-segment pattern matches {@code /admin}, and — since
 * only {@code POST} is registered for it — rejected the {@code GET} request with
 * {@code HttpRequestMethodNotSupportedException} before {@link com.flatio.config.AdminSpaWebConfig}'s
 * resource handler, registered on a separate, lower-priority {@code HandlerMapping}, ever got a
 * chance to run: {@code DispatcherServlet} stops at the first {@code HandlerMapping} that either
 * returns a handler or throws, and never reaches the rest of the list.
 *
 * <p>A literal, GET-only mapping on this same {@code RequestMappingHandlerMapping} sidesteps the
 * conflict entirely: for a {@code GET} request the library's {@code POST}-only mapping never
 * becomes a candidate match at all (method mismatch), so this becomes the sole match and Spring
 * never falls into the "path matched, method didn't" branch that threw before. {@code POST /admin}
 * (not a real traffic pattern for either the SPA or the webhook) is unaffected — it still reaches
 * the webhook handler exactly as before.
 */
@Controller
public class AdminSpaRedirectController {

  @GetMapping("/admin")
  public RedirectView redirectAdminWithoutTrailingSlash() {
    return new RedirectView("/admin/");
  }
}
