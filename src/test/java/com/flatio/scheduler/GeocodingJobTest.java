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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
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
    ReflectionTestUtils.setField(geocodingJob, "maxAttempts", 5);
  }

  // -------------------------------------------------------------------------
  // Happy path
  // -------------------------------------------------------------------------

  @Test
  void should_set_city_when_nominatim_resolves_coordinates() {
    // Given
    var listing = buildListingWithCoordinates(1L, "53.9", "27.5");
    when(listingRepository.findNeedingGeocoding(anyInt(), any())).thenReturn(List.of(listing));
    when(nominatimClient.reverseGeocode(any(), any())).thenReturn(Optional.of("Минск"));

    // When
    geocodingJob.runGeocoding();

    // Then
    assertThat(listing.getCity()).isEqualTo("Минск");
    verify(listingRepository).findNeedingGeocoding(anyInt(), any());
    verify(listingRepository).save(listing);
    verify(nominatimClient).reverseGeocode(listing.getLatitude(), listing.getLongitude());
  }

  @Test
  void should_resolve_multiple_listings_in_batch() {
    // Given
    var listing1 = buildListingWithCoordinates(1L, "53.9", "27.5");
    var listing2 = buildListingWithCoordinates(2L, "54.0", "27.6");
    when(listingRepository.findNeedingGeocoding(anyInt(), any())).thenReturn(List.of(listing1, listing2));
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
    when(listingRepository.findNeedingGeocoding(anyInt(), any())).thenReturn(List.of(listing));
    when(nominatimClient.reverseGeocode(any(), any())).thenReturn(Optional.empty());

    // When
    geocodingJob.runGeocoding();

    // Then — city remains null, but the failed attempt is still recorded (issue #380)
    assertThat(listing.getCity()).isNull();
    assertThat(listing.getGeocodingFailedAttempts()).isEqualTo(1);
    verify(listingRepository).save(listing);
  }

  @Test
  void should_do_nothing_when_no_listings_need_geocoding() {
    // Given
    when(listingRepository.findNeedingGeocoding(anyInt(), any())).thenReturn(List.of());

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
    when(listingRepository.findNeedingGeocoding(anyInt(), any())).thenReturn(List.of(listing));
    when(nominatimClient.reverseGeocode(any(), any()))
        .thenThrow(new RuntimeException("Connection refused"));

    // When / Then — exception is swallowed; city not set, but the failed attempt is recorded
    assertThatNoException().isThrownBy(() -> geocodingJob.runGeocoding());
    assertThat(listing.getCity()).isNull();
    assertThat(listing.getGeocodingFailedAttempts()).isEqualTo(1);
    verify(listingRepository).save(listing);
  }

  @Test
  void should_resolve_remaining_listings_when_one_listing_fails_mid_batch() {
    // Given — three listings; the second one fails, first and third must still resolve
    var listing1 = buildListingWithCoordinates(1L, "53.9", "27.5");
    var listing2 = buildListingWithCoordinates(2L, "54.0", "27.6");
    var listing3 = buildListingWithCoordinates(3L, "55.0", "30.0");
    when(listingRepository.findNeedingGeocoding(anyInt(), any())).thenReturn(List.of(listing1, listing2, listing3));
    when(nominatimClient.reverseGeocode(listing1.getLatitude(), listing1.getLongitude()))
        .thenReturn(Optional.of("Минск"));
    when(nominatimClient.reverseGeocode(listing2.getLatitude(), listing2.getLongitude()))
        .thenThrow(new RuntimeException("Connection refused"));
    when(nominatimClient.reverseGeocode(listing3.getLatitude(), listing3.getLongitude()))
        .thenReturn(Optional.of("Гродно"));

    // When
    assertThatNoException().isThrownBy(() -> geocodingJob.runGeocoding());

    // Then — listing2's failure did not abort geocoding for listing1/listing3, but is recorded
    assertThat(listing1.getCity()).isEqualTo("Минск");
    assertThat(listing2.getCity()).isNull();
    assertThat(listing2.getGeocodingFailedAttempts()).isEqualTo(1);
    assertThat(listing3.getCity()).isEqualTo("Гродно");
    verify(listingRepository).save(listing1);
    verify(listingRepository).save(listing2);
    verify(listingRepository).save(listing3);
  }

  @Test
  void should_skip_iteration_without_exception_when_repository_throws() {
    // Given — e.g. DB unavailable
    when(listingRepository.findNeedingGeocoding(anyInt(), any()))
        .thenThrow(new RuntimeException("DB connection lost"));

    // When / Then
    assertThatNoException().isThrownBy(() -> geocodingJob.runGeocoding());
  }

  // -------------------------------------------------------------------------
  // Failed-attempts threshold (#380)
  // -------------------------------------------------------------------------

  @Test
  void should_pass_configured_max_attempts_to_repository() {
    // Given
    ReflectionTestUtils.setField(geocodingJob, "maxAttempts", 7);
    when(listingRepository.findNeedingGeocoding(anyInt(), any())).thenReturn(List.of());

    // When
    geocodingJob.runGeocoding();

    // Then
    verify(listingRepository).findNeedingGeocoding(eq(7), any());
  }

  @Test
  void should_increment_failed_attempts_counter_each_time_geocoding_fails() {
    // Given — a listing that already failed twice before, fails again this run
    var listing = buildListingWithCoordinates(4L, "53.9", "27.5");
    listing.setGeocodingFailedAttempts(2);
    when(listingRepository.findNeedingGeocoding(anyInt(), any())).thenReturn(List.of(listing));
    when(nominatimClient.reverseGeocode(any(), any())).thenReturn(Optional.empty());

    // When
    geocodingJob.runGeocoding();

    // Then
    assertThat(listing.getGeocodingFailedAttempts()).isEqualTo(3);
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
