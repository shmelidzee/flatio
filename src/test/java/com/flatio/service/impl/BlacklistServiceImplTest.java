package com.flatio.service.impl;

import com.flatio.common.exception.BlacklistEntryNotFoundException;
import com.flatio.common.exception.BlacklistInvalidValueException;
import com.flatio.common.exception.BlacklistKeywordLimitExceededException;
import com.flatio.common.exception.ListingNotFoundException;
import com.flatio.common.exception.SourceNotFoundException;
import com.flatio.config.BlacklistLimitProperties;
import com.flatio.domain.blacklist.BlacklistEntry;
import com.flatio.domain.blacklist.BlacklistEntryType;
import com.flatio.domain.source.Source;
import com.flatio.domain.user.User;
import com.flatio.domain.user.UserRole;
import com.flatio.repository.BlacklistEntryRepository;
import com.flatio.repository.ListingRepository;
import com.flatio.repository.SourceRepository;
import com.flatio.repository.UserRepository;
import com.flatio.web.dto.BlacklistEntryResponse;
import com.flatio.web.dto.CreateBlacklistEntryRequest;
import com.flatio.web.mapper.BlacklistEntryMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BlacklistServiceImplTest {

  @Mock
  private BlacklistEntryRepository blacklistEntryRepository;

  @Mock
  private UserRepository userRepository;

  @Mock
  private ListingRepository listingRepository;

  @Mock
  private SourceRepository sourceRepository;

  @Mock
  private BlacklistEntryMapper blacklistEntryMapper;

  private BlacklistServiceImpl blacklistService;

  @BeforeEach
  void setUp() {
    var limitProperties = new BlacklistLimitProperties(20, null);
    blacklistService = new BlacklistServiceImpl(
        blacklistEntryRepository, userRepository, listingRepository, sourceRepository,
        blacklistEntryMapper, limitProperties
    );
  }

  // -------------------------------------------------------------------------
  // create — happy path
  // -------------------------------------------------------------------------

  @Test
  void should_create_entry_when_type_is_listing_and_listing_exists() {
    // Given
    var user = buildUser(1L, UserRole.USER);
    var request = new CreateBlacklistEntryRequest(BlacklistEntryType.LISTING, "42");
    var response = mock(BlacklistEntryResponse.class);
    var savedCaptor = ArgumentCaptor.forClass(BlacklistEntry.class);
    when(userRepository.findById(1L)).thenReturn(Optional.of(user));
    when(listingRepository.existsById(42L)).thenReturn(true);
    when(blacklistEntryRepository.findByUserAndTypeAndValue(user, BlacklistEntryType.LISTING, "42"))
        .thenReturn(Optional.empty());
    when(blacklistEntryRepository.save(savedCaptor.capture())).thenAnswer(invocation -> invocation.getArgument(0));
    when(blacklistEntryMapper.toResponse(any(BlacklistEntry.class))).thenReturn(response);

    // When
    var result = blacklistService.create(1L, request);

    // Then
    assertThat(result).isSameAs(response);
    assertThat(savedCaptor.getValue().getUser()).isEqualTo(user);
    assertThat(savedCaptor.getValue().getType()).isEqualTo(BlacklistEntryType.LISTING);
    assertThat(savedCaptor.getValue().getValue()).isEqualTo("42");
  }

  @Test
  void should_create_entry_when_type_is_source_and_source_exists() {
    // Given
    var user = buildUser(1L, UserRole.USER);
    var source = buildSource("onliner");
    var request = new CreateBlacklistEntryRequest(BlacklistEntryType.SOURCE, "onliner");
    var response = mock(BlacklistEntryResponse.class);
    var savedCaptor = ArgumentCaptor.forClass(BlacklistEntry.class);
    when(userRepository.findById(1L)).thenReturn(Optional.of(user));
    when(sourceRepository.findByCode("onliner")).thenReturn(Optional.of(source));
    when(blacklistEntryRepository.findByUserAndTypeAndValue(user, BlacklistEntryType.SOURCE, "onliner"))
        .thenReturn(Optional.empty());
    when(blacklistEntryRepository.save(savedCaptor.capture())).thenAnswer(invocation -> invocation.getArgument(0));
    when(blacklistEntryMapper.toResponse(any(BlacklistEntry.class))).thenReturn(response);

    // When
    var result = blacklistService.create(1L, request);

    // Then
    assertThat(result).isSameAs(response);
    assertThat(savedCaptor.getValue().getType()).isEqualTo(BlacklistEntryType.SOURCE);
    assertThat(savedCaptor.getValue().getValue()).isEqualTo("onliner");
  }

  @Test
  void should_create_entry_when_type_is_keyword_and_under_limit() {
    // Given
    var user = buildUser(1L, UserRole.USER);
    var request = new CreateBlacklistEntryRequest(BlacklistEntryType.KEYWORD, "novostroyka");
    var response = mock(BlacklistEntryResponse.class);
    var savedCaptor = ArgumentCaptor.forClass(BlacklistEntry.class);
    when(userRepository.findById(1L)).thenReturn(Optional.of(user));
    when(blacklistEntryRepository.findByUserAndTypeAndValue(user, BlacklistEntryType.KEYWORD, "novostroyka"))
        .thenReturn(Optional.empty());
    when(blacklistEntryRepository.countByUserAndType(user, BlacklistEntryType.KEYWORD)).thenReturn(0L);
    when(blacklistEntryRepository.save(savedCaptor.capture())).thenAnswer(invocation -> invocation.getArgument(0));
    when(blacklistEntryMapper.toResponse(any(BlacklistEntry.class))).thenReturn(response);

    // When
    var result = blacklistService.create(1L, request);

    // Then
    assertThat(result).isSameAs(response);
    assertThat(savedCaptor.getValue().getType()).isEqualTo(BlacklistEntryType.KEYWORD);
    assertThat(savedCaptor.getValue().getValue()).isEqualTo("novostroyka");
  }

  @Test
  void should_strip_control_characters_when_creating_keyword_entry() {
    // Given — issue #433: CR/LF embedded in a keyword must not reach storage/logs as-is
    var user = buildUser(1L, UserRole.USER);
    var request = new CreateBlacklistEntryRequest(BlacklistEntryType.KEYWORD, "novo\r\nstroyka");
    var response = mock(BlacklistEntryResponse.class);
    var savedCaptor = ArgumentCaptor.forClass(BlacklistEntry.class);
    when(userRepository.findById(1L)).thenReturn(Optional.of(user));
    when(blacklistEntryRepository.findByUserAndTypeAndValue(user, BlacklistEntryType.KEYWORD, "novostroyka"))
        .thenReturn(Optional.empty());
    when(blacklistEntryRepository.countByUserAndType(user, BlacklistEntryType.KEYWORD)).thenReturn(0L);
    when(blacklistEntryRepository.save(savedCaptor.capture())).thenAnswer(invocation -> invocation.getArgument(0));
    when(blacklistEntryMapper.toResponse(any(BlacklistEntry.class))).thenReturn(response);

    // When
    blacklistService.create(1L, request);

    // Then
    assertThat(savedCaptor.getValue().getValue()).isEqualTo("novostroyka");
  }

  // -------------------------------------------------------------------------
  // create — invalid value format
  // -------------------------------------------------------------------------

  @Test
  void should_throw_invalid_value_when_keyword_is_only_control_characters() {
    // Given — becomes empty once control characters are stripped
    var user = buildUser(1L, UserRole.USER);
    var request = new CreateBlacklistEntryRequest(BlacklistEntryType.KEYWORD, "\r\n\t");
    when(userRepository.findById(1L)).thenReturn(Optional.of(user));

    // When / Then
    assertThatThrownBy(() -> blacklistService.create(1L, request))
        .isInstanceOf(BlacklistInvalidValueException.class);
    verify(blacklistEntryRepository, never()).save(any());
  }

  @Test
  void should_throw_invalid_value_when_listing_value_is_not_numeric() {
    // Given
    var user = buildUser(1L, UserRole.USER);
    var request = new CreateBlacklistEntryRequest(BlacklistEntryType.LISTING, "abc");
    when(userRepository.findById(1L)).thenReturn(Optional.of(user));

    // When / Then
    assertThatThrownBy(() -> blacklistService.create(1L, request))
        .isInstanceOf(BlacklistInvalidValueException.class)
        .hasMessageContaining("abc");
    verify(listingRepository, never()).existsById(any());
    verify(blacklistEntryRepository, never()).save(any());
  }

  @Test
  void should_strip_control_characters_before_reporting_invalid_listing_value() {
    // Given — issue #433: the exception message must not carry raw \r/\n into the log line
    var user = buildUser(1L, UserRole.USER);
    var request = new CreateBlacklistEntryRequest(BlacklistEntryType.LISTING, "ab\r\nc");
    when(userRepository.findById(1L)).thenReturn(Optional.of(user));

    // When / Then
    assertThatThrownBy(() -> blacklistService.create(1L, request))
        .isInstanceOf(BlacklistInvalidValueException.class)
        .hasMessageContaining("abc")
        .hasMessageNotContaining("\r")
        .hasMessageNotContaining("\n");
  }

  @Test
  void should_throw_invalid_value_when_keyword_is_blank() {
    // Given — whitespace-only value trims to empty
    var user = buildUser(1L, UserRole.USER);
    var request = new CreateBlacklistEntryRequest(BlacklistEntryType.KEYWORD, "   ");
    when(userRepository.findById(1L)).thenReturn(Optional.of(user));

    // When / Then
    assertThatThrownBy(() -> blacklistService.create(1L, request))
        .isInstanceOf(BlacklistInvalidValueException.class);
    verify(blacklistEntryRepository, never()).save(any());
  }

  @Test
  void should_throw_invalid_value_when_keyword_exceeds_max_length() {
    // Given — 101 characters, one over the 100-char limit
    var user = buildUser(1L, UserRole.USER);
    var tooLong = "a".repeat(101);
    var request = new CreateBlacklistEntryRequest(BlacklistEntryType.KEYWORD, tooLong);
    when(userRepository.findById(1L)).thenReturn(Optional.of(user));

    // When / Then
    assertThatThrownBy(() -> blacklistService.create(1L, request))
        .isInstanceOf(BlacklistInvalidValueException.class);
    verify(blacklistEntryRepository, never()).save(any());
  }

  // -------------------------------------------------------------------------
  // create — referenced entity not found
  // -------------------------------------------------------------------------

  @Test
  void should_throw_listing_not_found_when_listing_id_does_not_exist() {
    // Given
    var user = buildUser(1L, UserRole.USER);
    var request = new CreateBlacklistEntryRequest(BlacklistEntryType.LISTING, "99");
    when(userRepository.findById(1L)).thenReturn(Optional.of(user));
    when(listingRepository.existsById(99L)).thenReturn(false);

    // When / Then
    assertThatThrownBy(() -> blacklistService.create(1L, request))
        .isInstanceOf(ListingNotFoundException.class)
        .hasMessageContaining("99");
    verify(blacklistEntryRepository, never()).save(any());
  }

  @Test
  void should_throw_source_not_found_when_source_code_does_not_exist() {
    // Given
    var user = buildUser(1L, UserRole.USER);
    var request = new CreateBlacklistEntryRequest(BlacklistEntryType.SOURCE, "unknown-source");
    when(userRepository.findById(1L)).thenReturn(Optional.of(user));
    when(sourceRepository.findByCode("unknown-source")).thenReturn(Optional.empty());

    // When / Then
    assertThatThrownBy(() -> blacklistService.create(1L, request))
        .isInstanceOf(SourceNotFoundException.class)
        .hasMessageContaining("unknown-source");
    verify(blacklistEntryRepository, never()).save(any());
  }

  @Test
  void should_strip_control_characters_before_reporting_source_not_found() {
    // Given — issue #433: the exception message must not carry raw \r/\n into the log line
    var user = buildUser(1L, UserRole.USER);
    var request = new CreateBlacklistEntryRequest(BlacklistEntryType.SOURCE, "unkno\r\nwn");
    when(userRepository.findById(1L)).thenReturn(Optional.of(user));
    when(sourceRepository.findByCode("unknown")).thenReturn(Optional.empty());

    // When / Then
    assertThatThrownBy(() -> blacklistService.create(1L, request))
        .isInstanceOf(SourceNotFoundException.class)
        .hasMessageContaining("unknown")
        .hasMessageNotContaining("\r")
        .hasMessageNotContaining("\n");
  }

  // -------------------------------------------------------------------------
  // create — tariff limit on KEYWORD entries
  // -------------------------------------------------------------------------

  @Test
  void should_throw_keyword_limit_exceeded_when_user_role_reached_limit() {
    // Given — USER tariff limit is 20, already at 20 keywords
    var user = buildUser(1L, UserRole.USER);
    var request = new CreateBlacklistEntryRequest(BlacklistEntryType.KEYWORD, "novostroyka");
    when(userRepository.findById(1L)).thenReturn(Optional.of(user));
    when(blacklistEntryRepository.findByUserAndTypeAndValue(user, BlacklistEntryType.KEYWORD, "novostroyka"))
        .thenReturn(Optional.empty());
    when(blacklistEntryRepository.countByUserAndType(user, BlacklistEntryType.KEYWORD)).thenReturn(20L);

    // When / Then
    assertThatThrownBy(() -> blacklistService.create(1L, request))
        .isInstanceOf(BlacklistKeywordLimitExceededException.class)
        .hasMessageContaining("20");
    verify(blacklistEntryRepository, never()).save(any());
  }

  @Test
  void should_not_enforce_keyword_limit_when_user_role_is_pro_and_no_pro_limit_configured() {
    // Given — PRO tariff has no configured limit (proMaxKeywords = null means unlimited)
    var user = buildUser(1L, UserRole.PRO);
    var request = new CreateBlacklistEntryRequest(BlacklistEntryType.KEYWORD, "novostroyka");
    when(userRepository.findById(1L)).thenReturn(Optional.of(user));
    when(blacklistEntryRepository.findByUserAndTypeAndValue(user, BlacklistEntryType.KEYWORD, "novostroyka"))
        .thenReturn(Optional.empty());
    when(blacklistEntryRepository.save(any(BlacklistEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));
    when(blacklistEntryMapper.toResponse(any(BlacklistEntry.class))).thenReturn(mock(BlacklistEntryResponse.class));

    // When
    blacklistService.create(1L, request);

    // Then
    verify(blacklistEntryRepository, never()).countByUserAndType(any(), any());
  }

  @Test
  void should_throw_keyword_limit_exceeded_when_pro_limit_configured_and_reached() {
    // Given — PRO tariff has an explicit configured limit
    blacklistService = new BlacklistServiceImpl(
        blacklistEntryRepository, userRepository, listingRepository, sourceRepository,
        blacklistEntryMapper, new BlacklistLimitProperties(20, 5)
    );
    var user = buildUser(1L, UserRole.PRO);
    var request = new CreateBlacklistEntryRequest(BlacklistEntryType.KEYWORD, "novostroyka");
    when(userRepository.findById(1L)).thenReturn(Optional.of(user));
    when(blacklistEntryRepository.findByUserAndTypeAndValue(user, BlacklistEntryType.KEYWORD, "novostroyka"))
        .thenReturn(Optional.empty());
    when(blacklistEntryRepository.countByUserAndType(user, BlacklistEntryType.KEYWORD)).thenReturn(5L);

    // When / Then
    assertThatThrownBy(() -> blacklistService.create(1L, request))
        .isInstanceOf(BlacklistKeywordLimitExceededException.class)
        .hasMessageContaining("5");
  }

  @Test
  void should_not_check_keyword_limit_when_type_is_listing_regardless_of_entry_count() {
    // Given — LISTING entries are never tariff-limited, however many the user already has
    var user = buildUser(1L, UserRole.USER);
    var request = new CreateBlacklistEntryRequest(BlacklistEntryType.LISTING, "42");
    when(userRepository.findById(1L)).thenReturn(Optional.of(user));
    when(listingRepository.existsById(42L)).thenReturn(true);
    when(blacklistEntryRepository.findByUserAndTypeAndValue(user, BlacklistEntryType.LISTING, "42"))
        .thenReturn(Optional.empty());
    when(blacklistEntryRepository.save(any(BlacklistEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));
    when(blacklistEntryMapper.toResponse(any(BlacklistEntry.class))).thenReturn(mock(BlacklistEntryResponse.class));

    // When
    blacklistService.create(1L, request);

    // Then
    verify(blacklistEntryRepository, never()).countByUserAndType(any(), eq(BlacklistEntryType.LISTING));
  }

  @Test
  void should_not_check_keyword_limit_when_type_is_source_regardless_of_entry_count() {
    // Given — SOURCE entries are never tariff-limited, however many the user already has
    var user = buildUser(1L, UserRole.USER);
    var source = buildSource("onliner");
    var request = new CreateBlacklistEntryRequest(BlacklistEntryType.SOURCE, "onliner");
    when(userRepository.findById(1L)).thenReturn(Optional.of(user));
    when(sourceRepository.findByCode("onliner")).thenReturn(Optional.of(source));
    when(blacklistEntryRepository.findByUserAndTypeAndValue(user, BlacklistEntryType.SOURCE, "onliner"))
        .thenReturn(Optional.empty());
    when(blacklistEntryRepository.save(any(BlacklistEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));
    when(blacklistEntryMapper.toResponse(any(BlacklistEntry.class))).thenReturn(mock(BlacklistEntryResponse.class));

    // When
    blacklistService.create(1L, request);

    // Then
    verify(blacklistEntryRepository, never()).countByUserAndType(any(), eq(BlacklistEntryType.SOURCE));
  }

  // -------------------------------------------------------------------------
  // create — idempotency
  // -------------------------------------------------------------------------

  @Test
  void should_return_existing_entry_when_already_blacklisted() {
    // Given — re-adding the same keyword must not consume the tariff limit or create a duplicate
    var user = buildUser(1L, UserRole.USER);
    var existing = new BlacklistEntry();
    var request = new CreateBlacklistEntryRequest(BlacklistEntryType.KEYWORD, "novostroyka");
    var response = mock(BlacklistEntryResponse.class);
    when(userRepository.findById(1L)).thenReturn(Optional.of(user));
    when(blacklistEntryRepository.findByUserAndTypeAndValue(user, BlacklistEntryType.KEYWORD, "novostroyka"))
        .thenReturn(Optional.of(existing));
    when(blacklistEntryMapper.toResponse(existing)).thenReturn(response);

    // When
    var result = blacklistService.create(1L, request);

    // Then
    assertThat(result).isSameAs(response);
    verify(blacklistEntryRepository, never()).countByUserAndType(any(), any());
    verify(blacklistEntryRepository, never()).save(any());
  }

  // -------------------------------------------------------------------------
  // findByUser
  // -------------------------------------------------------------------------

  @Test
  void should_return_page_of_own_entries_filtered_by_type() {
    // Given
    var user = buildUser(1L, UserRole.USER);
    var entry = new BlacklistEntry();
    var response = mock(BlacklistEntryResponse.class);
    var pageable = PageRequest.of(0, 20);
    Page<BlacklistEntry> page = new PageImpl<>(List.of(entry), pageable, 1);
    when(userRepository.findById(1L)).thenReturn(Optional.of(user));
    when(blacklistEntryRepository.findByUserAndType(user, BlacklistEntryType.KEYWORD, pageable)).thenReturn(page);
    when(blacklistEntryMapper.toResponse(entry)).thenReturn(response);

    // When
    var result = blacklistService.findByUser(1L, BlacklistEntryType.KEYWORD, pageable);

    // Then
    assertThat(result.getContent()).containsExactly(response);
    verify(blacklistEntryRepository).findByUserAndType(user, BlacklistEntryType.KEYWORD, pageable);
    verify(blacklistEntryRepository, never()).findByUser(any(), any());
  }

  @Test
  void should_return_page_of_own_entries_when_no_type_filter_given() {
    // Given
    var user = buildUser(1L, UserRole.USER);
    var entry = new BlacklistEntry();
    var response = mock(BlacklistEntryResponse.class);
    var pageable = PageRequest.of(0, 20);
    Page<BlacklistEntry> page = new PageImpl<>(List.of(entry), pageable, 1);
    when(userRepository.findById(1L)).thenReturn(Optional.of(user));
    when(blacklistEntryRepository.findByUser(user, pageable)).thenReturn(page);
    when(blacklistEntryMapper.toResponse(entry)).thenReturn(response);

    // When
    var result = blacklistService.findByUser(1L, null, pageable);

    // Then
    assertThat(result.getContent()).containsExactly(response);
    verify(blacklistEntryRepository).findByUser(user, pageable);
    verify(blacklistEntryRepository, never()).findByUserAndType(any(), any(), any());
  }

  // -------------------------------------------------------------------------
  // delete
  // -------------------------------------------------------------------------

  @Test
  void should_delete_entry_when_owned_by_user() {
    // Given
    var user = buildUser(1L, UserRole.USER);
    var entry = new BlacklistEntry();
    when(userRepository.findById(1L)).thenReturn(Optional.of(user));
    when(blacklistEntryRepository.findByIdAndUser(7L, user)).thenReturn(Optional.of(entry));

    // When
    blacklistService.delete(1L, 7L);

    // Then
    verify(blacklistEntryRepository).delete(entry);
  }

  @Test
  void should_throw_not_found_when_deleting_entry_not_owned_or_missing() {
    // Given — not present for this user, whether never created or owned by someone else
    var user = buildUser(1L, UserRole.USER);
    when(userRepository.findById(1L)).thenReturn(Optional.of(user));
    when(blacklistEntryRepository.findByIdAndUser(99L, user)).thenReturn(Optional.empty());

    // When / Then
    assertThatThrownBy(() -> blacklistService.delete(1L, 99L))
        .isInstanceOf(BlacklistEntryNotFoundException.class)
        .hasMessageContaining("99");
    verify(blacklistEntryRepository, never()).delete(any(BlacklistEntry.class));
  }

  // -------------------------------------------------------------------------
  // helpers
  // -------------------------------------------------------------------------

  private User buildUser(Long id, UserRole role) {
    var user = new User();
    user.setId(id);
    user.setDisplayName("Test User");
    user.setActive(true);
    user.setRole(role);
    return user;
  }

  private Source buildSource(String code) {
    var source = new Source();
    source.setCode(code);
    return source;
  }
}
