package com.flatio.security;

import com.flatio.domain.user.User;
import com.flatio.domain.user.UserRole;
import com.flatio.repository.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserStatusCacheTest {

  @Mock
  private UserRepository userRepository;

  @InjectMocks
  private UserStatusCache userStatusCache;

  @Test
  void should_return_status_from_database_when_not_cached() {
    // Given
    var user = new User();
    user.setId(1L);
    user.setActive(true);
    user.setRole(UserRole.ADMIN);
    when(userRepository.findById(1L)).thenReturn(Optional.of(user));

    // When
    var result = userStatusCache.getStatus(1L);

    // Then
    assertThat(result).contains(new UserStatusCache.UserStatus(true, UserRole.ADMIN));
  }

  @Test
  void should_return_empty_when_user_does_not_exist() {
    // Given
    when(userRepository.findById(99L)).thenReturn(Optional.empty());

    // When
    var result = userStatusCache.getStatus(99L);

    // Then
    assertThat(result).isEmpty();
  }

  @Test
  void should_return_cached_status_without_querying_database_again() {
    // Given
    var user = new User();
    user.setId(1L);
    user.setActive(true);
    user.setRole(UserRole.USER);
    when(userRepository.findById(1L)).thenReturn(Optional.of(user));
    userStatusCache.getStatus(1L);

    // When — second lookup within TTL
    var result = userStatusCache.getStatus(1L);

    // Then — only one database read across both calls
    assertThat(result).contains(new UserStatusCache.UserStatus(true, UserRole.USER));
    verify(userRepository, times(1)).findById(1L);
  }

  @Test
  void should_query_database_again_when_evicted() {
    // Given
    var activeUser = new User();
    activeUser.setId(1L);
    activeUser.setActive(true);
    activeUser.setRole(UserRole.USER);
    var deactivatedUser = new User();
    deactivatedUser.setId(1L);
    deactivatedUser.setActive(false);
    deactivatedUser.setRole(UserRole.USER);
    when(userRepository.findById(1L)).thenReturn(Optional.of(activeUser), Optional.of(deactivatedUser));
    userStatusCache.getStatus(1L);

    // When — admin deactivates the user, evicting the stale cache entry
    userStatusCache.evict(1L);
    var result = userStatusCache.getStatus(1L);

    // Then — fresh read reflects the deactivation
    assertThat(result).contains(new UserStatusCache.UserStatus(false, UserRole.USER));
    verify(userRepository, times(2)).findById(1L);
  }

  @Test
  void should_not_fail_when_evicting_unknown_user() {
    // When / Then
    userStatusCache.evict(12345L);
  }
}
