# Connectors (Parsers) — Flatio

Connectors fetch raw listing data from external sources and convert it to `RawListing` records.
Each connector is an independent Spring `@Service` implementing `com.flatio.integration.core.ListingConnector`.

---

## Architecture

```
Delta sync jobs (scheduled)                Full sync jobs (daily cron + startup)
─────────────────────────────────────      ─────────────────────────────────────
OnlinerDeltaSyncJob       (every 10 min)   OnlinerFullSyncJob        (02:00)
OnlinerSaleDeltaSyncJob   (every 15 min)   OnlinerSaleFullSyncJob    (03:00)
RealtDeltaSyncJob         (every 15 min)   RealtFullSyncJob          (04:00)
RealtSaleDeltaSyncJob     (every 15 min)   RealtSaleFullSyncJob      (05:00)
RealtRoomDeltaSyncJob     (every  5 min)   RealtRoomFullSyncJob      (06:00)
RealtRoomSaleDeltaSyncJob (every  5 min)   RealtRoomSaleFullSyncJob  (07:00)
RealtHouseSaleDeltaSyncJob(every  5 min)   RealtHouseSaleFullSyncJob (08:00)
        ↓                                          ↓
ListingConnector.fetchDelta(since)    ListingConnector.fetch()
        ↓                                          ↓
  @RateLimiter + @CircuitBreaker + @Retry (Resilience4j)
        ↓
  List<RawListing>  ←── never raw HTML
        ↓
  ListingIngestionService (dedup + persist)
        ↓
  SyncRunService (record SUCCESS / FAILURE)
```

**Realt connectors share a common parser:** `RealtHtmlParser` extracts listing data from `__NEXT_DATA__` JSON embedded in SSR pages. Each connector passes a `RealtPageContext` record that carries category-specific fields (`dealType`, `propertyType`, `objectPathPrefix`, `fallbackTitle`).

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
| `price` | `price.converted.BYN.amount` | BYN price; `null` → `isNegotiable=true`, `price=0` |
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
| `isNegotiable` | — | `true` when BYN price is absent; `false` otherwise |

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

## RealtConnector

**Source:** Realt.by (SSR HTML with embedded `__NEXT_DATA__` JSON)
**Region:** BY (Belarus)
**Package:** `com.flatio.integration.realt.client`
**Endpoint:** `GET {baseUrl}{listingsPath}?page={n}` — paginated listing pages

### How data is extracted

Realt.by is a Next.js application. Each SSR page embeds a `<script id="__NEXT_DATA__" type="application/json">` tag containing the full listing payload in `props.pageProps.objects`. The connector extracts this JSON using Jsoup (`script#__NEXT_DATA__`), parses it with Jackson, and maps each object in the array to a `RawListing`. HTML is only used to detect the next page link (`a[data-testid='nextBtn']`). No raw HTML is stored.

**Note:** The JSON `price` field is in USD (ISO 4217 code 840). The site displays BYN prices (converted client-side), but the canonical data source is USD.

### Configuration (`application.yml`)

```yaml
connector:
  realt:
    base-url: ${REALT_BASE_URL:https://realt.by}
    source-id: ${REALT_SOURCE_ID:REALT}
    region-code: ${REALT_REGION_CODE:BY}
    listings-path: ${REALT_LISTINGS_PATH:/rent/flat-for-long/}
    object-path-prefix: ${REALT_OBJECT_PATH_PREFIX:/rent-flat-for-long/object/}
```

### Resilience4j config

```yaml
resilience4j:
  ratelimiter:
    instances:
      connector-realt:
        limit-for-period: 1
        limit-refresh-period: 2s
        timeout-duration: 5s
  retry:
    instances:
      connector-realt:
        max-attempts: 3
        wait-duration: 2s
        enable-exponential-backoff: true
        exponential-backoff-multiplier: 2
  circuitbreaker:
    instances:
      connector-realt:
        sliding-window-type: COUNT_BASED
        sliding-window-size: 5
        minimum-number-of-calls: 5
        failure-rate-threshold: 100
        wait-duration-in-open-state: 60s
```

**Aspect order:** `@RateLimiter` (outermost) → `@CircuitBreaker` → `@Retry` (innermost with fallback).

- **Rate limiter:** 1 request per 2 seconds.
- **Retry:** 3 attempts with exponential backoff 2s → 4s → 8s.
- **Circuit breaker:** opens after 5 consecutive failures; stays open 60s.
- **Fallback:** `fetchFallback(Exception e)` — returns `List.of()`.

### HTTP client (`RealtClientConfig`)

`RestClient` bean `"realtRestClient"` configured with:
- Base URL from `RealtProperties`
- `User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36`
- Connect timeout: **5 seconds**
- Read timeout: **10 seconds**

### RawListing field mapping

| `RawListing` field | JSON field (`__NEXT_DATA__`) | Notes |
|--------------------|------------------------------|-------|
| `externalId` | `code` | Integer → String |
| `title` | `title` → `headline` fallback | Stripped; final fallback: `"Квартира на Realt.by"` |
| `description` | — | Always `null` |
| `dealType` | — | Always `"RENT"` |
| `propertyType` | — | Always `"APARTMENT"` |
| `price` | `price` | USD amount; `price=0` → `isNegotiable=true`, `price=0` |
| `currency` | `priceCurrency` | ISO 4217: `840` → `"USD"`; `933` → `"BYN"` |
| `priceUsd` | — | Always `null`; `price` is already in USD — BYN equivalent filled by a future exchange-rate layer |
| `rooms` | `rooms` | nullable |
| `floorNumber` | `storey` | nullable |
| `floorsTotal` | `storeys` | nullable |
| `areaTotalM2` | `areaTotal` | nullable |
| `address` | `address` | nullable |
| `latitude` | — | Always `null` (not in `__NEXT_DATA__`) |
| `longitude` | — | Always `null` (not in `__NEXT_DATA__`) |
| `city` | `townName` | nullable |
| `sourceUrl` | Constructed | `baseUrl + objectPathPrefix + code + "/"` |
| `publishedAt` | `createdAt` | ISO-8601 with offset → `Instant`; `null` when absent |
| `photoUrls` | `images` | Array of CDN URLs; `List.of()` when field absent |
| `isOwner` | `companyUuid` | `null` when field missing; `true` when JSON null (private owner); `false` when UUID present (agency) |
| `priceUnit` | — | Always `null` |
| `isNegotiable` | — | `true` when `price=0`; `false` otherwise |

### Schedulers

**Package:** `com.flatio.integration.realt.scheduler`

#### RealtDeltaSyncJob

| Trigger | Schedule | Behaviour |
|---------|----------|-----------|
| `@Scheduled(fixedDelay)` | `${flatio.sync.realt.delta.interval-ms}` (default every 15 min) | Incremental sync — only listings newer than last successful run |

- Reads last successful `SyncRun` for source REALT from DB via `SyncRunService.findLastSuccessfulRunAt(sourceId)`
- If cursor found → `fetchDelta(since)` (DELTA mode); if no cursor (first run or prolonged outage) → `fetch()` (FULL mode)
- realt.by has no server-side date filter; delta is implemented client-side: pages are sorted newest-first, pagination stops on the first listing with `publishedAt < since`
- `CallNotPermittedException` → `log.warn`, not propagated

```yaml
flatio:
  sync:
    realt:
      delta:
        interval-ms: ${FLATIO_SYNC_REALT_DELTA_INTERVAL_MS:900000}
```

#### RealtFullSyncJob

| Trigger | Schedule | Behaviour |
|---------|----------|-----------|
| `@Scheduled` | `${flatio.sync.realt.full.cron}` (default `0 0 4 * * *`) | Daily full crawl — all pages |
| `@EventListener(ApplicationReadyEvent)` | On startup | Runs immediately if DB has 0 listings for source REALT |

- Empty `fetch()` response → deactivation skipped (prevents mass-deactivation on source downtime)
- `CallNotPermittedException` → `log.warn`, not propagated
- `SyncRunService.record()` always called on completion (SUCCESS or FAILURE)

```yaml
flatio:
  sync:
    realt:
      full:
        cron: ${FLATIO_SYNC_REALT_FULL_CRON:0 0 4 * * *}
```

### Error handling

- **HTTP 5xx / network failure:** propagates from `fetch()`, `@Retry` triggers; after 3 failures `fetchFallback` returns `List.of()`
- **HTTP 429 Too Many Requests:** `Retry-After` header is read, `ConnectorTransientException` is thrown to trigger retry
- **HTTP 4xx (non-429):** logged at ERROR, returns `List.of()` without retry
- **`__NEXT_DATA__` missing or unparseable:** `log.warn` / `log.error`, returns `List.of()` without retry
- **`__NEXT_DATA__` exceeds 5 MB:** `log.error` + returns `List.of()` — OOM guard before `objectMapper.readTree()`
- **Photo URLs:** filtered by `isSafeImageUrl()` — only `https://` scheme with non-empty host accepted; protects against SSRF via crafted CDN URLs
- **Log injection:** `code` field and `createdAt` strings sanitized with `.replaceAll("[\r\n\t]", "_")` before logging
- **Single broken listing** (e.g., `code: 0`, `price: 0`): skipped with `log.warn`, rest are processed

### Test coverage

File: `src/test/java/com/flatio/integration/realt/client/RealtConnectorTest.java`
Fixtures: `src/test/resources/fixtures/realt/`

| Test | Scenario |
|------|----------|
| `should_return_listings_when_valid_html_fixture_provided` | Happy path — 2 listings mapped |
| `should_return_source_id_and_region_from_properties` | No hard-coded values |
| `should_map_all_required_fields_when_valid_card_parsed` | Full field mapping including photos, rooms, floor, area, city, isOwner, publishedAt |
| `should_set_currency_to_usd_and_price_usd_to_null_when_price_currency_is_840` | USD pricing (priceCurrency=840): currency="USD", priceUsd=null |
| `should_parse_usd_price_with_null_price_usd_from_dedicated_fixture` | Full parse chain via `listing-with-usd-price.html` fixture |
| `should_return_empty_photo_list_when_listing_has_no_images` | Empty `images` array |
| `should_use_fallback_title_when_both_title_and_headline_are_null` | Title fallback |
| `should_return_empty_list_when_page_has_no_listing_objects` | Empty objects array |
| `should_return_empty_list_when_response_is_null` | Null body |
| `should_return_empty_list_when_response_is_blank_string` | Blank body |
| `should_skip_listing_without_price_and_return_valid_ones` | Per-listing error isolation (price=0) |
| `should_skip_card_without_external_id_and_return_valid_ones` | Per-listing error isolation (code=0) |
| `should_not_throw_exception_when_single_card_is_broken` | Exception isolation |
| `should_return_empty_list_when_html_has_no_next_data_script` | Missing `__NEXT_DATA__` |
| `should_return_empty_list_when_fallback_is_invoked_after_exhausted_retries` | Fallback safety |
| `should_not_throw_from_fallback_method` | Fallback never throws |
| `should_stop_pagination_when_no_next_page_link_in_html` | Pagination stop condition |
| `should_fetch_second_page_when_first_page_has_next_link` | Multi-page fetch |
| `should_throw_connector_transient_exception_when_429_received` | HTTP 429 |
| `should_return_empty_list_when_non_retryable_4xx_received` | HTTP 4xx (non-429) |
| `should_propagate_server_exception_when_5xx_received` | HTTP 5xx propagates |
| `should_return_default_retry_after_when_headers_are_null` | Retry-After: null headers |
| `should_return_default_retry_after_when_header_is_absent` | Retry-After: header absent |
| `should_return_parsed_retry_after_when_header_contains_valid_seconds` | Retry-After: valid value |
| `should_cap_retry_after_at_60_seconds_when_header_exceeds_maximum` | Retry-After: cap at 60s |
| `should_return_default_retry_after_when_header_is_not_numeric` | Retry-After: non-numeric |
| `should_return_null_is_owner_when_company_uuid_field_is_absent` | `companyUuid` MissingNode → `isOwner = null` |
| `should_return_false_is_owner_when_company_uuid_is_present` | `companyUuid` UUID string → `isOwner = false` |
| `should_filter_out_non_https_photo_urls` | SSRF guard: non-`https://` URLs excluded from `photoUrls` |
| `should_return_empty_list_when_next_data_exceeds_size_limit` | OOM guard: `__NEXT_DATA__` > 5 MB → `List.of()` |

---

## RealtSaleConnector

**Source:** Realt.by (SSR HTML with embedded `__NEXT_DATA__` JSON)  
**Region:** BY (Belarus)  
**Package:** `com.flatio.integration.realt.client`  
**Category:** Apartment sale (`dealType=SELL`, `propertyType=APARTMENT`)  
**Listings path:** `/sale/flats/` — **Object prefix:** `/sale-flat/object/`

Shares `"realtSaleRestClient"` bean and `connector-realt-sale` Resilience4j (rate limiter 1 req/2s, retry × 3, circuit breaker).  
Uses `RealtHtmlParser` with `RealtPageContext(dealType=SELL, propertyType=APARTMENT, fallbackTitle="Квартира на Realt.by")`.

**Schedulers:** `RealtSaleDeltaSyncJob` (every 15 min) + `RealtSaleFullSyncJob` (daily 05:00, startup trigger).

---

## RealtRoomConnector

**Source:** Realt.by  
**Region:** BY (Belarus)  
**Package:** `com.flatio.integration.realt.client`  
**Category:** Room rent (`dealType=RENT`, `propertyType=ROOM`)  
**Listings path:** `/rent/room-for-long/` — **Object prefix:** `/rent-room-for-long/object/`

Shares `"realtRestClient"` bean and `connector-realt` Resilience4j (same instance as `RealtConnector`; same origin server).  
Uses `RealtHtmlParser` with `RealtPageContext(dealType=RENT, propertyType=ROOM, fallbackTitle="Комната на Realt.by")`.

**Schedulers:** `RealtRoomDeltaSyncJob` (every 5 min) + `RealtRoomFullSyncJob` (daily 06:00, startup trigger).

---

## RealtRoomSaleConnector

**Source:** Realt.by  
**Region:** BY (Belarus)  
**Package:** `com.flatio.integration.realt.client`  
**Category:** Room sale (`dealType=SELL`, `propertyType=ROOM`)  
**Listings path:** `/sale/rooms/` — **Object prefix:** `/sale-rooms/object/`

> ⚠️ Object prefix was corrected from `/sale-room/object/` to `/sale-rooms/object/` (PR #285). Flyway V40 backfills existing records. Configurable via env var `REALT_ROOM_SALE_OBJECT_PATH_PREFIX`.

Shares `"realtSaleRestClient"` bean and `connector-realt-sale` Resilience4j.  
Uses `RealtHtmlParser` with `RealtPageContext(dealType=SELL, propertyType=ROOM, fallbackTitle="Комната на Realt.by")`.

**Schedulers:** `RealtRoomSaleDeltaSyncJob` (every 5 min) + `RealtRoomSaleFullSyncJob` (daily 07:00, startup trigger).

---

## RealtHouseSaleConnector

**Source:** Realt.by  
**Region:** BY (Belarus)  
**Package:** `com.flatio.integration.realt.client`  
**Category:** House/cottage sale (`dealType=SELL`, `propertyType=HOUSE`)  
**Listings path:** `/sale/cottages/` — **Object prefix:** `/sale-cottages/object/`

> ⚠️ The path was changed from `/sale/houses/` to `/sale/cottages/` (PR #280) after realt.by restructured its URL scheme. Both paths are configurable via env vars `REALT_HOUSE_SALE_LISTINGS_PATH` / `REALT_HOUSE_SALE_OBJECT_PATH_PREFIX`.

Shares `"realtSaleRestClient"` bean and `connector-realt-sale` Resilience4j.  
Uses `RealtHtmlParser` with `RealtPageContext(dealType=SELL, propertyType=HOUSE, fallbackTitle="Дом на Realt.by")`.

**Schedulers:** `RealtHouseSaleDeltaSyncJob` (every 5 min) + `RealtHouseSaleFullSyncJob` (daily 08:00, startup trigger).

**Error handling — 404:** When the source URL returns 404 (site restructure), both `fetchAllInternal` and `fetchDelta` catch `HttpClientErrorException`, log at ERROR, and return an empty list. No exception propagated.

---

## KufarConnector (and variants)

**Source:** Kufar.by REST JSON API  
**Region:** BY (Belarus)  
**Package:** `com.flatio.integration.kufar.client`  
**Endpoint:** `GET {baseUrl}{searchPath}?cat={categoryCode}&typ={dealType}&lang={lang}&size={pageSize}[&cursor={token}]`

Six connectors share the same `KufarApiClient` service:

| Connector class | `cat` | `typ` | `dealType` | `propertyType` |
|-----------------|-------|-------|------------|----------------|
| `KufarApartmentRentConnector` | apartments rent code | `let` | `RENT` | `APARTMENT` |
| `KufarApartmentSaleConnector` | apartments sale code | `buy` | `SELL` | `APARTMENT` |
| `KufarRoomRentConnector` | rooms rent code | `let` | `RENT` | `ROOM` |
| `KufarRoomSaleConnector` | rooms sale code | `buy` | `SELL` | `ROOM` |
| `KufarHouseSaleConnector` | houses sale code | `buy` | `SELL` | `HOUSE` |
| `KufarCommercialConnector` | commercial code | `buy` | `SELL` | `COMMERCIAL` |

### How data is extracted

Kufar returns a JSON response containing an `ads` array and cursor-based pagination (`pagination.pages[]`). Pagination continues as long as a page link with `label="next"` exists and `MAX_PAGES=100` is not exceeded.

Property attributes (rooms, floor, etc.) are encoded as a list of key-value records in `ad_parameters[]` with machine key `p`. Extraction via `parseIntParam()` / `parseStringParam()` / `parseBigDecimalParam()`.

Prices are stored in BYN kopecks (`price_byn` field) — divided by 100 before persisting.

### Configuration (`application.yml`)

```yaml
connector:
  kufar:
    base-url: ${KUFAR_BASE_URL:https://www.kufar.by}
    search-path: /v1/search/classified-listings
    photo-cdn-base-url: ${KUFAR_PHOTO_CDN_BASE_URL:https://rms.kufar.by/v1/gallery/adim1}
    lang: ru
    page-size: 30
    apartment-rent:
      source-id: ${KUFAR_APT_RENT_SOURCE_ID:KUFAR_APT_RENT}
      category-code: ${KUFAR_APT_RENT_CAT:...}
      deal-type: let
```

Category codes are configurable via env variables and are set per connector.

### Resilience4j config

Shared `connector-kufar` instance:
- **Rate limiter:** 1 request/second
- **Retry:** 3 attempts with exponential backoff 2s → 4s → 8s
- **Circuit breaker:** opens after 5 consecutive failures; stays open 60s

### RawListing field mapping

| `RawListing` field | Kufar JSON field / `ad_parameters` key | Notes |
|--------------------|----------------------------------------|-------|
| `externalId` | `ad_id` | Long → String |
| `title` | `subject` | Fallback: connector-specific label (e.g., "Квартира на Kufar.by") |
| `description` | `body` | nullable |
| `dealType` | — | From caller (`RENT` / `SELL`) |
| `propertyType` | — | From caller (`APARTMENT` / `ROOM` / `HOUSE`) |
| `price` | `price_byn` ÷ 100 | BYN price; `0` when `isNegotiable=true` |
| `currency` | — | Always `"BYN"` |
| `priceUsd` | — | Always `null` |
| `priceByn` | — | Always `null` (same as `price`) |
| `rooms` | `ad_parameters[p="rooms"]` | Integer; `null` when absent or non-numeric |
| `floorNumber` | `ad_parameters[p="floor"]` | Integer; nullable |
| `floorsTotal` | `ad_parameters[p="re_number_floors"]` | Integer; nullable |
| `areaTotalM2` | `ad_parameters[p="size"]` | BigDecimal; nullable |
| `address` | `ad_parameters[p="address"].vl` | Prefers `vl` (human-readable), falls back to `v`; nullable |
| `latitude` | — | Always `null` (not in API response; Nominatim fills later) |
| `longitude` | — | Always `null` |
| `city` | — | Always `null` (city is part of address string) |
| `sourceUrl` | `ad_link` | Full URL to listing page |
| `publishedAt` | `list_time` | ISO-8601 with offset → `Instant`; `null` when absent or unparseable |
| `photoUrls` | `images[].path` | Prefixed with `photo-cdn-base-url`; `List.of()` when absent |
| `isOwner` | `account.type="private"` / `company_ad` | `true` when private account; `false` when agency; `null` when both absent |
| `priceUnit` | — | Always `null` |
| `isNegotiable` | — | `true` when `price_byn == null || price_byn == 0`; `false` otherwise |

### Error handling

- **Single broken listing:** `safeAdd()` catches any exception, logs `WARN "Skipping broken Kufar listing: adId=..."`, continues processing remaining ads
- **Empty response / null `ads`:** `fetchAll()` / `fetchDelta()` returns `List.of()` without retry
- **Network/HTTP errors:** propagate to Resilience4j at the calling connector level; fallback returns `List.of()`
- **Negotiable price:** `price_byn == null || price_byn == 0` → `isNegotiable=true`, `price=0`; listing is returned (not skipped)

### Test coverage

File: `src/test/java/com/flatio/integration/kufar/client/KufarApiClientTest.java`  
Fixtures: `src/test/resources/fixtures/kufar/`

| Test | Scenario |
|------|----------|
| `should_fetch_all_listings_when_valid_response_provided` | Happy path — field mapping |
| `should_stop_pagination_when_next_cursor_is_absent` | Pagination stop condition |
| `should_not_propagate_exception_when_single_listing_fails` | Per-listing exception isolation |
| `should_return_negotiable_listing_when_price_is_missing` | `price_byn=null` → `isNegotiable=true` |
| `should_return_negotiable_listing_from_fixture_when_price_missing` | Fixture-based negotiable test |
| `should_correctly_deserialize_valid_apartment_fixture` | Full field mapping via fixture including `address` |
| `should_return_empty_list_from_empty_fixture` | Empty response fixture |
| `should_skip_fetch_when_category_code_is_empty` | Empty `categoryCode` guard |

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
