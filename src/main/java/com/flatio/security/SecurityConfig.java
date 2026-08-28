package com.flatio.security;

import com.flatio.telegram.config.BotConfig;
import java.util.Arrays;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Spring Security configuration for the Flatio API.
 *
 * <p>The application uses stateless JWT-based authentication.
 * Sessions are disabled. The {@link JwtAuthenticationFilter} is registered before
 * the default username/password filter so that bearer tokens are resolved on every request.
 * {@link RateLimitFilter} runs immediately after it so that per-caller rate limits (by IP for
 * {@code /api/v1/auth/**}, by JWT subject otherwise) see the resolved authentication.
 *
 * <p>Access rules:
 * <ul>
 *   <li>{@code /api/v1/admin/**} — requires {@code ADMIN} role</li>
 *   <li>{@code /api/v1/auth/**} — publicly accessible; this is where JWT access tokens
 *       are issued, so it cannot itself require a token (see {@code AuthController})</li>
 *   <li>{@code /api/v1/**} — requires any authenticated user</li>
 *   <li>{@code /admin/**} — publicly accessible; this is the static admin SPA shell
 *       ({@code index.html}, JS, CSS — see {@code AdminSpaWebConfig}), not the API. The SPA
 *       itself calls the JWT-protected {@code /api/v1/admin/**} endpoints once loaded</li>
 *   <li>Swagger UI, OpenAPI docs, and Actuator health/info — publicly accessible</li>
 *   <li>{@code /actuator/prometheus} — requires {@code ADMIN} role, same as
 *       {@code /api/v1/admin/**} (issue #417); a scraper must present an admin-role bearer JWT</li>
 *   <li>{@code POST /<bot-token>} — publicly accessible; this is the Telegram webhook
 *       endpoint (see {@code TelegramWebhookConfig}). Telegram cannot send a JWT, and the
 *       token-as-path is itself the access control for this endpoint</li>
 *   <li>Everything else — denied (fail-closed, HTTP 403)</li>
 * </ul>
 *
 * <p>CORS is configured via {@code flatio.cors.allowed-origins}
 * (environment variable: {@code CORS_ALLOWED_ORIGINS}).
 * Accepts a comma-separated list of allowed origins.
 * Defaults to {@code http://localhost:3000} for local development.
 * Wildcard origins ({@code *}) are never accepted.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties(JwtProperties.class)
@RequiredArgsConstructor
public class SecurityConfig {

  private final JwtAuthenticationFilter jwtAuthFilter;
  private final RateLimitFilter rateLimitFilter;
  private final BotConfig botConfig;

  @Value("${flatio.cors.allowed-origins:http://localhost:3000}")
  private String corsAllowedOrigins;

  /**
   * Configures the main security filter chain.
   *
   * @param http the {@link HttpSecurity} builder provided by Spring Security
   * @return the configured {@link SecurityFilterChain}
   * @throws Exception if the HTTP security configuration fails
   */
  @Bean
  public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    return http
        .csrf(AbstractHttpConfigurer::disable)
        .cors(cors -> cors.configurationSource(corsConfigurationSource()))
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(auth -> auth
            .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
            .requestMatchers("/api/v1/auth/**").permitAll()
            .requestMatchers("/api/v1/**").authenticated()
            .requestMatchers("/admin", "/admin/", "/admin/**").permitAll()
            .requestMatchers(
                "/swagger-ui/**", "/swagger-ui.html",
                "/v3/api-docs/**", "/api-docs/**",
                "/actuator/health/**", "/actuator/info"
            ).permitAll()
            .requestMatchers("/actuator/prometheus").hasRole("ADMIN")
            .requestMatchers(HttpMethod.POST, "/" + botConfig.token()).permitAll()
            .anyRequest().denyAll()
        )
        .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
        .addFilterAfter(rateLimitFilter, JwtAuthenticationFilter.class)
        .build();
  }

  /**
   * Provides the CORS policy for all API paths.
   *
   * <p>Allowed origins are read from {@code flatio.cors.allowed-origins}
   * (env: {@code CORS_ALLOWED_ORIGINS}). Multiple origins may be provided as a
   * comma-separated string. Credentials are permitted; wildcard origins are not.
   *
   * @return the configured {@link CorsConfigurationSource}
   */
  @Bean
  CorsConfigurationSource corsConfigurationSource() {
    var config = new CorsConfiguration();
    config.setAllowedOrigins(Arrays.stream(corsAllowedOrigins.split(","))
        .map(String::trim)
        .toList());
    config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
    config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
    config.setAllowCredentials(true);
    config.setMaxAge(3600L);
    var source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", config);
    return source;
  }
}
