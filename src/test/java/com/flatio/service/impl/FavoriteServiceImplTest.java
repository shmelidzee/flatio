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
import com.flatio.web.dto.CreateFavoriteRequest;
import com.flatio.web.dto.FavoriteResponse;
import com.flatio.web.mapper.FavoriteMapper;
import java.math.BigDecimal;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FavoriteServiceImplTest {

  @Mock
  private FavoriteRepository favoriteRepository;

  @Mock
  private UserRepository userRepository;

  @Mock
  private ListingRepository listingRepository;

  @Mock
  private FavoriteMapper favoriteMapper;

  private FavoriteServiceImpl favoriteService;

  @BeforeEach
  void setUp() {
    var limitProperties = new FavoriteLimitProperties(100, null);
    favoriteService = new FavoriteServiceImpl(
        favoriteRepository, userRepository, listingRepository, favoriteMapper, limitProperties
    );
  }

  // -------------------------------------------------------------------------
  // create
  // -------------------------------------------------------------------------

  @Test
  void should_create_favorite_when_listing_not_already_favorited() {
    // Given
    var user = buildUser(1L, UserRole.USER);
    var listing = buildListing(42L, BigDecimal.valueOf(75_000));
    var request = new CreateFavoriteRequest(42L);
    var response = mock(FavoriteResponse.class);
    var savedCaptor = ArgumentCaptor.forClass(Favorite.class);
    when(userRepository.findById(1L)).thenReturn(Optional.of(user));
    when(favoriteRepository.findByUserAndListingId(user, 42L)).thenReturn(Optional.empty());
    when(favoriteRepository.countByUser(user)).thenReturn(0L);
    when(listingRepository.findById(42L)).thenReturn(Optional.of(listing));
    when(favoriteRepository.save(savedCaptor.capture())).thenAnswer(invocation -> invocation.getArgument(0));
    when(favoriteMapper.toResponse(any(Favorite.class))).thenReturn(response);

    // When
    var result = favoriteService.create(1L, request);

    // Then
    assertThat(result).isSameAs(response);
    assertThat(savedCaptor.getValue().getUser()).isEqualTo(user);
    assertThat(savedCaptor.getValue().getListing()).isEqualTo(listing);
    assertThat(savedCaptor.getValue().getPriceAtAdd()).isEqualByComparingTo(BigDecimal.valueOf(75_000));
  }

  @Test
  void should_set_null_price_at_add_when_listing_price_is_negotiable() {
    // Given — listing has no disclosed price
    var user = buildUser(1L, UserRole.USER);
    var listing = buildListing(42L, null);
    var request = new CreateFavoriteRequest(42L);
    var savedCaptor = ArgumentCaptor.forClass(Favorite.class);
    when(userRepository.findById(1L)).thenReturn(Optional.of(user));
    when(favoriteRepository.findByUserAndListingId(user, 42L)).thenReturn(Optional.empty());
    when(favoriteRepository.countByUser(user)).thenReturn(0L);
    when(listingRepository.findById(42L)).thenReturn(Optional.of(listing));
    when(favoriteRepository.save(savedCaptor.capture())).thenAnswer(invocation -> invocation.getArgument(0));
    when(favoriteMapper.toResponse(any(Favorite.class))).thenReturn(mock(FavoriteResponse.class));

    // When
    favoriteService.create(1L, request);

    // Then
    assertThat(savedCaptor.getValue().getPriceAtAdd()).isNull();
  }

  @Test
  void should_return_existing_favorite_when_already_favorited() {
    // Given — idempotent re-add, must not consume the tariff limit or hit listing lookup
    var user = buildUser(1L, UserRole.USER);
    var existing = new Favorite();
    var request = new CreateFavoriteRequest(42L);
    var response = mock(FavoriteResponse.class);
    when(userRepository.findById(1L)).thenReturn(Optional.of(user));
    when(favoriteRepository.findByUserAndListingId(user, 42L)).thenReturn(Optional.of(existing));
    when(favoriteMapper.toResponse(existing)).thenReturn(response);

    // When
    var result = favoriteService.create(1L, request);

    // Then
    assertThat(result).isSameAs(response);
    verify(favoriteRepository, never()).countByUser(any());
    verify(listingRepository, never()).findById(any());
    verify(favoriteRepository, never()).save(any());
  }

  @Test
  void should_throw_when_user_role_limit_reached_on_create() {
    // Given — USER tariff limit is 100, already at 100 favorites
    var user = buildUser(1L, UserRole.USER);
    var request = new CreateFavoriteRequest(42L);
    when(userRepository.findById(1L)).thenReturn(Optional.of(user));
    when(favoriteRepository.findByUserAndListingId(user, 42L)).thenReturn(Optional.empty());
    when(favoriteRepository.countByUser(user)).thenReturn(100L);

    // When / Then
    assertThatThrownBy(() -> favoriteService.create(1L, request))
        .isInstanceOf(FavoriteLimitExceededException.class)
        .hasMessageContaining("100");
    verify(listingRepository, never()).findById(any());
    verify(favoriteRepository, never()).save(any());
  }

  @Test
  void should_not_enforce_limit_when_user_role_is_pro_and_no_pro_limit_configured() {
    // Given — PRO/ADMIN tariff has no configured limit (proMaxFavorites = null means unlimited)
    var user = buildUser(1L, UserRole.PRO);
    var listing = buildListing(42L, BigDecimal.valueOf(75_000));
    var request = new CreateFavoriteRequest(42L);
    when(userRepository.findById(1L)).thenReturn(Optional.of(user));
    when(favoriteRepository.findByUserAndListingId(user, 42L)).thenReturn(Optional.empty());
    when(listingRepository.findById(42L)).thenReturn(Optional.of(listing));
    when(favoriteRepository.save(any(Favorite.class))).thenAnswer(invocation -> invocation.getArgument(0));
    when(favoriteMapper.toResponse(any(Favorite.class))).thenReturn(mock(FavoriteResponse.class));

    // When
    favoriteService.create(1L, request);

    // Then
    verify(favoriteRepository, never()).countByUser(any());
  }

  @Test
  void should_throw_when_pro_limit_configured_and_reached() {
    // Given — PRO tariff has an explicit configured limit
    favoriteService = new FavoriteServiceImpl(
        favoriteRepository, userRepository, listingRepository, favoriteMapper,
        new FavoriteLimitProperties(100, 5)
    );
    var user = buildUser(1L, UserRole.PRO);
    var request = new CreateFavoriteRequest(42L);
    when(userRepository.findById(1L)).thenReturn(Optional.of(user));
    when(favoriteRepository.findByUserAndListingId(user, 42L)).thenReturn(Optional.empty());
    when(favoriteRepository.countByUser(user)).thenReturn(5L);

    // When / Then
    assertThatThrownBy(() -> favoriteService.create(1L, request))
        .isInstanceOf(FavoriteLimitExceededException.class)
        .hasMessageContaining("5");
  }

  @Test
  void should_throw_when_listing_not_found_on_create() {
    // Given
    var user = buildUser(1L, UserRole.USER);
    var request = new CreateFavoriteRequest(99L);
    when(userRepository.findById(1L)).thenReturn(Optional.of(user));
    when(favoriteRepository.findByUserAndListingId(user, 99L)).thenReturn(Optional.empty());
    when(favoriteRepository.countByUser(user)).thenReturn(0L);
    when(listingRepository.findById(99L)).thenReturn(Optional.empty());

    // When / Then
    assertThatThrownBy(() -> favoriteService.create(1L, request))
        .isInstanceOf(ListingNotFoundException.class)
        .hasMessageContaining("99");
    verify(favoriteRepository, never()).save(any());
  }

  // -------------------------------------------------------------------------
  // findByUser
  // -------------------------------------------------------------------------

  @Test
  void should_return_page_of_favorites_for_user() {
    // Given
    var user = buildUser(1L, UserRole.USER);
    var favorite = new Favorite();
    var response = mock(FavoriteResponse.class);
    var pageable = PageRequest.of(0, 20);
    Page<Favorite> page = new PageImpl<>(List.of(favorite), pageable, 1);
    when(userRepository.findById(1L)).thenReturn(Optional.of(user));
    when(favoriteRepository.findByUser(user, pageable)).thenReturn(page);
    when(favoriteMapper.toResponse(favorite)).thenReturn(response);

    // When
    var result = favoriteService.findByUser(1L, pageable);

    // Then
    assertThat(result.getContent()).containsExactly(response);
    verify(favoriteRepository).findByUser(user, pageable);
  }

  // -------------------------------------------------------------------------
  // delete
  // -------------------------------------------------------------------------

  @Test
  void should_delete_favorite_when_owned_by_user() {
    // Given
    var user = buildUser(1L, UserRole.USER);
    var favorite = new Favorite();
    when(userRepository.findById(1L)).thenReturn(Optional.of(user));
    when(favoriteRepository.findByUserAndListingId(user, 42L)).thenReturn(Optional.of(favorite));

    // When
    favoriteService.delete(1L, 42L);

    // Then
    verify(favoriteRepository).delete(favorite);
  }

  @Test
  void should_throw_not_found_when_deleting_favorite_not_owned_or_missing() {
    // Given — not present for this user, whether never favorited or owned by someone else
    var user = buildUser(1L, UserRole.USER);
    when(userRepository.findById(1L)).thenReturn(Optional.of(user));
    when(favoriteRepository.findByUserAndListingId(user, 99L)).thenReturn(Optional.empty());

    // When / Then
    assertThatThrownBy(() -> favoriteService.delete(1L, 99L))
        .isInstanceOf(FavoriteNotFoundException.class)
        .hasMessageContaining("99");
    verify(favoriteRepository, never()).delete(any(Favorite.class));
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

  private Listing buildListing(Long id, BigDecimal price) {
    var listing = new Listing();
    listing.setId(id);
    listing.setPrice(price);
    return listing;
  }
}
