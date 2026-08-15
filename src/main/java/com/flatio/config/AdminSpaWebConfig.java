package com.flatio.config;

import java.io.IOException;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * Serves the admin SPA (built by the Gradle {@code copyFrontendToStatic} task into
 * {@code static/admin}) under {@code /admin} with client-side routing support.
 *
 * <p>Requests for an existing static asset (JS, CSS, {@code index.html}) are served as-is.
 * Any other {@code /admin/**} request — e.g. {@code /admin/listings}, a route only React Router
 * knows about — falls back to {@code index.html} so the SPA can take over rendering.
 */
@Configuration
public class AdminSpaWebConfig implements WebMvcConfigurer {

  private static final String ADMIN_STATIC_LOCATION = "classpath:/static/admin/";

  @Override
  public void addResourceHandlers(ResourceHandlerRegistry registry) {
    registry.addResourceHandler("/admin", "/admin/", "/admin/**")
        .addResourceLocations(ADMIN_STATIC_LOCATION)
        .resourceChain(true)
        .addResolver(new SpaFallbackResourceResolver());
  }

  /**
   * Resolves an existing static file if present, otherwise falls back to {@code index.html}
   * so client-side routes resolve to the SPA shell instead of a 404.
   */
  private static final class SpaFallbackResourceResolver extends PathResourceResolver {

    @Override
    protected Resource getResource(String resourcePath, Resource location) throws IOException {
      // Delegates to the parent resolver first so its checkResource() traversal guard still
      // runs — only the "not found" case is special-cased here for the SPA fallback.
      var resource = super.getResource(resourcePath, location);
      return resource != null ? resource : new ClassPathResource("static/admin/index.html");
    }
  }
}
