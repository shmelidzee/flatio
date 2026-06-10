# Architecture — Flatio

Flatio is a real-estate aggregation platform. It collects listings from multiple sources,
provides search, subscriptions, and analytics.

---

## Technology Stack

| Component | Technology |
|-----------|-----------|
| Language | Java 21 (LTS) |
| Framework | Spring Boot 3.2.x |
| Database | PostgreSQL 16 |
| Migrations | Flyway |
| Build | Gradle 8.x (Kotlin DSL) |
| Tests | JUnit 5 + Mockito + Testcontainers |
| Mapping | MapStruct |
| Boilerplate | Lombok |
| API docs | springdoc-openapi (Swagger UI at `/swagger-ui.html`) |
| Logging | Logback — JSON on `prod` profile via `logstash-logback-encoder` |

---

## Layer Architecture

```
Controller → Service → Repository → Database
               ↓
            Domain (Entity)
               ↓
            DTO (Record)
```

- **Controller** — HTTP only: receive request, call service, return response. No business logic.
- **Service** — all business logic. `@Transactional` lives here only.
- **Repository** — Spring Data JPA queries.
- **Domain** — JPA Entities. No Jackson or validation annotations.
- **DTO** — Java Records. Data only, no logic.

---

## Package Structure

Root package: `com.flatio`

```
com.flatio
├── config/              # Spring configuration and beans
│   ├── OpenApiConfig    # springdoc/Swagger setup
│   └── SchedulerConfig  # @EnableScheduling — activates Spring scheduled task execution
├── domain/              # JPA entities (domain model)
│   ├── country/         # Country entity — ISO country reference data
│   ├── currency/        # Currency entity — currency reference data
│   ├── source/          # Source entity — listing source (site) registry
│   ├── listing/         # Core listing domain
│   │   ├── Listing      # Main JPA entity (27 fields, incl. dedup_hash, repost fields)
│   │   ├── PriceHistory # Append-only price history entity
│   │   ├── DealType     # Enum: RENT | RENT_DAILY | SELL
│   │   ├── ListingStatus # Enum: ACTIVE | INACTIVE | REPOSTED
│   │   └── PriceUnit    # Enum: PER_MONTH | PER_DAY
│   └── user/            # User authentication domain
│       ├── User         # User entity: displayName, email, active, role
│       ├── UserAuthProvider # Auth provider link: provider enum + externalId
│       ├── AuthProvider # Enum: TELEGRAM | GOOGLE | EMAIL
│       └── UserRole     # Enum: USER | ADMIN
├── repository/          # Spring Data JPA repositories
│   ├── CountryRepository
│   ├── CurrencyRepository
│   ├── SourceRepository
│   ├── ListingRepository      # findByExternalIdAndSourceId, findByDedupHashAndSourceNot, findByCountryCodeAndStatus, findPageByCountryCodeAndStatus, findFirstByDedupHashAndSourceAndExternalIdNotAndStatus
│   ├── PriceHistoryRepository # findByListingOrderByRecordedAtDesc
│   ├── UserRepository         # findByTelegramId, findByProviderAndExternalId
│   └── UserAuthProviderRepository
├── service/             # Business logic
│   ├── DedupHashService           # interface — SHA-256 dedup hash computation
│   ├── DedupHashServiceImpl       # SHA-256 with normalisation (lowercase, trim, collapse whitespace)
│   ├── ListingIngestionService    # interface — ingest(RawListing, Source) + ingestBatch(...)
│   ├── ListingIngestionServiceImpl # upsert: CREATE or UPDATE path + PriceHistory; per-item @Transactional
│   ├── ListingService             # interface (listing queries and management — M1.4)
│   └── ListingServiceImpl         # placeholder (M1.4)
├── web/                 # REST controllers, DTOs, mappers
│   ├── controller/      # (M1.4 — to be added)
│   ├── dto/             # ListingResponse (19 fields + @Schema), ListingSummaryResponse (11 fields)
│   └── mapper/          # ListingMapper — MapStruct Listing ↔ ListingResponse / ListingSummaryResponse
├── integration/         # External source integrations
│   ├── core/            # ListingConnector interface, RawListing record, RawListingMapper, ConnectorTransientException
│   └── onliner/         # Onliner integration
│       ├── client/      # OnlinerConnector — implements ListingConnector
│       ├── config/      # OnlinerClientConfig (@Bean onlinerRestClient) + OnlinerProperties
│       ├── dto/         # OnlinerSearchResponse, OnlinerApartment, OnlinerPrice, OnlinerLocation, OnlinerArea, OnlinerPage
│       └── scheduler/   # OnlinerDeltaSyncJob (every 10 min), OnlinerFullSyncJob (daily 02:00)
├── telegram/            # Telegram Bot
│   ├── handler/         # FlatioBot — TelegramLongPollingBot Spring bean; SearchResultSender — отправка карточек
│   ├── command/         # StartCommandHandler — /start command
│   ├── callback/        # FilterCallbackHandler — обработка callback FILTER:*
│   ├── keyboard/        # FilterKeyboardFactory — InlineKeyboardMarkup для шагов wizard
│   ├── state/           # FSM и пользовательские сценарии: FilterStep (enum шагов), SearchFilterState (in-memory состояние), SearchFilterWizard (управление переходами)
│   ├── formatter/       # ListingFormatter — форматирование ListingSummaryResponse в HTML-caption и InlineKeyboardMarkup
│   └── config/          # BotConfig (@ConfigurationProperties) + BotConfiguration (@EnableConfigurationProperties)
├── scheduler/           # Generic scheduled tasks (currently empty; source-specific jobs live in integration/)
├── security/            # JWT authentication
│   ├── JwtService       # Token generation and validation (HMAC-SHA256)
│   ├── JwtAuthenticationFilter # OncePerRequestFilter — extracts Bearer token, populates SecurityContext
│   ├── JwtProperties    # @ConfigurationProperties(prefix = "flatio.jwt"): secretKey, accessTokenExpiry
│   └── SecurityConfig   # Spring Security filter chain: stateless, JWT-based, anyRequest().denyAll() (fail-closed)
└── util/                # Utilities
```

---

## Domain Model

### Listing (core entity)

Table: `listings`

| Field | Type | Nullable | Notes |
|-------|------|----------|-------|
| `id` | `BIGSERIAL` | NOT NULL | Primary key |
| `external_id` | `VARCHAR(255)` | NOT NULL | ID from the source platform |
| `source_id` | `BIGINT` FK | NOT NULL | Reference to `source` |
| `title` | `VARCHAR(500)` | NOT NULL | |
| `description` | `TEXT` | nullable | |
| `deal_type` | `VARCHAR(10)` | NOT NULL | `RENT` or `SELL` |
| `property_type` | `VARCHAR(50)` | nullable | |
| `price` | `NUMERIC(15,2)` | NOT NULL | In listing currency |
| `currency_id` | `BIGINT` FK | NOT NULL | Reference to `currency` |
| `price_usd` | `NUMERIC(15,2)` | nullable | Normalised USD price |
| `rooms` | `INTEGER` | nullable | |
| `floor_number` | `INTEGER` | nullable | |
| `floors_total` | `INTEGER` | nullable | |
| `area_total_m2` | `NUMERIC(8,2)` | nullable | |
| `area_living_m2` | `NUMERIC(8,2)` | nullable | |
| `area_kitchen_m2` | `NUMERIC(8,2)` | nullable | |
| `address` | `VARCHAR(500)` | nullable | |
| `latitude` | `NUMERIC(10,7)` | nullable | |
| `longitude` | `NUMERIC(10,7)` | nullable | |
| `country_id` | `BIGINT` FK | NOT NULL | Reference to `country` |
| `city` | `VARCHAR(100)` | nullable | |
| `district` | `VARCHAR(100)` | nullable | |
| `price_unit` | `VARCHAR(10)` | nullable | `PER_MONTH` or `PER_DAY`; null for `SELL`; derived from `deal_type` on ingest |
| `status` | `VARCHAR(10)` | NOT NULL | `ACTIVE`, `INACTIVE`, or `REPOSTED` |
| `source_url` | `VARCHAR(1000)` | NOT NULL | |
| `dedup_hash` | `VARCHAR(64)` | nullable | SHA-256 of normalised (address, rooms, areaTotalM2, dealType) |
| `reposted_from` | `BIGINT` FK | nullable | FK to `listings.id` — original listing this is a repost of; `ON DELETE SET NULL` |
| `last_reposted_at` | `TIMESTAMPTZ` | nullable | Set on the original listing each time a new repost is detected |
| `published_at` | `TIMESTAMPTZ` | nullable | |
| `created_at` | `TIMESTAMPTZ` | NOT NULL | Auto-set on insert |
| `updated_at` | `TIMESTAMPTZ` | NOT NULL | Auto-set on update |

Unique constraint: `(external_id, source_id)` — used for deduplication during parsing.

Indexes: `source_id`, `status`, `deal_type`, `price`, `published_at`, `dedup_hash`,
`(dedup_hash, source_id) WHERE dedup_hash IS NOT NULL` (partial composite — repost detection).

### PriceHistory (append-only)

Table: `price_history`

| Field | Type | Nullable | Notes |
|-------|------|----------|-------|
| `id` | `BIGSERIAL` | NOT NULL | Primary key |
| `listing_id` | `BIGINT` FK | NOT NULL | Reference to `listings` |
| `price` | `NUMERIC(15,2)` | NOT NULL | Price at the recorded moment |
| `currency_id` | `BIGINT` FK | NOT NULL | Reference to `currency` |
| `recorded_at` | `TIMESTAMPTZ` | NOT NULL | Auto-set on insert, never updated |

Index: `(listing_id, recorded_at DESC)` — optimised for latest-price-first queries.
Records are inserted only, never updated or deleted. Used to track price history over time.

### Reference entities

| Entity | Table | Key field | Purpose |
|--------|-------|-----------|---------|
| `Country` | `country` | `code` (ISO, e.g. `BY`) | Market / region grouping |
| `Currency` | `currency` | `code` (e.g. `BYN`, `USD`) | Price currency |
| `Source` | `source` | `code` (e.g. `onliner`) | Source platform registry |

---

## Database Migrations (Flyway)

| Version | File | Description |
|---------|------|-------------|
| V2 | `V2__create_reference_tables.sql` | DDL for `country`, `currency`, `source` |
| V3 | `V3__seed_reference_data.sql` | Seed data for reference tables |
| V4 | `V4__add_reference_tables_indexes.sql` | Indexes on reference tables |
| V5 | `V5__create_listings.sql` | DDL for `listings` table |
| V6 | `V6__add_listings_indexes.sql` | 5 indexes on `listings` |
| V7 | `V7__create_users.sql` | DDL for `users` and `user_auth_provider` tables |
| V8 | `V8__add_user_auth_provider_indexes.sql` | Index on `user_auth_provider.user_id` |
| V9 | `V9__create_price_history.sql` | DDL for `price_history` table + composite index |
| V10 | `V10__add_listing_dedup_hash.sql` | `dedup_hash VARCHAR(64)` column + index on `listings` |
| V11 | `V11__add_users_last_seen.sql` | `last_seen TIMESTAMPTZ` column on `users` |
| V12 | `V12__extend_deal_type_column.sql` | Extend `deal_type` column |
| V13 | `V13__add_is_owner_to_listings.sql` | `is_owner BOOLEAN` column on `listings` |
| V14 | `V14__add_listing_missed_syncs_count.sql` | `missed_syncs_count INTEGER` column on `listings` |
| V15 | `V15__add_listings_search_vector.sql` | FTS `search_vector TSVECTOR` column + GIN index on `listings` |
| V16 | `V16__add_user_role.sql` | `role VARCHAR(20) NOT NULL DEFAULT 'USER'` column on `users` |
| V17 | `V17__add_price_unit_to_listings.sql` | `price_unit VARCHAR(10)` column on `listings` |
| V18 | `V18__add_listing_repost_fields.sql` | `reposted_from BIGINT` + `last_reposted_at TIMESTAMPTZ` columns on `listings`; FK `fk_listing_reposted_from` with `ON DELETE SET NULL` |
| V19 | `V19__add_index_listings_dedup_hash_source.sql` | Partial composite index `idx_listings_dedup_hash_source` on `(dedup_hash, source_id) WHERE dedup_hash IS NOT NULL` |

Migration files are located in `src/main/resources/db/migration/`.
Never edit an existing migration file — always create a new one.

---

## Ingestion Pipeline

The ingestion pipeline converts `RawListing` objects (produced by connectors) into persisted `Listing`
entities. The flow is orchestrated by `ListingIngestionService`:

```
ListingConnector.fetch()
  → List<RawListing>
  → ListingIngestionService.ingestBatch(raws, source)
      for each raw:
        ListingIngestionService.ingest(raw, source)   ← @Transactional per item
          ├── findByExternalIdAndSourceId → not found → CREATE path
          │     RawListingMapper.toEntity(raw)
          │     set source, currency, country, status=ACTIVE, dedupHash
          │     PriceHistoryRepository.save(initial record)
          │     ListingRepository.save(listing)
          └── found → UPDATE path
                RawListingMapper.updateEntity(raw, existing)
                refresh status, dedupHash
                if price changed → PriceHistoryRepository.save(new record)
                ListingRepository.save(existing)
```

Key design decisions:
- `ingestBatch` runs with `@Propagation.NOT_SUPPORTED` — each item gets its own transaction via `self.ingest()`.
  A failure in one item rolls back only that item, the batch continues.
- Self-proxy via `@Lazy @Autowired ListingIngestionService self` — required for Spring AOP to apply
  `@Transactional` on `ingest()` when called from within the same bean.
- `DedupHashService` is injected into `ListingIngestionServiceImpl` (not `ListingService`) to avoid
  cross-service coupling at the wrong layer.

---

## Connector Contract

Each data-source connector must implement `com.flatio.integration.core.ListingConnector`:

```java
public interface ListingConnector {
    String getSourceId();            // unique source identifier (e.g. "ONLINER", "REALT")
    String getSupportedRegionCode(); // ISO region code injected from config — never hard-coded
    List<RawListing> fetch();        // main fetch method
}
```

Raw listing data is transferred via `com.flatio.integration.core.RawListing` (Java Record, 18 fields).
Optional fields are nullable; the service layer is responsible for validation and mapping to domain types.

Requirements for all connector implementations:
- Rate limiting via Resilience4j (`@RateLimiter`)
- Retry with exponential backoff (`@Retry(fallbackMethod = "...Fallback")`, 3 attempts: 2s → 4s → 8s);
  the annotated method must **not** catch exceptions internally — they must propagate for retry to trigger
- Fallback method returns empty list — graceful degradation after exhausted retries
- HTTP timeouts configured in `OnlinerClientConfig` (connect: 5s, read: 10s) to prevent thread blocking
- Per-listing error isolation — a broken listing must not abort the full fetch
- No raw HTML stored — return only structured `RawListing` data
- Realistic `User-Agent` header — not the default OkHttp/RestClient value

### Implemented connectors

| Connector | Source | Region | Package |
|-----------|--------|--------|---------|
| `OnlinerConnector` | Onliner API (JSON) | BY | `com.flatio.integration.onliner.client` |

---

## Multi-Region Design

Region is always passed as a parameter — never hard-coded. Every architectural decision
must answer: "Will this work for a market other than Belarus?" If the answer is "no"
or "unknown", the constraint is documented and escalated to the product owner.

---

## Security

JWT-based stateless authentication via Spring Security. Sessions are disabled.

### Access rules

| Path | Access |
|------|--------|
| `/api/v1/admin/**` | `ADMIN` role required |
| `/api/v1/**` | Any authenticated user |
| `/swagger-ui/**`, `/v3/api-docs/**`, `/actuator/health/**`, `/actuator/info` | Public |
| Everything else | Denied — HTTP 403 (fail-closed) |

`anyRequest().denyAll()` ensures no route is accidentally left open.

### CORS

Allowed origins are configured via `flatio.cors.allowed-origins`
(environment variable: `CORS_ALLOWED_ORIGINS`). Accepts a comma-separated list.
Defaults to `http://localhost:3000`. Wildcard `*` is never accepted.

### Environment variables

| Variable | Description | Default |
|----------|-------------|---------|
| `JWT_SECRET_KEY` | HMAC-SHA256 signing key — **required**, no default | — |
| `JWT_ACCESS_TOKEN_EXPIRY` | Access token lifetime in seconds | `3600` |
| `CORS_ALLOWED_ORIGINS` | Comma-separated list of allowed CORS origins | `http://localhost:3000` |

---

## REST API Conventions

- Base path: `/api/v1/`
- Version in URL, always
- Swagger UI: `/swagger-ui.html`
- OpenAPI spec: `/v3/api-docs`

See `docs/api.md` for endpoint reference.
