package com.flatio.scheduler;

import com.flatio.domain.listing.Listing;
import com.flatio.integration.nominatim.client.NominatimClient;
import com.flatio.repository.ListingRepository;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Background job that enriches listings with a human-readable city name via reverse geocoding.
 *
 * <p>On each run, selects a batch of listings that have coordinates but no city, calls
 * {@link NominatimClient} for each one, and persists the resolved name. A failure resolving one
 * listing (rate limiter timeout, network error) only skips that listing — it does not abort
 * geocoding for the rest of the batch (issue #372). A failure reading the batch itself (e.g. the
 * database is unavailable) skips the whole run with a warning; the job is retried on the next
 * scheduled trigger.
 *
 * <p>Cron schedule is configurable via {@code flatio.geocoding.cron}
 * (env: {@code FLATIO_GEOCODING_CRON}); default is every 30 minutes.
 * Batch size is configurable via {@code flatio.geocoding.batch-size}
 * (env: {@code FLATIO_GEOCODING_BATCH_SIZE}); default is 50.
 * A listing whose {@code geocodingFailedAttempts} reaches {@code flatio.geocoding.max-attempts}
 * (env: {@code FLATIO_GEOCODING_MAX_ATTEMPTS}; default 5) is excluded from future batches —
 * otherwise persistently bad coordinates would keep occupying batch slots forever (issue #380).
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class GeocodingJob {

  private final ListingRepository listingRepository;
  private final NominatimClient nominatimClient;

  @Value("${flatio.geocoding.batch-size:50}")
  private int batchSize;

  @Value("${flatio.geocoding.max-attempts:5}")
  private int maxAttempts;

  /**
   * Selects a batch of listings without a city name and resolves each one via Nominatim.
   *
   * <p>On any connectivity or rate-limit error the whole iteration is skipped and a warning
   * is logged. The job does not propagate exceptions — a failure in one run does not affect
   * the scheduler or subsequent runs.
   */
  @Scheduled(cron = "${flatio.geocoding.cron:0 */30 * * * *}")
  public void runGeocoding() {
    log.info("Geocoding job started: batchSize={}", batchSize);
    try {
      List<Listing> batch = listingRepository.findNeedingGeocoding(maxAttempts, PageRequest.of(0, batchSize));
      if (batch.isEmpty()) {
        log.debug("Geocoding job: no listings need geocoding");
        return;
      }
      int resolved = processListings(batch);
      log.info("Geocoding job completed: processed={}, resolved={}", batch.size(), resolved);
    } catch (Exception e) {
      log.warn("Geocoding job skipped: nominatim unavailable: error={}", e.getMessage());
    }
  }

  private int processListings(List<Listing> listings) {
    int resolved = 0;
    for (Listing listing : listings) {
      if (resolveOne(listing)) {
        resolved++;
      }
    }
    return resolved;
  }

  /**
   * Resolves and persists the city for a single listing, isolated from the rest of the batch.
   *
   * <p>A transient failure for one listing (rate limiter timeout, network error) must not abort
   * geocoding for the remaining listings in the batch — mirrors the per-item isolation every
   * source connector already applies (issue #372). Every failed attempt — Nominatim returning
   * no result or an error — increments {@code geocodingFailedAttempts} so a listing with
   * persistently bad coordinates eventually drops out of {@link #runGeocoding}'s batch (#380).
   *
   * @param listing the listing to geocode, never null
   * @return true if the city was resolved and persisted, false otherwise
   */
  private boolean resolveOne(Listing listing) {
    try {
      Optional<String> city = nominatimClient.reverseGeocode(listing.getLatitude(), listing.getLongitude());
      if (city.isEmpty()) {
        registerFailedAttempt(listing);
        return false;
      }
      listing.setCity(city.get());
      listingRepository.save(listing);
      return true;
    } catch (Exception e) {
      log.warn("Skipping geocoding for listing due to error: listingId={}, error={}",
          listing.getId(), e.getMessage());
      registerFailedAttempt(listing);
      return false;
    }
  }

  private void registerFailedAttempt(Listing listing) {
    listing.setGeocodingFailedAttempts(listing.getGeocodingFailedAttempts() + 1);
    listingRepository.save(listing);
  }
}
