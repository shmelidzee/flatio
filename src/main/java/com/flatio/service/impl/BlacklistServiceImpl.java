package com.flatio.service.impl;

import com.flatio.common.exception.BlacklistEntryNotFoundException;
import com.flatio.common.exception.BlacklistInvalidValueException;
import com.flatio.common.exception.BlacklistKeywordLimitExceededException;
import com.flatio.common.exception.ListingNotFoundException;
import com.flatio.common.exception.SourceNotFoundException;
import com.flatio.common.util.ControlCharacterUtils;
import com.flatio.config.BlacklistLimitProperties;
import com.flatio.domain.blacklist.BlacklistEntry;
import com.flatio.domain.blacklist.BlacklistEntryType;
import com.flatio.domain.user.User;
import com.flatio.domain.user.UserRole;
import com.flatio.repository.BlacklistEntryRepository;
import com.flatio.repository.ListingRepository;
import com.flatio.repository.SourceRepository;
import com.flatio.repository.UserRepository;
import com.flatio.service.BlacklistService;
import com.flatio.web.dto.BlacklistEntryResponse;
import com.flatio.web.dto.CreateBlacklistEntryRequest;
import com.flatio.web.mapper.BlacklistEntryMapper;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementation of {@link BlacklistService} enforcing per-user ownership, type-dependent value
 * format, and tariff limits on stop-words.
 */
@Service
@Transactional(readOnly = true)
@Slf4j
@RequiredArgsConstructor
public class BlacklistServiceImpl implements BlacklistService {

  private static final int MAX_KEYWORD_LENGTH = 100;

  private final BlacklistEntryRepository blacklistEntryRepository;
  private final UserRepository userRepository;
  private final ListingRepository listingRepository;
  private final SourceRepository sourceRepository;
  private final BlacklistEntryMapper blacklistEntryMapper;
  private final BlacklistLimitProperties limitProperties;

  @Override
  @Transactional
  public BlacklistEntryResponse create(Long userId, CreateBlacklistEntryRequest request) {
    User user = getUser(userId);
    BlacklistEntryType type = request.type();
    String value = normalizeValue(type, request.value());

    Optional<BlacklistEntry> existing = blacklistEntryRepository.findByUserAndTypeAndValue(user, type, value);
    if (existing.isPresent()) {
      log.debug("Blacklist entry already exists, returning existing entry: userId={}, type={}, value={}",
          userId, type, value);
      return blacklistEntryMapper.toResponse(existing.get());
    }

    if (type == BlacklistEntryType.KEYWORD) {
      enforceKeywordLimit(user);
    }

    BlacklistEntry entry = new BlacklistEntry();
    entry.setUser(user);
    entry.setType(type);
    entry.setValue(value);

    BlacklistEntry saved = blacklistEntryRepository.save(entry);
    log.info("Blacklist entry created: id={}, userId={}, type={}, value={}", saved.getId(), userId, type, value);
    return blacklistEntryMapper.toResponse(saved);
  }

  @Override
  public Page<BlacklistEntryResponse> findByUser(Long userId, BlacklistEntryType type, Pageable pageable) {
    User user = getUser(userId);
    Page<BlacklistEntry> entries = type != null
        ? blacklistEntryRepository.findByUserAndType(user, type, pageable)
        : blacklistEntryRepository.findByUser(user, pageable);
    return entries.map(blacklistEntryMapper::toResponse);
  }

  @Override
  @Transactional
  public void delete(Long userId, Long id) {
    User user = getUser(userId);
    BlacklistEntry entry = blacklistEntryRepository.findByIdAndUser(id, user)
        .orElseThrow(() -> new BlacklistEntryNotFoundException(id));
    blacklistEntryRepository.delete(entry);
    log.info("Blacklist entry deleted: id={}, userId={}", id, userId);
  }

  private User getUser(Long userId) {
    return userRepository.findById(userId)
        .orElseThrow(() -> new IllegalStateException("Authenticated user not found: " + userId));
  }

  /**
   * Validates and canonicalizes a request's raw value against the format required by its type.
   *
   * <p>LISTING values must parse as an existing listing's numeric ID; the canonical decimal form
   * is stored so equivalent inputs (e.g. leading zeros) are deduplicated. SOURCE values must match
   * an existing source's {@code code} (the same identifier exposed to clients elsewhere in the
   * API), since {@code Source.id} is never exposed to clients. KEYWORD values must be a non-blank
   * string within the length limit, with control characters stripped (issue #433).
   */
  private String normalizeValue(BlacklistEntryType type, String rawValue) {
    String trimmed = rawValue.trim();
    return switch (type) {
      case LISTING -> normalizeListingValue(trimmed);
      case SOURCE -> normalizeSourceValue(trimmed);
      case KEYWORD -> normalizeKeywordValue(trimmed);
    };
  }

  private String normalizeListingValue(String trimmed) {
    Long listingId = parseId(BlacklistEntryType.LISTING, trimmed);
    if (!listingRepository.existsById(listingId)) {
      throw new ListingNotFoundException(listingId);
    }
    return listingId.toString();
  }

  private String normalizeSourceValue(String trimmed) {
    return sourceRepository.findByCode(trimmed)
        .orElseThrow(() -> new SourceNotFoundException(trimmed))
        .getCode();
  }

  private String normalizeKeywordValue(String trimmed) {
    // Strips control characters (CWE-117) before persisting/logging — a keyword containing them
    // is also meaningless as a literal substring to match against listing text (issue #433).
    String sanitized = ControlCharacterUtils.stripControlCharacters(trimmed).trim();
    if (sanitized.isEmpty() || sanitized.length() > MAX_KEYWORD_LENGTH) {
      throw new BlacklistInvalidValueException(BlacklistEntryType.KEYWORD, sanitized);
    }
    return sanitized;
  }

  private Long parseId(BlacklistEntryType type, String trimmed) {
    try {
      return Long.valueOf(trimmed);
    } catch (NumberFormatException ex) {
      throw new BlacklistInvalidValueException(type, trimmed);
    }
  }

  private void enforceKeywordLimit(User user) {
    Integer limit = resolveMaxKeywords(user);
    if (limit == null) {
      return;
    }
    long count = blacklistEntryRepository.countByUserAndType(user, BlacklistEntryType.KEYWORD);
    if (count >= limit) {
      throw new BlacklistKeywordLimitExceededException(limit);
    }
  }

  private Integer resolveMaxKeywords(User user) {
    return user.getRole() == UserRole.USER ? limitProperties.userMaxKeywords() : limitProperties.proMaxKeywords();
  }
}
