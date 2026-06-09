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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
  // ingest — unknown deal_type skip
  // -------------------------------------------------------------------------

  @Test
  void should_return_skipped_when_deal_type_is_unknown() {
    // Given — connector sends a deal_type value not in the DealType enum
    var raw = new RawListing(
        "ext-skip1", "Test", null, "AUCTION", "APARTMENT",
        BigDecimal.valueOf(500), "BYN",
        2, 3, 9, BigDecimal.valueOf(55.5),
        "Минск", BigDecimal.valueOf(53.9), BigDecimal.valueOf(27.5),
        "Минск", "https://onliner.by/skip1",
        Instant.parse("2026-06-01T10:00:00Z"), List.of()
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
        BigDecimal.valueOf(500), "BYN",
        2, 3, 9, BigDecimal.valueOf(55.5),
        "Минск", BigDecimal.valueOf(53.9), BigDecimal.valueOf(27.5),
        "Минск", "https://onliner.by/skip2",
        Instant.parse("2026-06-01T10:00:00Z"), List.of()
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
        BigDecimal.valueOf(500), "BYN",
        2, 3, 9, BigDecimal.valueOf(55.5),
        "Минск", BigDecimal.valueOf(53.9), BigDecimal.valueOf(27.5),
        "Минск", "https://onliner.by/skip3",
        Instant.parse("2026-06-01T10:00:00Z"), List.of()
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
        BigDecimal.valueOf(500), "BYN",
        2, 3, 9, BigDecimal.valueOf(55.5),
        "Минск", BigDecimal.valueOf(53.9), BigDecimal.valueOf(27.5),
        "Минск", "https://onliner.by/batch-skip",
        Instant.parse("2026-06-01T10:00:00Z"), List.of()
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
  // Helpers
  // -------------------------------------------------------------------------

  private RawListing buildRawListing(String externalId, BigDecimal price) {
    return new RawListing(
        externalId, "Test apartment", "Description", "RENT", "APARTMENT",
        price, "BYN",
        2, 3, 9, BigDecimal.valueOf(55.5),
        "Минск, Немига 5",
        BigDecimal.valueOf(53.9), BigDecimal.valueOf(27.5),
        "Минск", "https://onliner.by/" + externalId,
        Instant.parse("2026-06-01T10:00:00Z"),
        List.of()
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
