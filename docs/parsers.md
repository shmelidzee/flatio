# Connectors (Parsers) — Flatio

Connectors fetch raw listing data from external sources and convert it to `RawListing` records.
Each connector is an independent Spring `@Service` implementing `com.flatio.connector.core.ListingConnector`.

---

## Architecture

```
Scheduler
  ↓
ListingConnector.fetch()          ← @RateLimiter + @Retry(fallbackMethod = "...Fallback")
  ↓
List<RawListing>                  ← structured data, never raw HTML
  ↓
ListingService (dedup + persist)
```

All connectors share the same contract — see `docs/architecture.md`, section **Connector Contract**.

---

## OnlinerConnector

**Source:** Onliner.by REST API (JSON)  
**Region:** BY (Belarus)  
**Package:** `com.flatio.connector.onliner`  
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
```

Rate: 1 request/second. Retry: 3 attempts, backoff 2s → 4s → 8s.  
After all retries fail, `fetchFallback(Exception e)` is called and returns an empty list.

### HTTP client (`ConnectorConfig`)

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
| `propertyType` | — | Always `"APARTMENT"` |
| `price` | `price.amount` | Parsed as `BigDecimal` |
| `currency` | `price.currency` | `"USD"` / `"BYN"` as-is |
| `rooms` | `rooms_count` | nullable |
| `floorNumber` | `floor` | nullable |
| `floorsTotal` | `number_of_floors` | nullable |
| `areaTotalM2` | `area.total` | nullable |
| `address` | `location.address` | nullable |
| `latitude` | `location.latitude` | nullable |
| `longitude` | `location.longitude` | nullable |
| `city` | — | Always `null` (no separate city field in Onliner API) |
| `sourceUrl` | `url` | |
| `publishedAt` | `created_at` | ISO-8601 string → `Instant`; `null` on parse failure |
| `photoUrls` | `photo` | Single photo URL wrapped in `List.of()`; `List.of()` when null |

### Error handling

- **Network failure / HTTP error:** exception propagates from `fetch()`, `@Retry` triggers;
  after 3 failed attempts `fetchFallback` returns `List.of()`
- **Single broken listing** (e.g., invalid `price.amount`): skipped with `log.warn`, rest are processed
- **Null / empty API response:** `fetch()` returns `List.of()` without retry

### Test coverage

File: `src/test/java/com/flatio/connector/onliner/OnlinerConnectorTest.java`  
Fixtures: `src/test/resources/fixtures/onliner/`

| Test | Scenario |
|------|----------|
| `should_return_listings_when_valid_response_provided` | Happy path — 2 listings mapped |
| `should_return_source_id_and_region_from_properties` | No hard-coded values |
| `should_return_empty_list_when_apartments_list_is_empty` | Empty API response |
| `should_return_empty_list_when_api_returns_null_response` | Null body from API |
| `should_return_empty_list_when_fallback_is_invoked_after_exhausted_retries` | Fallback after retry exhaustion |
| `should_not_throw_from_fallback_method` | Fallback is always safe |
| `should_skip_listing_with_null_price_amount_and_return_others` | Per-listing error isolation |
| `should_map_all_required_fields_correctly` | Full field mapping check |
| `should_return_fallback_title_when_all_title_fields_are_null` | Title fallback logic |
| `should_return_empty_photo_list_when_photo_is_null` | Null photo handling |

---

## Adding a New Connector

1. Create package `com.flatio.connector.{source}`
2. Implement `ListingConnector` — `getSourceId()`, `getSupportedRegionCode()`, `fetch()`
3. Create `{Source}Properties` record with `@ConfigurationProperties(prefix = "connector.{source}")`
4. Add `@Bean("{source}RestClient")` in `ConnectorConfig` with timeouts and User-Agent
5. Add Resilience4j config in `application.yml` under `resilience4j.ratelimiter/retry`
6. Add connector env-variables section in `application.yml` under `connector.{source}`
7. Write unit tests covering all mandatory scenarios (see `testing-standards.md`)
8. Add connector row to the table in `docs/parsers.md`
