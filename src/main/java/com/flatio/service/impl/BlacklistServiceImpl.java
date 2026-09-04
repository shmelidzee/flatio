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
  public BlacklistEntryResponse findByIdForUser(Long userId, Long id) {
    User user = getUser(userId);
    BlacklistEntry entry = blacklistEntryRepository.findByIdAndUser(id, user)
        .orElseThrow(() -> new BlacklistEntryNotFoundException(id));
    return blacklistEntryMapper.toResponse(entry);
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
   * <p>Control characters are stripped before any type-specific validation (issue #433, CWE-117)
   * — every branch below can throw an exception whose message embeds the value, which {@code
   * GlobalExceptionHandler} logs as-is; sanitizing once here (rather than only in the KEYWORD
   * branch) keeps a raw {@code \r}/{@code \n} out of that log line regardless of which type
   * rejects the value.
   *
   * <p>LISTING values must parse as an existing listing's numeric ID; the canonical decimal form
   * is stored so equivalent inputs (e.g. leading zeros) are deduplicated. SOURCE values must match
   * an existing source's {@code code} (the same identifier exposed to clients elsewhere in the
   * API), since {@code Source.id} is never exposed to clients. KEYWORD values must be a non-blank
   * string within the length limit.
   */
  private String normalizeValue(BlacklistEntryType type, String rawValue) {
    String sanitized = ControlCharacterUtils.stripControlCharacters(rawValue).trim();
    return switch (type) {
      case LISTING -> normalizeListingValue(sanitized);
      case SOURCE -> normalizeSourceValue(sanitized);
      case KEYWORD -> normalizeKeywordValue(sanitized);
    };
  }

  private String normalizeListingValue(String sanitized) {
    Long listingId = parseId(BlacklistEntryType.LISTING, sanitized);
    if (!listingRepository.existsById(listingId)) {
      throw new ListingNotFoundException(listingId);
    }
    return listingId.toString();
  }

  private String normalizeSourceValue(String sanitized) {
    return sourceRepository.findByCode(sanitized)
        .orElseThrow(() -> new SourceNotFoundException(sanitized))
        .getCode();
  }

  private String normalizeKeywordValue(String sanitized) {
    if (sanitized.isEmpty() || sanitized.length() > MAX_KEYWORD_LENGTH) {
      throw new BlacklistInvalidValueException(BlacklistEntryType.KEYWORD, sanitized);
    }
    return sanitized;
  }

  private Long parseId(BlacklistEntryType type, String sanitized) {
    try {
      return Long.valueOf(sanitized);
    } catch (NumberFormatException ex) {
      throw new BlacklistInvalidValueException(type, sanitized);
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
