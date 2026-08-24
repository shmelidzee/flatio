package com.flatio.service;

import com.flatio.domain.user.AuthProvider;
import com.flatio.domain.user.User;
import com.flatio.domain.user.UserAuthProvider;
import com.flatio.repository.UserAuthProviderRepository;
import com.flatio.repository.UserRepository;
import java.time.Instant;
import java.util.Optional;

import com.flatio.service.impl.UserServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

  @Mock
  private UserRepository userRepository;

  @Mock
  private UserAuthProviderRepository userAuthProviderRepository;

  @InjectMocks
  private UserServiceImpl userService;

  @BeforeEach
  void setUp() {
    // findOrCreate delegates to findOrCreateTransactional via a self-injected AOP proxy
    // reference (see class Javadoc); pointing it at the same instance preserves real behaviour
    // in these unit tests, which do not exercise Spring's transactional proxying.
    ReflectionTestUtils.setField(userService, "self", userService);
  }

  // -------------------------------------------------------------------------
  // findOrCreate — new user
  // -------------------------------------------------------------------------

  @Test
  void should_create_user_and_auth_provider_when_telegram_id_not_found() {
    // Given
    when(userRepository.findByTelegramId("123456")).thenReturn(Optional.empty());
    when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

    // When
    userService.findOrCreate(123456L, "johndoe", "John");

    // Then
    ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
    verify(userRepository).save(userCaptor.capture());
    assertThat(userCaptor.getValue().getDisplayName()).isEqualTo("John");
    assertThat(userCaptor.getValue().isActive()).isTrue();
    assertThat(userCaptor.getValue().getLastSeen()).isNotNull();

    ArgumentCaptor<UserAuthProvider> providerCaptor = ArgumentCaptor.forClass(UserAuthProvider.class);
    verify(userAuthProviderRepository).save(providerCaptor.capture());
    assertThat(providerCaptor.getValue().getProvider()).isEqualTo(AuthProvider.TELEGRAM);
    assertThat(providerCaptor.getValue().getExternalId()).isEqualTo("123456");
    assertThat(providerCaptor.getValue().getTelegramUsername()).isEqualTo("johndoe");
  }

  @Test
  void should_use_username_as_display_name_when_first_name_is_null() {
    // Given
    when(userRepository.findByTelegramId("999")).thenReturn(Optional.empty());
    when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

    // When
    userService.findOrCreate(999L, "myhandle", null);

    // Then
    ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
    verify(userRepository).save(captor.capture());
    assertThat(captor.getValue().getDisplayName()).isEqualTo("@myhandle");
  }

  @Test
  void should_use_fallback_display_name_when_first_name_and_username_are_null() {
    // Given
    when(userRepository.findByTelegramId("777")).thenReturn(Optional.empty());
    when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

    // When
    userService.findOrCreate(777L, null, null);

    // Then
    ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
    verify(userRepository).save(captor.capture());
    assertThat(captor.getValue().getDisplayName()).isEqualTo("Telegram User");
  }

  @Test
  void should_not_create_auth_provider_when_user_already_exists() {
    // Given
    var existingUser = buildUser(1L);
    when(userRepository.findByTelegramId("123456")).thenReturn(Optional.of(existingUser));
    when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

    // When
    userService.findOrCreate(123456L, "johndoe", "John");

    // Then
    verify(userAuthProviderRepository, never()).save(any());
  }

  // -------------------------------------------------------------------------
  // findOrCreate — concurrent registration conflict (issue #375)
  // -------------------------------------------------------------------------

  @Test
  void should_return_existing_user_when_concurrent_registration_conflict_occurs() {
    // Given — first lookup finds nothing (race with another /start in flight), the auth-provider
    // insert then fails on the unique (provider, external_id) constraint the other call already
    // committed; a retried lookup now finds that row
    var racedUser = buildUser(99L);
    when(userRepository.findByTelegramId("555"))
        .thenReturn(Optional.empty())
        .thenReturn(Optional.of(racedUser));
    when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
    when(userAuthProviderRepository.save(any(UserAuthProvider.class)))
        .thenThrow(new DataIntegrityViolationException(
            "duplicate key value violates unique constraint \"uq_provider_external_id\""));

    // When
    var result = userService.findOrCreate(555L, "raced", "Race");

    // Then
    assertThat(result.getId()).isEqualTo(99L);
  }

  @Test
  void should_rethrow_when_conflict_retry_still_finds_no_user() {
    // Given — insert fails but the row is still absent on retry (should not happen in practice)
    when(userRepository.findByTelegramId("556")).thenReturn(Optional.empty());
    when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
    when(userAuthProviderRepository.save(any(UserAuthProvider.class)))
        .thenThrow(new DataIntegrityViolationException("constraint violation"));

    // When / Then
    assertThatThrownBy(() -> userService.findOrCreate(556L, "x", "X"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  // -------------------------------------------------------------------------
  // findOrCreate — returning user (last_seen update)
  // -------------------------------------------------------------------------

  @Test
  void should_update_last_seen_when_user_already_exists() {
    // Given
    var existingUser = buildUser(1L);
    Instant beforeCall = Instant.now().minusSeconds(60);
    existingUser.setLastSeen(beforeCall);
    when(userRepository.findByTelegramId("123456")).thenReturn(Optional.of(existingUser));
    when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

    // When
    userService.findOrCreate(123456L, "johndoe", "John");

    // Then
    ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
    verify(userRepository).save(captor.capture());
    assertThat(captor.getValue().getLastSeen()).isAfter(beforeCall);
  }

  @Test
  void should_return_existing_user_without_duplication() {
    // Given
    var existingUser = buildUser(42L);
    when(userRepository.findByTelegramId("42")).thenReturn(Optional.of(existingUser));
    when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

    // When
    var result = userService.findOrCreate(42L, null, "Anna");

    // Then
    assertThat(result.getId()).isEqualTo(42L);
    verify(userAuthProviderRepository, never()).save(any());
  }

  // -------------------------------------------------------------------------
  // findByTelegramId
  // -------------------------------------------------------------------------

  @Test
  void should_return_user_when_telegram_id_exists() {
    // Given
    var existingUser = buildUser(42L);
    when(userRepository.findByTelegramId("42")).thenReturn(Optional.of(existingUser));

    // When
    var result = userService.findByTelegramId(42L);

    // Then
    assertThat(result).isPresent();
    assertThat(result.get().getId()).isEqualTo(42L);
    verify(userRepository, never()).save(any());
  }

  @Test
  void should_return_empty_when_telegram_id_not_found() {
    // Given
    when(userRepository.findByTelegramId("404")).thenReturn(Optional.empty());

    // When
    var result = userService.findByTelegramId(404L);

    // Then
    assertThat(result).isEmpty();
    verify(userAuthProviderRepository, never()).save(any());
  }

  // -------------------------------------------------------------------------
  // helpers
  // -------------------------------------------------------------------------

  private User buildUser(Long id) {
    var user = new User();
    user.setId(id);
    user.setDisplayName("Test User");
    user.setActive(true);
    user.setLastSeen(Instant.now());
    return user;
  }
}
