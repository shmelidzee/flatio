package com.flatio.repository;

import com.flatio.domain.user.AuthProvider;
import com.flatio.domain.user.User;
import com.flatio.domain.user.UserAuthProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
class UserRepositoryIT {

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
      .withDatabaseName("flatio_test");

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  @Autowired
  private UserRepository userRepository;

  @Autowired
  private UserAuthProviderRepository userAuthProviderRepository;

  @BeforeEach
  void setUp() {
    userAuthProviderRepository.deleteAll();
    userRepository.deleteAll();
  }

  // -------------------------------------------------------------------------
  // Helper
  // -------------------------------------------------------------------------

  private User buildUser(String displayName) {
    var user = new User();
    user.setDisplayName(displayName);
    user.setActive(true);
    return user;
  }

  private UserAuthProvider buildAuthProvider(User user, AuthProvider provider, String externalId) {
    var authProvider = new UserAuthProvider();
    authProvider.setUser(user);
    authProvider.setProvider(provider);
    authProvider.setExternalId(externalId);
    return authProvider;
  }

  // -------------------------------------------------------------------------
  // findByTelegramId
  // -------------------------------------------------------------------------

  @Test
  void should_find_user_when_telegram_provider_exists() {
    // Given
    var user = userRepository.save(buildUser("Pavel"));
    userAuthProviderRepository.save(buildAuthProvider(user, AuthProvider.TELEGRAM, "tg-123456"));

    // When
    var result = userRepository.findByTelegramId("tg-123456");

    // Then
    assertThat(result).isPresent();
    assertThat(result.get().getId()).isEqualTo(user.getId());
    assertThat(result.get().getDisplayName()).isEqualTo("Pavel");
  }

  @Test
  void should_return_empty_when_telegram_id_not_registered() {
    // Given — user exists but no TELEGRAM provider with this id
    userRepository.save(buildUser("Pavel"));

    // When
    var result = userRepository.findByTelegramId("tg-nonexistent");

    // Then
    assertThat(result).isEmpty();
  }

  @Test
  void should_return_empty_when_provider_is_not_telegram() {
    // Given — user has GOOGLE provider, not TELEGRAM
    var user = userRepository.save(buildUser("Pavel"));
    userAuthProviderRepository.save(buildAuthProvider(user, AuthProvider.GOOGLE, "google-sub-abc"));

    // When — searching by the same externalId but via TELEGRAM lookup
    var result = userRepository.findByTelegramId("google-sub-abc");

    // Then
    assertThat(result).isEmpty();
  }

  @Test
  void should_return_empty_when_no_users_exist() {
    // Given — empty database

    // When
    var result = userRepository.findByTelegramId("tg-123");

    // Then
    assertThat(result).isEmpty();
  }

  // -------------------------------------------------------------------------
  // UNIQUE constraint (provider, external_id)
  // -------------------------------------------------------------------------

  @Test
  void should_reject_duplicate_provider_external_id() {
    // Given — first auth provider saved successfully
    var user1 = userRepository.save(buildUser("User1"));
    var user2 = userRepository.save(buildUser("User2"));
    userAuthProviderRepository.saveAndFlush(
        buildAuthProvider(user1, AuthProvider.TELEGRAM, "tg-duplicate")
    );

    // When / Then — same (provider, externalId) for a different user violates UNIQUE
    assertThatThrownBy(() -> userAuthProviderRepository.saveAndFlush(
        buildAuthProvider(user2, AuthProvider.TELEGRAM, "tg-duplicate")
    )).isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void should_allow_same_external_id_for_different_providers() {
    // Given — same externalId string but different providers is allowed
    var user = userRepository.save(buildUser("Pavel"));
    userAuthProviderRepository.saveAndFlush(
        buildAuthProvider(user, AuthProvider.TELEGRAM, "shared-id")
    );

    // When / Then — different provider with same externalId should succeed
    var googleProvider = buildAuthProvider(user, AuthProvider.GOOGLE, "shared-id");
    var saved = userAuthProviderRepository.saveAndFlush(googleProvider);
    assertThat(saved.getId()).isNotNull();
  }

  // -------------------------------------------------------------------------
  // User entity basics
  // -------------------------------------------------------------------------

  @Test
  void should_persist_user_with_nullable_email() {
    // Given — email is not set (nullable)
    var user = buildUser("Pavel");

    // When
    var saved = userRepository.saveAndFlush(user);

    // Then
    assertThat(saved.getId()).isNotNull();
    assertThat(saved.getEmail()).isNull();
    assertThat(saved.isActive()).isTrue();
    assertThat(saved.getCreatedAt()).isNotNull();
    assertThat(saved.getUpdatedAt()).isNotNull();
  }

  @Test
  void should_persist_user_with_email() {
    // Given
    var user = buildUser("Pavel");
    user.setEmail("pavel@example.com");

    // When
    var saved = userRepository.saveAndFlush(user);

    // Then
    assertThat(saved.getEmail()).isEqualTo("pavel@example.com");
  }
}
