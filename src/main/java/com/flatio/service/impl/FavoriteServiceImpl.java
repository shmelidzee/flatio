package com.flatio.service.impl;

import com.flatio.common.exception.FavoriteLimitExceededException;
import com.flatio.common.exception.FavoriteNotFoundException;
import com.flatio.common.exception.ListingNotFoundException;
import com.flatio.config.FavoriteLimitProperties;
import com.flatio.domain.favorite.Favorite;
import com.flatio.domain.listing.Listing;
import com.flatio.domain.user.User;
import com.flatio.domain.user.UserRole;
import com.flatio.repository.FavoriteRepository;
import com.flatio.repository.ListingRepository;
import com.flatio.repository.UserRepository;
import com.flatio.service.FavoriteService;
import com.flatio.web.dto.CreateFavoriteRequest;
import com.flatio.web.dto.FavoriteResponse;
import com.flatio.web.mapper.FavoriteMapper;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementation of {@link FavoriteService} enforcing per-user ownership and tariff limits.
 */
@Service
@Transactional(readOnly = true)
@Slf4j
@RequiredArgsConstructor
public class FavoriteServiceImpl implements FavoriteService {

  private final FavoriteRepository favoriteRepository;
  private final UserRepository userRepository;
  private final ListingRepository listingRepository;
  private final FavoriteMapper favoriteMapper;
  private final FavoriteLimitProperties limitProperties;

  @Override
  @Transactional
  public FavoriteResponse create(Long userId, CreateFavoriteRequest request) {
    User user = getUser(userId);
    Long listingId = request.listingId();

    Optional<Favorite> existing = favoriteRepository.findByUserAndListingId(user, listingId);
    if (existing.isPresent()) {
      log.debug("Favorite already exists, returning existing entry: userId={}, listingId={}", userId, listingId);
      return favoriteMapper.toResponse(existing.get());
    }

    enforceLimit(user);
    Listing listing = getListing(listingId);

    Favorite favorite = new Favorite();
    favorite.setUser(user);
    favorite.setListing(listing);
    favorite.setPriceAtAdd(listing.getPrice());

    Favorite saved = favoriteRepository.save(favorite);
    log.info("Favorite created: id={}, userId={}, listingId={}", saved.getId(), userId, listingId);
    return favoriteMapper.toResponse(saved);
  }

  @Override
  public Page<FavoriteResponse> findByUser(Long userId, Pageable pageable) {
    User user = getUser(userId);
    return favoriteRepository.findByUser(user, pageable).map(favoriteMapper::toResponse);
  }

  @Override
  @Transactional
  public void delete(Long userId, Long listingId) {
    User user = getUser(userId);
    Favorite favorite = favoriteRepository.findByUserAndListingId(user, listingId)
        .orElseThrow(() -> new FavoriteNotFoundException(listingId));
    favoriteRepository.delete(favorite);
    log.info("Favorite deleted: userId={}, listingId={}", userId, listingId);
  }

  private User getUser(Long userId) {
    return userRepository.findById(userId)
        .orElseThrow(() -> new IllegalStateException("Authenticated user not found: " + userId));
  }

  private Listing getListing(Long listingId) {
    return listingRepository.findById(listingId)
        .orElseThrow(() -> new ListingNotFoundException(listingId));
  }

  private void enforceLimit(User user) {
    Integer limit = resolveMaxFavorites(user);
    if (limit == null) {
      return;
    }
    long count = favoriteRepository.countByUser(user);
    if (count >= limit) {
      throw new FavoriteLimitExceededException(limit);
    }
  }

  private Integer resolveMaxFavorites(User user) {
    return user.getRole() == UserRole.USER ? limitProperties.userMaxFavorites() : limitProperties.proMaxFavorites();
  }
}
