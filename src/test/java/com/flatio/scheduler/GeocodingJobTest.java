package com.flatio.scheduler;

import com.flatio.domain.listing.Listing;
import com.flatio.integration.nominatim.client.NominatimClient;
import com.flatio.repository.ListingRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GeocodingJobTest {

  @Mock
  private ListingRepository listingRepository;

  @Mock
  private NominatimClient nominatimClient;

  @InjectMocks
  private GeocodingJob geocodingJob;

  @BeforeEach
  void setUp() {
    ReflectionTestUtils.setField(geocodingJob, "batchSize", 50);
  }

  // -------------------------------------------------------------------------
  // Happy path
  // -------------------------------------------------------------------------

  @Test
  void should_set_city_when_nominatim_resolves_coordinates() {
    // Given
    var listing = buildListingWithCoordinates(1L, "53.9", "27.5");
    when(listingRepository.findNeedingGeocoding(any())).thenReturn(List.of(listing));
    when(nominatimClient.reverseGeocode(any(), any())).thenReturn(Optional.of("Минск"));

    // When
    geocodingJob.runGeocoding();

    // Then
    assertThat(listing.getCity()).isEqualTo("Минск");
    verify(listingRepository).findNeedingGeocoding(any());
    verify(nominatimClient).reverseGeocode(listing.getLatitude(), listing.getLongitude());
  }

  @Test
  void should_resolve_multiple_listings_in_batch() {
    // Given
    var listing1 = buildListingWithCoordinates(1L, "53.9", "27.5");
    var listing2 = buildListingWithCoordinates(2L, "54.0", "27.6");
    when(listingRepository.findNeedingGeocoding(any())).thenReturn(List.of(listing1, listing2));
    when(nominatimClient.reverseGeocode(listing1.getLatitude(), listing1.getLongitude()))
        .thenReturn(Optional.of("Минск"));
    when(nominatimClient.reverseGeocode(listing2.getLatitude(), listing2.getLongitude()))
        .thenReturn(Optional.of("д. Заречье, Минский район"));

    // When
    geocodingJob.runGeocoding();

    // Then
    assertThat(listing1.getCity()).isEqualTo("Минск");
    assertThat(listing2.getCity()).isEqualTo("д. Заречье, Минский район");
  }

  // -------------------------------------------------------------------------
  // Empty / no-op cases
  // -------------------------------------------------------------------------

  @Test
  void should_not_update_city_when_nominatim_returns_empty() {
    // Given
    var listing = buildListingWithCoordinates(2L, "55.0", "30.0");
    when(listingRepository.findNeedingGeocoding(any())).thenReturn(List.of(listing));
    when(nominatimClient.reverseGeocode(any(), any())).thenReturn(Optional.empty());

    // When
    geocodingJob.runGeocoding();

    // Then — city remains null; listing not updated with empty value
    assertThat(listing.getCity()).isNull();
  }

  @Test
  void should_do_nothing_when_no_listings_need_geocoding() {
    // Given
    when(listingRepository.findNeedingGeocoding(any())).thenReturn(List.of());

    // When
    geocodingJob.runGeocoding();

    // Then — Nominatim never called
    verify(nominatimClient, never()).reverseGeocode(any(), any());
  }

  // -------------------------------------------------------------------------
  // Resilience — Nominatim unavailable
  // -------------------------------------------------------------------------

  @Test
  void should_skip_iteration_without_exception_when_nominatim_is_unavailable() {
    // Given
    var listing = buildListingWithCoordinates(3L, "53.9", "27.5");
    when(listingRepository.findNeedingGeocoding(any())).thenReturn(List.of(listing));
    when(nominatimClient.reverseGeocode(any(), any()))
        .thenThrow(new RuntimeException("Connection refused"));

    // When / Then — exception is swallowed; city not set
    assertThatNoException().isThrownBy(() -> geocodingJob.runGeocoding());
    assertThat(listing.getCity()).isNull();
  }

  @Test
  void should_skip_iteration_without_exception_when_repository_throws() {
    // Given — e.g. DB unavailable
    when(listingRepository.findNeedingGeocoding(any()))
        .thenThrow(new RuntimeException("DB connection lost"));

    // When / Then
    assertThatNoException().isThrownBy(() -> geocodingJob.runGeocoding());
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  private Listing buildListingWithCoordinates(Long id, String lat, String lon) {
    var listing = new Listing();
    listing.setId(id);
    listing.setLatitude(new BigDecimal(lat));
    listing.setLongitude(new BigDecimal(lon));
    return listing;
  }
}
