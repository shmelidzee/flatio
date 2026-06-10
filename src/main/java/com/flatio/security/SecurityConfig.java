package com.flatio.security;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security configuration for the Flatio API.
 *
 * <p>The application uses stateless JWT-based authentication.
 * Sessions are disabled. The {@link JwtAuthenticationFilter} is registered before
 * the default username/password filter so that bearer tokens are resolved on every request.
 *
 * <p>Access rules:
 * <ul>
 *   <li>{@code /api/v1/admin/**} — requires {@code ADMIN} role</li>
 *   <li>{@code /api/v1/**} — requires any authenticated user</li>
 *   <li>Swagger UI and OpenAPI docs — publicly accessible</li>
 *   <li>Everything else — permitted without authentication</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties(JwtProperties.class)
@RequiredArgsConstructor
public class SecurityConfig {

  private final JwtAuthenticationFilter jwtAuthFilter;

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
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(auth -> auth
            .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
            .requestMatchers("/api/v1/**").authenticated()
            .requestMatchers("/swagger-ui/**", "/v3/api-docs/**", "/api-docs/**").permitAll()
            .anyRequest().permitAll()
        )
        .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
        .build();
  }
}
