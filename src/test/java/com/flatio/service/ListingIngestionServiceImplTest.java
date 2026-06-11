package com.flatio.service;

import com.flatio.integration.core.RawListing;
import com.flatio.domain.country.Country;
import com.flatio.domain.currency.Currency;
import com.flatio.domain.listing.DealType;
import com.flatio.domain.listing.Listing;
import com.flatio.domain.listing.ListingStatus;
import com.flatio.domain.listing.PriceHistory;
import com.flatio.domain.source.Source;
import com.flatio.repository.CurrencyRepository;
import com.flatio.repository.ListingRepository;
import com.flatio.repository.PriceHistoryRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.flatio.domain.listing.PriceUnit;
import com.flatio.service.domain.IngestOutcome;
import com.flatio.service.impl.ListingIngestionServiceImpl;
import com.flatio.integration.core.RawListingMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ListingIngestionServiceImplTest {

  @Mock private ListingRepository listingRepository;
  @Mock private PriceHistoryRepository priceHistoryRepository;
  @Mock private CurrencyRepository currencyRepository;
  @Mock private RawListingMapper rawListingMapper;
  @Mock private DedupHashService dedupHashService;
  @Mock private ListingIngestionService self;

  @InjectMocks
  private ListingIngestionServiceImpl ingestionService;

  private Currency byn;
  private Source source;

  @BeforeEach
  void setUp() {
    byn = buildCurrency("BYN");
    source = buildSource(1L, "ONLINER");
    // @Lazy @Autowired field is not injectable by Mockito constructor injection; set explicitly
    ReflectionTestUtils.setField(ingestionService, "self", self);
    ReflectionTestUtils.setField(ingestionService, "inactiveThreshold", 3);
  }

  // -------------------------------------------------------------------------
  // ingest — CREATE path
  // -------------------------------------------------------------------------

  @Test
  void should_return_created_when_listing_does_not_exist() {
    // Given
    var raw = buildRawListing("ext-001", BigDecimal.valueOf(500));
    var mapped = new Listing();
    mapped.setDealType(DealType.RENT);

    when(currencyRepository.findByCode("BYN")).thenReturn(Optional.of(byn));
    when(listingRepository.findByExternalIdAndSourceId("ext-001", 1L)).thenReturn(Optional.empty());
    when(rawListingMapper.toEntity(raw)).thenReturn(mapped);
    when(dedupHashService.computeDedupHash(any(), any(), any(), any())).thenReturn("deadbeef");

    // When
    var result = ingestionService.ingest(raw, source);

    // Then
    assertThat(result).isEqualTo(IngestOutcome.CREATED);
  }

  @Test
  void should_set_source_currency_country_status_and_dedup_hash_on_create() {
    // Given
    var raw = buildRawListing("ext-002", BigDecimal.valueOf(600));
    var mapped = new Listing();
    mapped.setDealType(DealType.SELL);

    when(currencyRepository.findByCode("BYN")).thenReturn(Optional.of(byn));
    when(listingRepository.findByExternalIdAndSourceId(anyString(), anyLong())).thenReturn(Optional.empty());
    when(rawListingMapper.toEntity(raw)).thenReturn(mapped);
    when(dedupHashService.computeDedupHash(any(), any(), any(), any())).thenReturn("abc123");

    // When
    ingestionService.ingest(raw, source);

    // Then
    assertThat(mapped.getSource()).isEqualTo(source);
    assertThat(mapped.getCurrency()).isEqualTo(byn);
    assertThat(mapped.getCountry()).isEqualTo(source.getCountry());
    assertThat(mapped.getStatus()).isEqualTo(ListingStatus.ACTIVE);
    assertThat(mapped.getDedupHash()).isEqualTo("abc123");
  }

  @Test
  void should_record_initial_price_history_on_create() {
    // Given
    var raw = buildRawListing("ext-003", BigDecimal.valueOf(700));
    var mapped = new Listing();
    mapped.setDealType(DealType.RENT);
    mapped.setPrice(raw.price());

    when(currencyRepository.findByCode("BYN")).thenReturn(Optional.of(byn));
    when(listingRepository.findByExternalIdAndSourceId(anyString(), anyLong())).thenReturn(Optional.empty());
    when(rawListingMapper.toEntity(raw)).thenReturn(mapped);
    when(dedupHashService.computeDedupHash(any(), any(), any(), any())).thenReturn("hash1");

    // When
    ingestionService.ingest(raw, source);

    // Then
    var captor = ArgumentCaptor.forClass(PriceHistory.class);
    verify(priceHistoryRepository).save(captor.capture());
    assertThat(captor.getValue().getListing()).isEqualTo(mapped);
    assertThat(captor.getValue().getCurrency()).isEqualTo(byn);
  }

  // -------------------------------------------------------------------------
  // detectRepost — within-source repost detection
  // -------------------------------------------------------------------------

  @Test
  void should_set_status_reposted_and_reposted_from_when_original_found() {
    // Given — incoming listing has the same dedup hash as an existing listing from the same source
    var raw = buildRawListing("ext-repost-01", BigDecimal.valueOf(500));
    var mapped = new Listing();
    mapped.setDealType(DealType.RENT);
    mapped.setExternalId("ext-repost-01");

    var original = buildExistingListing("ext-original-01", BigDecimal.valueOf(500));
    original.setId(77L);

    when(currencyRepository.findByCode("BYN")).thenReturn(Optional.of(byn));
    when(listingRepository.findByExternalIdAndSourceId("ext-repost-01", 1L)).thenReturn(Optional.empty());
    when(rawListingMapper.toEntity(raw)).thenReturn(mapped);
    when(dedupHashService.computeDedupHash(any(), any(), any(), any())).thenReturn("same-hash");
    when(listingRepository.findFirstByDedupHashAndSourceAndExternalIdNotAndStatus("same-hash", source, "ext-repost-01", ListingStatus.ACTIVE))
        .thenReturn(Optional.of(original));

    // When
    ingestionService.ingest(raw, source);

    // Then
    assertThat(mapped.getStatus()).isEqualTo(ListingStatus.REPOSTED);
    assertThat(mapped.getRepostedFrom()).isEqualTo(77L);
  }

  @Test
  void should_set_last_reposted_at_on_original_when_repost_detected() {
    // Given
    var raw = buildRawListing("ext-repost-02", BigDecimal.valueOf(500));
    var mapped = new Listing();
    mapped.setDealType(DealType.RENT);
    mapped.setExternalId("ext-repost-02");

    var original = buildExistingListing("ext-original-02", BigDecimal.valueOf(500));
    original.setId(88L);

    when(currencyRepository.findByCode("BYN")).thenReturn(Optional.of(byn));
    when(listingRepository.findByExternalIdAndSourceId("ext-repost-02", 1L)).thenReturn(Optional.empty());
    when(rawListingMapper.toEntity(raw)).thenReturn(mapped);
    when(dedupHashService.computeDedupHash(any(), any(), any(), any())).thenReturn("same-hash-2");
    when(listingRepository.findFirstByDedupHashAndSourceAndExternalIdNotAndStatus("same-hash-2", source, "ext-repost-02", ListingStatus.ACTIVE))
        .thenReturn(Optional.of(original));

    // When
    ingestionService.ingest(raw, source);

    // Then
    assertThat(original.getLastRepostedAt()).isNotNull();
  }

  @Test
  void should_save_original_listing_when_repost_detected() {
    // Given
    var raw = buildRawListing("ext-repost-03", BigDecimal.valueOf(500));
    var mapped = new Listing();
    mapped.setDealType(DealType.RENT);
    mapped.setExternalId("ext-repost-03");

    var original = buildExistingListing("ext-original-03", BigDecimal.valueOf(500));
    original.setId(99L);

    when(currencyRepository.findByCode("BYN")).thenReturn(Optional.of(byn));
    when(listingRepository.findByExternalIdAndSourceId("ext-repost-03", 1L)).thenReturn(Optional.empty());
    when(rawListingMapper.toEntity(raw)).thenReturn(mapped);
    when(dedupHashService.computeDedupHash(any(), any(), any(), any())).thenReturn("same-hash-3");
    when(listingRepository.findFirstByDedupHashAndSourceAndExternalIdNotAndStatus("same-hash-3", source, "ext-repost-03", ListingStatus.ACTIVE))
        .thenReturn(Optional.of(original));

    // When
    ingestionService.ingest(raw, source);

    // Then — original must be saved with updated lastRepostedAt
    var captor = ArgumentCaptor.forClass(Listing.class);
    verify(listingRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
    var savedListings = captor.getAllValues();
    assertThat(savedListings).anyMatch(l -> l == original);
  }

  @Test
  void should_keep_status_active_when_no_original_found() {
    // Given — no other listing with the same dedup hash from the same source
    var raw = buildRawListing("ext-unique-01", BigDecimal.valueOf(500));
    var mapped = new Listing();
    mapped.setDealType(DealType.RENT);
    mapped.setExternalId("ext-unique-01");

    when(currencyRepository.findByCode("BYN")).thenReturn(Optional.of(byn));
    when(listingRepository.findByExternalIdAndSourceId("ext-unique-01", 1L)).thenReturn(Optional.empty());
    when(rawListingMapper.toEntity(raw)).thenReturn(mapped);
    when(dedupHashService.computeDedupHash(any(), any(), any(), any())).thenReturn("unique-hash");
    when(listingRepository.findFirstByDedupHashAndSourceAndExternalIdNotAndStatus("unique-hash", source, "ext-unique-01", ListingStatus.ACTIVE))
        .thenReturn(Optional.empty());

    // When
    ingestionService.ingest(raw, source);

    // Then
    assertThat(mapped.getStatus()).isEqualTo(ListingStatus.ACTIVE);
    assertThat(mapped.getRepostedFrom()).isNull();
  }

  @Test
  void should_skip_repost_check_when_dedup_hash_is_null() {
    // Given — dedupHashService returns null (e.g. no address available)
    var raw = buildRawListing("ext-nohash-01", BigDecimal.valueOf(500));
    var mapped = new Listing();
    mapped.setDealType(DealType.RENT);
    mapped.setExternalId("ext-nohash-01");

    when(currencyRepository.findByCode("BYN")).thenReturn(Optional.of(byn));
    when(listingRepository.findByExternalIdAndSourceId("ext-nohash-01", 1L)).thenReturn(Optional.empty());
    when(rawListingMapper.toEntity(raw)).thenReturn(mapped);
    when(dedupHashService.computeDedupHash(any(), any(), any(), any())).thenReturn(null);

    // When
    ingestionService.ingest(raw, source);

    // Then — repository must not be queried for repost detection
    verify(listingRepository, never()).findFirstByDedupHashAndSourceAndExternalIdNotAndStatus(
        anyString(), any(), anyString(), any());
    assertThat(mapped.getStatus()).isEqualTo(ListingStatus.ACTIVE);
  }

  @Test
  void should_return_created_outcome_when_repost_is_detected() {
    // Given — IngestOutcome is CREATED regardless of repost status; status is set on the Listing entity
    var raw = buildRawListing("ext-repost-04", BigDecimal.valueOf(500));
    var mapped = new Listing();
    mapped.setDealType(DealType.RENT);
    mapped.setExternalId("ext-repost-04");

    var original = buildExistingListing("ext-original-04", BigDecimal.valueOf(500));
    original.setId(101L);

    when(currencyRepository.findByCode("BYN")).thenReturn(Optional.of(byn));
    when(listingRepository.findByExternalIdAndSourceId("ext-repost-04", 1L)).thenReturn(Optional.empty());
    when(rawListingMapper.toEntity(raw)).thenReturn(mapped);
    when(dedupHashService.computeDedupHash(any(), any(), any(), any())).thenReturn("same-hash-4");
    when(listingRepository.findFirstByDedupHashAndSourceAndExternalIdNotAndStatus("same-hash-4", source, "ext-repost-04", ListingStatus.ACTIVE))
        .thenReturn(Optional.of(original));

    // When
    var result = ingestionService.ingest(raw, source);

    // Then
    assertThat(result).isEqualTo(IngestOutcome.CREATED);
  }

  // -------------------------------------------------------------------------
  // ingest — UPDATE path
  // -------------------------------------------------------------------------

  @Test
  void should_return_updated_when_listing_already_exists() {
    // Given
    var raw = buildRawListing("ext-010", BigDecimal.valueOf(500));
    var existing = buildExistingListing("ext-010", BigDecimal.valueOf(500)); // same price

    when(currencyRepository.findByCode("BYN")).thenReturn(Optional.of(byn));
    when(listingRepository.findByExternalIdAndSourceId("ext-010", 1L)).thenReturn(Optional.of(existing));
    when(dedupHashService.computeDedupHash(any(), any(), any(), any())).thenReturn("hash10");

    // When
    var result = ingestionService.ingest(raw, source);

    // Then
    assertThat(result).isEqualTo(IngestOutcome.UPDATED);
  }

  @Test
  void should_update_listing_fields_on_update() {
    // Given
    var raw = buildRawListing("ext-011", BigDecimal.valueOf(550));
    var existing = buildExistingListing("ext-011", BigDecimal.valueOf(500));

    when(currencyRepository.findByCode("BYN")).thenReturn(Optional.of(byn));
    when(listingRepository.findByExternalIdAndSourceId("ext-011", 1L)).thenReturn(Optional.of(existing));
    when(dedupHashService.computeDedupHash(any(), any(), any(), any())).thenReturn("hash11");

    // When
    ingestionService.ingest(raw, source);

    // Then
    assertThat(existing.getStatus()).isEqualTo(ListingStatus.ACTIVE);
    assertThat(existing.getDedupHash()).isEqualTo("hash11");
    verify(listingRepository).save(existing);
  }

  @Test
  void should_record_price_history_when_price_changes_on_update() {
    // Given
    var raw = buildRawListing("ext-012", BigDecimal.valueOf(600));
    var existing = buildExistingListing("ext-012", BigDecimal.valueOf(500)); // price changes

    when(currencyRepository.findByCode("BYN")).thenReturn(Optional.of(byn));
    when(listingRepository.findByExternalIdAndSourceId("ext-012", 1L)).thenReturn(Optional.of(existing));
    when(dedupHashService.computeDedupHash(any(), any(), any(), any())).thenReturn("hash12");

    // When
    ingestionService.ingest(raw, source);

    // Then — price changed → new price history record
    verify(priceHistoryRepository).save(any(PriceHistory.class));
  }

  @Test
  void should_not_record_price_history_when_price_is_unchanged_on_update() {
    // Given — same price
    var raw = buildRawListing("ext-013", BigDecimal.valueOf(500));
    var existing = buildExistingListing("ext-013", BigDecimal.valueOf(500));

    when(currencyRepository.findByCode("BYN")).thenReturn(Optional.of(byn));
    when(listingRepository.findByExternalIdAndSourceId("ext-013", 1L)).thenReturn(Optional.of(existing));
    when(dedupHashService.computeDedupHash(any(), any(), any(), any())).thenReturn("hash13");

    // When
    ingestionService.ingest(raw, source);

    // Then — price unchanged → no price history written
    verify(priceHistoryRepository, never()).save(any());
  }

  // -------------------------------------------------------------------------
  // isPriceChanged — USD source price comparison (#139)
  // -------------------------------------------------------------------------

  @Test
  void should_not_record_price_history_when_usd_price_unchanged_despite_byn_change() {
    // Given — seller kept USD price = $500; BYN equivalent changed due to exchange rate
    var raw = new RawListing(
        "ext-usd-1", "Test apartment", null, "RENT", "APARTMENT",
        BigDecimal.valueOf(1650), "BYN", BigDecimal.valueOf(500),
        2, 3, 9, BigDecimal.valueOf(55.5),
        "Минск", BigDecimal.valueOf(53.9), BigDecimal.valueOf(27.5),
        "Минск", "https://onliner.by/usd-1",
        Instant.parse("2026-06-01T10:00:00Z"), List.of(), null, null
    );
    var existing = buildExistingListing("ext-usd-1", BigDecimal.valueOf(1600));
    existing.setPriceUsd(BigDecimal.valueOf(500)); // same USD price

    when(currencyRepository.findByCode("BYN")).thenReturn(Optional.of(byn));
    when(listingRepository.findByExternalIdAndSourceId("ext-usd-1", 1L)).thenReturn(Optional.of(existing));
    when(dedupHashService.computeDedupHash(any(), any(), any(), any())).thenReturn("hash-usd-1");

    // When
    ingestionService.ingest(raw, source);

    // Then — USD unchanged → no false price_history
    verify(priceHistoryRepository, never()).save(any());
  }

  @Test
  void should_record_price_history_when_usd_price_actually_changed() {
    // Given — seller reduced USD price from $500 to $450
    var raw = new RawListing(
        "ext-usd-2", "Test apartment", null, "RENT", "APARTMENT",
        BigDecimal.valueOf(1440), "BYN", BigDecimal.valueOf(450),
        2, 3, 9, BigDecimal.valueOf(55.5),
        "Минск", BigDecimal.valueOf(53.9), BigDecimal.valueOf(27.5),
        "Минск", "https://onliner.by/usd-2",
        Instant.parse("2026-06-01T10:00:00Z"), List.of(), null, null
    );
    var existing = buildExistingListing("ext-usd-2", BigDecimal.valueOf(1600));
    existing.setPriceUsd(BigDecimal.valueOf(500)); // old USD price

    when(currencyRepository.findByCode("BYN")).thenReturn(Optional.of(byn));
    when(listingRepository.findByExternalIdAndSourceId("ext-usd-2", 1L)).thenReturn(Optional.of(existing));
    when(dedupHashService.computeDedupHash(any(), any(), any(), any())).thenReturn("hash-usd-2");

    // When
    ingestionService.ingest(raw, source);

    // Then — USD changed → price_history is recorded
    verify(priceHistoryRepository).save(any(PriceHistory.class));
  }

  @Test
  void should_fall_back_to_byn_comparison_when_existing_has_no_usd_price() {
    // Given — raw has priceUsd but existing doesn't → fall back to BYN comparison; BYN unchanged
    var raw = new RawListing(
        "ext-usd-3", "Test apartment", null, "RENT", "APARTMENT",
        BigDecimal.valueOf(1600), "BYN", BigDecimal.valueOf(500),
        2, 3, 9, BigDecimal.valueOf(55.5),
        "Минск", BigDecimal.valueOf(53.9), BigDecimal.valueOf(27.5),
        "Минск", "https://onliner.by/usd-3",
        Instant.parse("2026-06-01T10:00:00Z"), List.of(), null, null
    );
    var existing = buildExistingListing("ext-usd-3", BigDecimal.valueOf(1600));
    // existing.priceUsd stays null — source didn't provide it historically

    when(currencyRepository.findByCode("BYN")).thenReturn(Optional.of(byn));
    when(listingRepository.findByExternalIdAndSourceId("ext-usd-3", 1L)).thenReturn(Optional.of(existing));
    when(dedupHashService.computeDedupHash(any(), any(), any(), any())).thenReturn("hash-usd-3");

    // When
    ingestionService.ingest(raw, source);

    // Then — falls back to BYN comparison; BYN = 1600 = 1600 → no price_history
    verify(priceHistoryRepository, never()).save(any());
  }

  @Test
  void should_not_call_mapper_toEntity_on_update_path() {
    // Given — update uses existing entity, not the mapper
    var raw = buildRawListing("ext-014", BigDecimal.valueOf(500));
    var existing = buildExistingListing("ext-014", BigDecimal.valueOf(500));

    when(currencyRepository.findByCode("BYN")).thenReturn(Optional.of(byn));
    when(listingRepository.findByExternalIdAndSourceId("ext-014", 1L)).thenReturn(Optional.of(existing));
    when(dedupHashService.computeDedupHash(any(), any(), any(), any())).thenReturn("hash14");

    // When
    ingestionService.ingest(raw, source);

    // Then
    verify(rawListingMapper, never()).toEntity(any());
  }

  // -------------------------------------------------------------------------
  // ingest — price_unit derivation
  // -------------------------------------------------------------------------

  @Test
  void should_set_price_unit_per_month_when_deal_type_is_rent() {
    // Given
    var raw = buildRawListing("ext-pu1", BigDecimal.valueOf(500));
    var mapped = new Listing();
    mapped.setDealType(DealType.RENT);

    when(currencyRepository.findByCode("BYN")).thenReturn(Optional.of(byn));
    when(listingRepository.findByExternalIdAndSourceId("ext-pu1", 1L)).thenReturn(Optional.empty());
    when(rawListingMapper.toEntity(raw)).thenReturn(mapped);
    when(dedupHashService.computeDedupHash(any(), any(), any(), any())).thenReturn("hashpu1");

    // When
    ingestionService.ingest(raw, source);

    // Then
    assertThat(mapped.getPriceUnit()).isEqualTo(PriceUnit.PER_MONTH);
  }

  @Test
  void should_set_price_unit_per_day_when_deal_type_is_rent_daily() {
    // Given
    var raw = new RawListing(
        "ext-pu2", "Test", null, "RENT_DAILY", "APARTMENT",
        BigDecimal.valueOf(50), "BYN", null,
        2, 3, 9, BigDecimal.valueOf(55.5),
        "Минск", BigDecimal.valueOf(53.9), BigDecimal.valueOf(27.5),
        "Минск", "https://onliner.by/pu2",
        Instant.parse("2026-06-01T10:00:00Z"), List.of(), null, null
    );
    var mapped = new Listing();
    mapped.setDealType(DealType.RENT_DAILY);

    when(currencyRepository.findByCode("BYN")).thenReturn(Optional.of(byn));
    when(listingRepository.findByExternalIdAndSourceId("ext-pu2", 1L)).thenReturn(Optional.empty());
    when(rawListingMapper.toEntity(raw)).thenReturn(mapped);
    when(dedupHashService.computeDedupHash(any(), any(), any(), any())).thenReturn("hashpu2");

    // When
    ingestionService.ingest(raw, source);

    // Then
    assertThat(mapped.getPriceUnit()).isEqualTo(PriceUnit.PER_DAY);
  }

  @Test
  void should_set_price_unit_null_when_deal_type_is_sell() {
    // Given
    var raw = buildRawListing("ext-pu3", BigDecimal.valueOf(120_000));
    var mapped = new Listing();
    mapped.setDealType(DealType.SELL);

    when(currencyRepository.findByCode("BYN")).thenReturn(Optional.of(byn));
    when(listingRepository.findByExternalIdAndSourceId("ext-pu3", 1L)).thenReturn(Optional.empty());
    when(rawListingMapper.toEntity(raw)).thenReturn(mapped);
    when(dedupHashService.computeDedupHash(any(), any(), any(), any())).thenReturn("hashpu3");

    // When
    ingestionService.ingest(raw, source);

    // Then
    assertThat(mapped.getPriceUnit()).isNull();
  }

  @Test
  void should_update_price_unit_on_update_path() {
    // Given — existing listing has RENT deal type (mapped via mock), should get PER_MONTH
    var raw = buildRawListing("ext-pu4", BigDecimal.valueOf(500));
    var existing = buildExistingListing("ext-pu4", BigDecimal.valueOf(500));
    existing.setDealType(DealType.RENT);

    when(currencyRepository.findByCode("BYN")).thenReturn(Optional.of(byn));
    when(listingRepository.findByExternalIdAndSourceId("ext-pu4", 1L)).thenReturn(Optional.of(existing));
    when(dedupHashService.computeDedupHash(any(), any(), any(), any())).thenReturn("hashpu4");

    // When
    ingestionService.ingest(raw, source);

    // Then
    assertThat(existing.getPriceUnit()).isEqualTo(PriceUnit.PER_MONTH);
  }

  // -------------------------------------------------------------------------
  // ingest — unknown deal_type skip
  // -------------------------------------------------------------------------

  @Test
  void should_return_skipped_when_deal_type_is_unknown() {
    // Given — connector sends a deal_type value not in the DealType enum
    var raw = new RawListing(
        "ext-skip1", "Test", null, "AUCTION", "APARTMENT",
        BigDecimal.valueOf(500), "BYN", null,
        2, 3, 9, BigDecimal.valueOf(55.5),
        "Минск", BigDecimal.valueOf(53.9), BigDecimal.valueOf(27.5),
        "Минск", "https://onliner.by/skip1",
        Instant.parse("2026-06-01T10:00:00Z"), List.of(), null, null
    );

    // When
    var result = ingestionService.ingest(raw, source);

    // Then — no error, listing is skipped silently with WARN log
    assertThat(result).isEqualTo(IngestOutcome.SKIPPED);
  }

  @Test
  void should_return_skipped_when_deal_type_is_null() {
    // Given — connector sends null deal_type
    var raw = new RawListing(
        "ext-skip2", "Test", null, null, "APARTMENT",
        BigDecimal.valueOf(500), "BYN", null,
        2, 3, 9, BigDecimal.valueOf(55.5),
        "Минск", BigDecimal.valueOf(53.9), BigDecimal.valueOf(27.5),
        "Минск", "https://onliner.by/skip2",
        Instant.parse("2026-06-01T10:00:00Z"), List.of(), null, null
    );

    // When
    var result = ingestionService.ingest(raw, source);

    // Then
    assertThat(result).isEqualTo(IngestOutcome.SKIPPED);
  }

  @Test
  void should_not_call_repository_when_deal_type_is_unknown() {
    // Given
    var raw = new RawListing(
        "ext-skip3", "Test", null, "EXCHANGE", "APARTMENT",
        BigDecimal.valueOf(500), "BYN", null,
        2, 3, 9, BigDecimal.valueOf(55.5),
        "Минск", BigDecimal.valueOf(53.9), BigDecimal.valueOf(27.5),
        "Минск", "https://onliner.by/skip3",
        Instant.parse("2026-06-01T10:00:00Z"), List.of(), null, null
    );

    // When
    ingestionService.ingest(raw, source);

    // Then — no DB access attempted for unknown deal_type
    verify(listingRepository, never()).findByExternalIdAndSourceId(anyString(), anyLong());
    verify(listingRepository, never()).save(any());
  }

  @Test
  void should_not_count_as_error_when_batch_contains_unknown_deal_type() {
    // Given — one unknown deal_type, one valid listing
    var rawUnknown = new RawListing(
        "ext-batch-skip", "Test", null, "AUCTION", "APARTMENT",
        BigDecimal.valueOf(500), "BYN", null,
        2, 3, 9, BigDecimal.valueOf(55.5),
        "Минск", BigDecimal.valueOf(53.9), BigDecimal.valueOf(27.5),
        "Минск", "https://onliner.by/batch-skip",
        Instant.parse("2026-06-01T10:00:00Z"), List.of(), null, null
    );
    var rawValid = buildRawListing("ext-batch-valid", BigDecimal.valueOf(500));

    when(self.ingest(rawUnknown, source)).thenReturn(IngestOutcome.SKIPPED);
    when(self.ingest(rawValid, source)).thenReturn(IngestOutcome.CREATED);

    // When
    var result = ingestionService.ingestBatch(List.of(rawUnknown, rawValid), source);

    // Then — skipped listing does not increment errors
    assertThat(result.errors()).isEqualTo(0);
    assertThat(result.added()).isEqualTo(1);
  }

  // -------------------------------------------------------------------------
  // ingest — error handling
  // -------------------------------------------------------------------------

  @Test
  void should_throw_exception_when_currency_code_is_unknown() {
    // Given
    var raw = buildRawListing("ext-020", BigDecimal.valueOf(500));
    when(currencyRepository.findByCode("BYN")).thenReturn(Optional.empty());

    // When / Then
    assertThatThrownBy(() -> ingestionService.ingest(raw, source))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("BYN");
  }

  // -------------------------------------------------------------------------
  // ingestBatch — batch orchestration
  // -------------------------------------------------------------------------

  @Test
  void should_return_correct_counts_for_creates_and_updates() {
    // Given
    var raw1 = buildRawListing("ext-b01", BigDecimal.valueOf(500));
    var raw2 = buildRawListing("ext-b02", BigDecimal.valueOf(500));
    var raw3 = buildRawListing("ext-b03", BigDecimal.valueOf(500));

    when(self.ingest(raw1, source)).thenReturn(IngestOutcome.CREATED);
    when(self.ingest(raw2, source)).thenReturn(IngestOutcome.CREATED);
    when(self.ingest(raw3, source)).thenReturn(IngestOutcome.UPDATED);

    // When
    var result = ingestionService.ingestBatch(List.of(raw1, raw2, raw3), source);

    // Then
    assertThat(result.added()).isEqualTo(2);
    assertThat(result.updated()).isEqualTo(1);
    assertThat(result.errors()).isEqualTo(0);
  }

  @Test
  void should_count_error_when_ingest_throws_for_one_item() {
    // Given
    var raw1 = buildRawListing("ext-b10", BigDecimal.valueOf(500));
    var raw2 = buildRawListing("ext-b11", BigDecimal.valueOf(500));

    when(self.ingest(raw1, source)).thenThrow(new IllegalArgumentException("Unknown currency"));
    when(self.ingest(raw2, source)).thenReturn(IngestOutcome.CREATED);

    // When
    var result = ingestionService.ingestBatch(List.of(raw1, raw2), source);

    // Then
    assertThat(result.errors()).isEqualTo(1);
    assertThat(result.added()).isEqualTo(1);
  }

  @Test
  void should_not_propagate_exception_when_single_batch_item_fails() {
    // Given — all items fail
    var raw1 = buildRawListing("ext-b20", BigDecimal.valueOf(500));
    when(self.ingest(raw1, source)).thenThrow(new RuntimeException("Source unavailable"));

    // When / Then — batch method must not rethrow
    assertThatNoException().isThrownBy(() -> ingestionService.ingestBatch(List.of(raw1), source));
  }

  @Test
  void should_return_zero_counts_for_empty_batch() {
    // When
    var result = ingestionService.ingestBatch(List.of(), source);

    // Then
    assertThat(result.added()).isEqualTo(0);
    assertThat(result.updated()).isEqualTo(0);
    assertThat(result.errors()).isEqualTo(0);
  }

  // -------------------------------------------------------------------------
  // countBySource
  // -------------------------------------------------------------------------

  @Test
  void should_return_count_from_repository_when_counting_by_source() {
    // Given
    when(listingRepository.countBySource(source)).thenReturn(42L);

    // When
    long count = ingestionService.countBySource(source);

    // Then
    assertThat(count).isEqualTo(42L);
    verify(listingRepository).countBySource(source);
  }

  // -------------------------------------------------------------------------
  // deactivateMissing
  // -------------------------------------------------------------------------

  @Test
  void should_return_zero_and_skip_repository_when_active_ids_set_is_empty() {
    // Given — empty set means guard against accidental mass-deactivation
    var emptySet = Set.<String>of();

    // When
    int result = ingestionService.deactivateMissing(source, emptySet);

    // Then
    assertThat(result).isEqualTo(0);
    verify(listingRepository, never()).deactivateActiveBySourceExcluding(any(), any());
  }

  @Test
  void should_call_repository_deactivation_when_active_ids_provided() {
    // Given
    var activeIds = Set.of("ext-1", "ext-2", "ext-3");
    when(listingRepository.deactivateActiveBySourceExcluding(source, activeIds)).thenReturn(2);

    // When
    int result = ingestionService.deactivateMissing(source, activeIds);

    // Then
    assertThat(result).isEqualTo(2);
    verify(listingRepository).deactivateActiveBySourceExcluding(source, activeIds);
  }

  @Test
  void should_return_zero_when_no_listings_were_deactivated() {
    // Given — all current listings are still present in the source
    var activeIds = Set.of("ext-1", "ext-2");
    when(listingRepository.deactivateActiveBySourceExcluding(source, activeIds)).thenReturn(0);

    // When
    int result = ingestionService.deactivateMissing(source, activeIds);

    // Then
    assertThat(result).isEqualTo(0);
  }

  // -------------------------------------------------------------------------
  // applyMissedSyncPenalty
  // -------------------------------------------------------------------------

  @Test
  void should_return_zero_and_skip_repository_when_active_ids_empty_for_penalty() {
    // Given — guard: empty set means fetch failed, must not trigger mass update
    var emptySet = Set.<String>of();

    // When
    int result = ingestionService.applyMissedSyncPenalty(source, emptySet);

    // Then
    assertThat(result).isEqualTo(0);
    verify(listingRepository, never()).incrementMissedSyncsForAbsent(any(), any());
    verify(listingRepository, never()).deactivateByMissedSyncsThreshold(any(), anyInt());
  }

  @Test
  void should_call_increment_then_deactivate_when_active_ids_provided() {
    // Given
    var activeIds = Set.of("ext-1", "ext-2");
    when(listingRepository.incrementMissedSyncsForAbsent(source, activeIds)).thenReturn(5);
    when(listingRepository.deactivateByMissedSyncsThreshold(source, 3)).thenReturn(2);

    // When
    ingestionService.applyMissedSyncPenalty(source, activeIds);

    // Then — both repository calls issued in order
    verify(listingRepository).incrementMissedSyncsForAbsent(source, activeIds);
    verify(listingRepository).deactivateByMissedSyncsThreshold(source, 3);
  }

  @Test
  void should_return_deactivated_count_not_incremented_count() {
    // Given — incremented=5, deactivated=2; method must return deactivated
    var activeIds = Set.of("ext-1");
    when(listingRepository.incrementMissedSyncsForAbsent(source, activeIds)).thenReturn(5);
    when(listingRepository.deactivateByMissedSyncsThreshold(source, 3)).thenReturn(2);

    // When
    int result = ingestionService.applyMissedSyncPenalty(source, activeIds);

    // Then
    assertThat(result).isEqualTo(2);
  }

  @Test
  void should_return_zero_when_no_listings_reached_threshold() {
    // Given — counters incremented but none hit threshold yet
    var activeIds = Set.of("ext-1", "ext-2");
    when(listingRepository.incrementMissedSyncsForAbsent(source, activeIds)).thenReturn(3);
    when(listingRepository.deactivateByMissedSyncsThreshold(source, 3)).thenReturn(0);

    // When
    int result = ingestionService.applyMissedSyncPenalty(source, activeIds);

    // Then
    assertThat(result).isEqualTo(0);
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  private RawListing buildRawListing(String externalId, BigDecimal price) {
    return new RawListing(
        externalId, "Test apartment", "Description", "RENT", "APARTMENT",
        price, "BYN", null,
        2, 3, 9, BigDecimal.valueOf(55.5),
        "Минск, Немига 5",
        BigDecimal.valueOf(53.9), BigDecimal.valueOf(27.5),
        "Минск", "https://onliner.by/" + externalId,
        Instant.parse("2026-06-01T10:00:00Z"),
        List.of(), null, null
    );
  }

  private Listing buildExistingListing(String externalId, BigDecimal price) {
    var listing = new Listing();
    listing.setExternalId(externalId);
    listing.setSource(source);
    listing.setPrice(price);
    listing.setCurrency(byn);
    listing.setDealType(DealType.RENT);
    listing.setStatus(ListingStatus.ACTIVE);
    listing.setTitle("Old title");
    listing.setSourceUrl("https://onliner.by/" + externalId);
    return listing;
  }

  private Currency buildCurrency(String code) {
    var currency = new Currency();
    currency.setId(1L);
    currency.setCode(code);
    currency.setSymbol("Br");
    return currency;
  }

  private Source buildSource(Long id, String code) {
    var country = new Country();
    country.setId(1L);
    country.setCode("BY");
    country.setName("Belarus");

    var src = new Source();
    src.setId(id);
    src.setCode(code);
    src.setName("Onliner");
    src.setUrl("https://onliner.by");
    src.setActive(true);
    src.setCountry(country);
    return src;
  }
}
