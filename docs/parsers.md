# Connectors (Parsers) — Flatio

Connectors fetch raw listing data from external sources and convert it to `RawListing` records.
Each connector is an independent Spring `@Service` implementing `com.flatio.integration.core.ListingConnector`.

---

## Architecture

```
OnlinerDeltaSyncJob (every 10 min)    OnlinerFullSyncJob (daily 02:00)
  ↓                                       ↓
ListingConnector.fetchDelta(since)    ListingConnector.fetchAll()
  ↓                                       ↓
  ←── @RateLimiter + @Retry + @CircuitBreaker ──→
  ↓                                       ↓
List<RawListing>              ←── never raw HTML ──→
  ↓
ListingIngestionService (dedup + persist)
```

All connectors share the same contract — see `docs/architecture.md`, section **Connector Contract**.

---

## OnlinerConnector

**Source:** Onliner.by REST API (JSON)  
**Region:** BY (Belarus)  
**Package:** `com.flatio.integration.onliner.client`  
**Endpoint:** `GET {baseUrl}/search/apartments?page=1&limit={pageSize}`

### Configuration (`application.yml`)

```yaml
connector:
  onliner:
    base-url: ${ONLINER_BASE_URL:https://ak.api.onliner.by}
    source-id: ${ONLINER_SOURCE_ID:ONLINER}
    region-code: ${ONLINER_REGION_CODE:BY}
    apartments-path: /search/apartments
    page-size: 50
```

All values are configurable via environment variables. Defaults are sufficient for local development.

### Resilience4j config

```yaml
resilience4j:
  ratelimiter:
    instances:
      connector-onliner:
        limit-for-period: 1
        limit-refresh-period: 1s
        timeout-duration: 5s
  retry:
    instances:
      connector-onliner:
        max-attempts: 3
        wait-duration: 2s
        enable-exponential-backoff: true
        exponential-backoff-multiplier: 2
        retry-exceptions:
          - org.springframework.web.client.HttpServerErrorException
          - org.springframework.web.client.ResourceAccessException
          - com.flatio.integration.core.ConnectorTransientException
        ignore-exceptions:
          - io.github.resilience4j.circuitbreaker.CallNotPermittedException
  circuitbreaker:
    instances:
      connector-onliner:
        sliding-window-type: COUNT_BASED
        sliding-window-size: 5
        minimum-number-of-calls: 5
        failure-rate-threshold: 100
        wait-duration-in-open-state: 60s
        automatic-transition-from-open-to-half-open-enabled: true
        permitted-number-of-calls-in-half-open-state: 1
```

**Aspect order:** `@RateLimiter` (outermost) → `@Retry` → `@CircuitBreaker` (innermost).

- **Rate limiter:** 1 request/second; waits up to 5s for a permit.
- **Circuit breaker:** opens after 5 consecutive failures; stays open 60s; transitions to HALF_OPEN automatically; 1 probe call allowed.
- **Retry:** 3 attempts with exponential backoff 2s → 4s → 8s; retries on 5xx, network errors, and HTTP 429 (`ConnectorTransientException`). When the circuit breaker is OPEN, `CallNotPermittedException` is in `ignore-exceptions` — it bypasses retry and goes directly to `fetchFallback`.
- **Fallback:** `fetchFallback(Exception e)` — returns `List.of()` after exhausted retries.

### HTTP client (`OnlinerClientConfig`)

`RestClient` bean `"onlinerRestClient"` configured with:
- Base URL from `OnlinerProperties`
- `User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36`
- Connect timeout: **5 seconds**
- Read timeout: **10 seconds**

### RawListing field mapping

| `RawListing` field | Onliner JSON field | Notes |
|--------------------|--------------------|-------|
| `externalId` | `id` | Converted to String |
| `title` | Built from `rooms_count`, `area.total`, `location.address` | Fallback: "Квартира на Onliner" |
| `description` | — | Always `null` (not returned in list view) |
| `dealType` | `deal_type` | `"rent"` / `"sell"` as-is |
| `propertyType` | `rent_type` | `"room"` → `"ROOM"`; anything else (including null) → `"APARTMENT"` |
| `price` | `price.converted.BYN.amount` | BYN price; exception if BYN key absent — listing skipped |
| `currency` | — | Always `"BYN"` |
| `priceUsd` | `price.converted.USD.amount` | nullable; `null` when USD key absent in `converted` |
| `rooms` | `rent_type` | Mapped via table: `1_room`→1, `2_rooms`→2, `3_rooms`→3, `4_rooms`→4; `null` otherwise |
| `floorNumber` | `floor` | nullable |
| `floorsTotal` | `number_of_floors` | nullable |
| `areaTotalM2` | `area.total` | nullable |
| `address` | `location.address` | nullable |
| `latitude` | `location.latitude` | nullable |
| `longitude` | `location.longitude` | nullable |
| `city` | — | Always `null` (no separate city field in Onliner API) |
| `sourceUrl` | `url` | |
| `publishedAt` | `last_time_up` | ISO-8601 string → `Instant`; `null` when field absent |
| `photoUrls` | `photo` | Single photo URL wrapped in `List.of()`; `List.of()` when null |
| `isOwner` | `contact.owner` | `true` when owner, `false` when agency; `null` when `contact` absent |

### Error handling

- **HTTP 5xx / network failure:** exception propagates from `fetch()`, `@Retry` triggers with exponential backoff; after 3 failed attempts circuit breaker records the failure; `fetchFallback` returns `List.of()`
- **HTTP 429 Too Many Requests:** `Retry-After` header is read (default 5s), thread sleeps, then `ConnectorTransientException` is thrown to trigger retry
- **HTTP 4xx (non-429):** logged at ERROR, returns `List.of()` without retry
- **Circuit breaker OPEN:** `CallNotPermittedException` is caught by `OnlinerDeltaSyncJob` / `OnlinerFullSyncJob`, which logs `WARN "Circuit OPEN, skipping Onliner sync"` and exits the current run without propagating the exception
- **Single broken listing** (e.g., `price: null`, invalid `price.amount`): skipped with `log.warn`, rest are processed
- **Null / empty API response:** `fetch()` returns `List.of()` without retry

### Test coverage

File: `src/test/java/com/flatio/integration/onliner/client/OnlinerConnectorTest.java`  
Fixtures: `src/test/resources/fixtures/onliner/`

| Test | Scenario |
|------|----------|
| `should_return_listings_when_valid_response_provided` | Happy path — 2 listings mapped |
| `should_return_source_id_and_region_from_properties` | No hard-coded values |
| `should_return_empty_list_when_apartments_list_is_empty` | Empty API response |
| `should_return_empty_list_when_api_returns_null_response` | Null body from API |
| `should_return_empty_list_when_fallback_is_invoked_after_exhausted_retries` | Fallback after retry exhaustion |
| `should_not_throw_from_fallback_method` | Fallback is always safe |
| `should_skip_listing_with_null_price_amount_and_return_others` | Per-listing error isolation (invalid amount string) |
| `should_map_all_required_fields_correctly` | Full field mapping check |
| `should_return_fallback_title_when_all_title_fields_are_null` | Title fallback logic |
| `should_return_empty_photo_list_when_photo_is_null` | Null photo handling |
| `should_throw_connector_transient_exception_when_429_received` | HTTP 429 → `ConnectorTransientException` |
| `should_use_retry_after_header_when_present_in_429_response` | `Retry-After` header is read |
| `should_return_empty_list_when_non_retryable_4xx_received` | HTTP 4xx (non-429) — swallowed |
| `should_propagate_server_exception_when_5xx_received` | HTTP 5xx propagates for retry tracking |
| `should_correctly_deserialize_valid_response_fixture_including_json_property_mappings` | JSON `@JsonProperty` mappings via fixture |
| `should_return_empty_list_from_empty_response_fixture` | Empty fixture deserialized correctly |
| `should_skip_listing_with_null_price_when_loaded_from_fixture` | `price: null` in fixture → listing skipped |
| `should_map_rent_type_room_to_property_type_room` | `rent_type="room"` → `propertyType="ROOM"` |
| `should_map_rent_type_1_room_to_property_type_apartment` | `rent_type="1_room"` → `propertyType="APARTMENT"` |
| `should_map_null_rent_type_to_property_type_apartment` | `rent_type=null` → `propertyType="APARTMENT"` (default) |
| `should_map_rent_type_1_room_to_rooms_count_1` | `rent_type="1_room"` → `rooms=1` |
| `should_map_rent_type_2_rooms_to_rooms_count_2` | `rent_type="2_rooms"` → `rooms=2` |
| `should_map_rent_type_3_rooms_to_rooms_count_3` | `rent_type="3_rooms"` → `rooms=3` |
| `should_map_rent_type_4_rooms_to_rooms_count_4` | `rent_type="4_rooms"` → `rooms=4` |
| `should_map_rent_type_room_to_rooms_count_null` | `rent_type="room"` → `rooms=null` |
| `should_map_contact_owner_true_to_is_owner_true` | `contact.owner=true` → `isOwner=true` |
| `should_map_contact_owner_false_to_is_owner_false` | `contact.owner=false` → `isOwner=false` |
| `should_return_null_is_owner_when_contact_is_absent` | `contact=null` → `isOwner=null` |

---

## Adding a New Connector

1. Create package `com.flatio.integration.{source}` with sub-packages `client`, `config`, `dto`
2. Implement `ListingConnector` in `client/` — `getSourceId()`, `getSupportedRegionCode()`, `fetch()`
3. Create `{Source}Properties` record in `config/` with `@ConfigurationProperties(prefix = "connector.{source}")`
4. Create `{Source}ClientConfig` in `config/` with `@Bean("{source}RestClient")` — timeouts and User-Agent
5. Add Resilience4j config in `application.yml` under `resilience4j.ratelimiter`, `retry`, and `circuitbreaker`
6. Add connector env-variables section in `application.yml` under `connector.{source}`
7. Write unit tests covering all mandatory scenarios (see `testing-standards.md`)
8. Add connector row to the table in `docs/parsers.md`
