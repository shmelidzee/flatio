package com.flatio.scheduler;

import com.flatio.integration.core.ListingConnector;
import com.flatio.integration.core.RawListing;
import com.flatio.domain.source.Source;
import com.flatio.repository.SourceRepository;
import com.flatio.service.domain.BatchIngestResult;
import com.flatio.service.ListingIngestionService;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ListingSyncSchedulerTest {

  @Mock
  private ListingConnector connector;

  @Mock
  private SourceRepository sourceRepository;

  @Mock
  private ListingIngestionService listingIngestionService;

  private ListingSyncScheduler scheduler;

  @BeforeEach
  void setUp() {
    scheduler = new ListingSyncScheduler(List.of(connector), sourceRepository, listingIngestionService);
  }

  // -------------------------------------------------------------------------
  // syncAll — happy path
  // -------------------------------------------------------------------------

  @Test
  void should_call_fetch_and_ingest_when_source_found() {
    // Given
    var source = new Source();
    var rawListing = buildRawListing("ext-1");
    when(connector.getSourceId()).thenReturn("ONLINER");
    when(sourceRepository.findByCode("ONLINER")).thenReturn(Optional.of(source));
    when(connector.fetch()).thenReturn(List.of(rawListing));
    when(listingIngestionService.ingestBatch(List.of(rawListing), source))
        .thenReturn(new BatchIngestResult(1, 0, 0));

    // When
    scheduler.syncAll();

    // Then
    verify(connector).fetch();
    verify(listingIngestionService).ingestBatch(List.of(rawListing), source);
  }

  @Test
  void should_pass_all_fetched_listings_to_ingest() {
    // Given
    var source = new Source();
    var raw1 = buildRawListing("ext-1");
    var raw2 = buildRawListing("ext-2");
    var raw3 = buildRawListing("ext-3");
    when(connector.getSourceId()).thenReturn("ONLINER");
    when(sourceRepository.findByCode("ONLINER")).thenReturn(Optional.of(source));
    when(connector.fetch()).thenReturn(List.of(raw1, raw2, raw3));
    when(listingIngestionService.ingestBatch(any(), eq(source)))
        .thenReturn(new BatchIngestResult(3, 0, 0));

    // When
    scheduler.syncAll();

    // Then
    verify(listingIngestionService).ingestBatch(List.of(raw1, raw2, raw3), source);
  }

  @Test
  void should_handle_empty_fetch_result_without_exception() {
    // Given
    var source = new Source();
    when(connector.getSourceId()).thenReturn("ONLINER");
    when(sourceRepository.findByCode("ONLINER")).thenReturn(Optional.of(source));
    when(connector.fetch()).thenReturn(List.of());
    when(listingIngestionService.ingestBatch(List.of(), source))
        .thenReturn(new BatchIngestResult(0, 0, 0));

    // When / Then — no exception
    scheduler.syncAll();
    verify(listingIngestionService).ingestBatch(List.of(), source);
  }

  // -------------------------------------------------------------------------
  // syncAll — error isolation
  // -------------------------------------------------------------------------

  @Test
  void should_not_call_fetch_when_source_not_found_in_db() {
    // Given
    when(connector.getSourceId()).thenReturn("ONLINER");
    when(sourceRepository.findByCode("ONLINER")).thenReturn(Optional.empty());

    // When / Then — no exception propagates; fetch never called
    scheduler.syncAll();

    verify(connector, never()).fetch();
    verify(listingIngestionService, never()).ingestBatch(any(), any());
  }

  @Test
  void should_not_propagate_exception_when_connector_fetch_throws() {
    // Given
    var source = new Source();
    when(connector.getSourceId()).thenReturn("ONLINER");
    when(sourceRepository.findByCode("ONLINER")).thenReturn(Optional.of(source));
    when(connector.fetch()).thenThrow(new RuntimeException("Connection refused"));

    // When / Then — exception is swallowed, ingest never called
    scheduler.syncAll();

    verify(listingIngestionService, never()).ingestBatch(any(), any());
  }

  @Test
  void should_skip_connector_and_not_propagate_when_circuit_is_open() {
    // Given — circuit breaker is OPEN; source is found but fetch throws CallNotPermittedException
    var source = new Source();
    when(connector.getSourceId()).thenReturn("ONLINER");
    when(sourceRepository.findByCode("ONLINER")).thenReturn(Optional.of(source));
    when(connector.fetch()).thenThrow(
        CallNotPermittedException.createCallNotPermittedException(CircuitBreaker.ofDefaults("test-cb")));

    // When / Then — no exception propagates, ingest is never called
    assertThatNoException().isThrownBy(() -> scheduler.syncAll());
    verify(listingIngestionService, never()).ingestBatch(any(), any());
  }

  @Test
  void should_not_propagate_exception_when_ingest_throws() {
    // Given
    var source = new Source();
    var rawListing = buildRawListing("ext-1");
    when(connector.getSourceId()).thenReturn("ONLINER");
    when(sourceRepository.findByCode("ONLINER")).thenReturn(Optional.of(source));
    when(connector.fetch()).thenReturn(List.of(rawListing));
    when(listingIngestionService.ingestBatch(any(), any()))
        .thenThrow(new RuntimeException("DB unavailable"));

    // When / Then — no exception propagates
    scheduler.syncAll();
  }

  @Test
  void should_sync_second_connector_when_first_fails() {
    // Given — two connectors, first fails, second succeeds
    var connector2 = org.mockito.Mockito.mock(ListingConnector.class);
    var source2 = new Source();

    when(connector.getSourceId()).thenReturn("ONLINER");
    when(sourceRepository.findByCode("ONLINER")).thenReturn(Optional.empty());

    when(connector2.getSourceId()).thenReturn("REALT");
    when(sourceRepository.findByCode("REALT")).thenReturn(Optional.of(source2));
    when(connector2.fetch()).thenReturn(List.of());
    when(listingIngestionService.ingestBatch(List.of(), source2))
        .thenReturn(new BatchIngestResult(0, 0, 0));

    var twoConnectorScheduler = new ListingSyncScheduler(
        List.of(connector, connector2), sourceRepository, listingIngestionService);

    // When
    twoConnectorScheduler.syncAll();

    // Then — second connector was still processed
    verify(connector2).fetch();
    verify(listingIngestionService).ingestBatch(List.of(), source2);
  }

  // -------------------------------------------------------------------------
  // helpers
  // -------------------------------------------------------------------------

  private RawListing buildRawListing(String externalId) {
    return new RawListing(
        externalId,
        "Test listing",
        null,
        "RENT",
        null,
        BigDecimal.valueOf(50000),
        "BYN",
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        "https://onliner.by/" + externalId,
        null,
        null,
        null
    );
  }
}
