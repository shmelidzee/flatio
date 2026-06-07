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
│   └── OpenApiConfig    # springdoc/Swagger setup
├── domain/              # JPA entities (domain model)
│   ├── country/         # Country entity — ISO country reference data
│   ├── currency/        # Currency entity — currency reference data
│   ├── source/          # Source entity — listing source (site) registry
│   ├── listing/         # Core listing domain
│   │   ├── Listing      # Main JPA entity (24 fields, incl. dedup_hash)
│   │   ├── PriceHistory # Append-only price history entity
│   │   ├── DealType     # Enum: RENT | SELL
│   │   └── ListingStatus # Enum: ACTIVE | INACTIVE
│   └── user/            # User authentication domain
│       ├── User         # User entity: displayName, email, active
│       ├── UserAuthProvider # Auth provider link: provider enum + externalId
│       └── AuthProvider # Enum: TELEGRAM | GOOGLE | EMAIL
├── repository/          # Spring Data JPA repositories
│   ├── CountryRepository
│   ├── CurrencyRepository
│   ├── SourceRepository
│   ├── ListingRepository      # findByExternalIdAndSourceId, findByDedupHashAndSourceNot, findByCountryCodeAndStatus
│   ├── PriceHistoryRepository # findByListingOrderByRecordedAtDesc
│   ├── UserRepository         # findByTelegramId, findByProviderAndExternalId
│   └── UserAuthProviderRepository
├── service/             # Business logic
│   ├── ListingService         # interface
│   └── ListingServiceImpl     # computeDedupHash: SHA-256 of (address|rooms|areaTotalM2|dealType)
├── web/                 # REST controllers, DTOs, mappers (to be added)
│   ├── controller/
│   ├── dto/
│   └── mapper/
├── connector/           # Source data connectors (to be added)
│   ├── core/            # ListingConnector interface + RawListing
│   └── {source}/        # Per-source implementations
├── bot/                 # Telegram Bot (to be added)
├── scheduler/           # Scheduled tasks (to be added)
├── security/            # Auth / JWT (to be added)
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
| `status` | `VARCHAR(10)` | NOT NULL | Default `ACTIVE` |
| `source_url` | `VARCHAR(1000)` | NOT NULL | |
| `dedup_hash` | `VARCHAR(64)` | nullable | SHA-256 of normalised (address, rooms, areaTotalM2, dealType) |
| `published_at` | `TIMESTAMPTZ` | nullable | |
| `created_at` | `TIMESTAMPTZ` | NOT NULL | Auto-set on insert |
| `updated_at` | `TIMESTAMPTZ` | NOT NULL | Auto-set on update |

Unique constraint: `(external_id, source_id)` — used for deduplication during parsing.

Indexes: `source_id`, `status`, `deal_type`, `price`, `published_at`, `dedup_hash`.

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

Migration files are located in `src/main/resources/db/migration/`.
Never edit an existing migration file — always create a new one.

---

## Connector Contract

Each data-source connector must implement:

```java
public interface ListingConnector {
    String getSourceId();            // unique source identifier
    RegionCode getSupportedRegion(); // region this connector covers
    List<RawListing> fetch();        // main fetch method
}
```

Requirements: rate limiting (Resilience4j), retry with exponential backoff,
per-listing error isolation, no raw HTML stored, realistic User-Agent header.

---

## Multi-Region Design

Region is always passed as a parameter — never hard-coded. Every architectural decision
must answer: "Will this work for a market other than Belarus?" If the answer is "no"
or "unknown", the constraint is documented and escalated to the product owner.

---

## REST API Conventions

- Base path: `/api/v1/`
- Version in URL, always
- Swagger UI: `/swagger-ui.html`
- OpenAPI spec: `/v3/api-docs`

See `docs/api.md` for endpoint reference (to be created).
