package com.flatio.service.notification;

import com.flatio.domain.user.AuthProvider;
import com.flatio.domain.user.User;
import com.flatio.domain.user.UserAuthProvider;
import com.flatio.repository.UserAuthProviderRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TelegramChatResolverTest {

  @Mock
  private UserAuthProviderRepository userAuthProviderRepository;

  @InjectMocks
  private TelegramChatResolver resolver;

  @Test
  void should_return_chat_id_when_telegram_account_linked() {
    // Given
    var user = new User();
    user.setId(1L);
    var authProvider = new UserAuthProvider();
    authProvider.setUser(user);
    authProvider.setProvider(AuthProvider.TELEGRAM);
    authProvider.setExternalId("111222333");
    when(userAuthProviderRepository.findByUserAndProvider(user, AuthProvider.TELEGRAM))
        .thenReturn(Optional.of(authProvider));

    // When
    var result = resolver.resolveChatId(user);

    // Then
    assertThat(result).contains("111222333");
  }

  @Test
  void should_return_empty_when_no_telegram_account_linked() {
    // Given
    var user = new User();
    user.setId(1L);
    when(userAuthProviderRepository.findByUserAndProvider(user, AuthProvider.TELEGRAM)).thenReturn(Optional.empty());

    // When
    var result = resolver.resolveChatId(user);

    // Then
    assertThat(result).isEmpty();
  }
}
