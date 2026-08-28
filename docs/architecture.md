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
│   ├── OpenApiConfig           # springdoc/Swagger setup
│   ├── SchedulerConfig         # @EnableScheduling — activates Spring scheduled task execution
│   └── TelegramExecutorConfig  # ThreadPoolTaskExecutor for concurrent Telegram update dispatch (core=10, max=20, queue=100)
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
│   ├── controller/      # ListingController (M1.4); AuthController — POST /api/v1/auth/telegram (#217)
│   ├── dto/             # ListingResponse (19 fields + @Schema), ListingSummaryResponse (11 fields); TelegramAuthRequest, AuthResponse
│   └── mapper/          # ListingMapper — MapStruct Listing ↔ ListingResponse / ListingSummaryResponse
├── integration/         # External source integrations
│   ├── core/            # ListingConnector interface, RawListing record, RawListingMapper, ConnectorTransientException
│   └── onliner/         # Onliner integration
│       ├── client/      # OnlinerConnector — implements ListingConnector
│       ├── config/      # OnlinerClientConfig (@Bean onlinerRestClient) + OnlinerProperties
│       ├── dto/         # OnlinerSearchResponse, OnlinerApartment, OnlinerPrice, OnlinerLocation, OnlinerArea, OnlinerPage
│       └── scheduler/   # OnlinerDeltaSyncJob (every 10 min), OnlinerFullSyncJob (daily 02:00)
├── telegram/            # Telegram Bot
│   ├── handler/         # FlatioBot — диспетчер апдейтов (глобальный try-catch, параллельный dispatch через TelegramExecutorConfig); SearchResultSender — отправка карточек с валидацией URL и photo/text fallback
│   ├── command/         # StartCommandHandler — /start (+ deep link `/start listing_<id>` → карточка объявления по ссылке, issue #418), приветственное меню строится через MainMenuKeyboardFactory (issue #456); HelpCommandHandler — /help и action:help callback
│   ├── callback/        # FilterCallbackHandler — обработка callback FILTER:*; FavoritesCallbackHandler/SubscriptionsCallbackHandler/BlacklistCallbackHandler — обработка action:favorites/action:subscriptions/action:blacklist из главного меню, переиспользуют FavoriteService/SubscriptionService/BlacklistService (issue #456)
│   ├── keyboard/        # FilterKeyboardFactory — InlineKeyboardMarkup для шагов wizard; MainMenuKeyboardFactory — клавиатура приветственного меню (Искать/Помощь/⭐ Избранное/🔔 Мои подписки/🚫 Чёрный список) + кнопка «🏠 Главное меню» для возврата из секций (issue #456)
│   ├── state/           # FSM и пользовательские сценарии: FilterStep (enum шагов), SearchFilterState (in-memory состояние), SearchFilterWizard (управление переходами); SearchSession (пагинация результатов, TTL 30 мин)
│   ├── formatter/       # ListingFormatter — форматирование ListingSummaryResponse (поиск) и ListingResponse (deep link, issue #418) в HTML-caption и InlineKeyboardMarkup; имя источника и флаг «адрес не указан» читаются из SourceDisplayProperties (issue #423), не хардкожены
│   └── config/          # BotConfig (@ConfigurationProperties) + BotConfiguration (@EnableConfigurationProperties); BotCommandsRegistrar (@PostConstruct, регистрирует /start, /search, /help); TelegramStartupValidator (@PostConstruct, валидирует токен и вебхук); SourceDisplayProperties (@ConfigurationProperties, telegram.source-display.sources — per-источник display name + address-unknown флаг, issue #423)
├── scheduler/           # Generic scheduled tasks (currently empty; source-specific jobs live in integration/)
├── security/            # JWT authentication
│   ├── JwtService       # Token generation and validation (HMAC-SHA256)
│   ├── JwtAuthenticationFilter # OncePerRequestFilter — extracts Bearer token, populates SecurityContext
│   ├── JwtProperties    # @ConfigurationProperties(prefix = "flatio.jwt"): secretKey, accessTokenExpiry
│   ├── TelegramInitDataValidator # validates Telegram WebApp initData (HMAC-SHA256, bot token as secret) — issue #217
│   ├── RateLimitFilter  # OncePerRequestFilter — per-caller rate limit on /api/v1/**: by client IP for /api/v1/auth/**, by JWT subject otherwise; dynamic RateLimiter per key via RateLimiterRegistry — issue #219. /api/v1/admin/** uses its own, more generous api-admin config instead of api-authenticated — issue #360
│   └── SecurityConfig   # Spring Security filter chain: stateless, JWT-based, /api/v1/auth/** permitAll (token issuance), anyRequest().denyAll() otherwise (fail-closed)
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
| `version` | `BIGINT` | NOT NULL | JPA `@Version` — optimistic lock (issue #367, `V52`) |

Unique constraint: `(external_id, source_id)` — used for deduplication during parsing.

Indexes: `source_id`, `status`, `deal_type`, `price`, `published_at`, `dedup_hash`,
`(dedup_hash, source_id) WHERE dedup_hash IS NOT NULL` (partial composite — repost detection).

**Optimistic locking (issue #367):** `Listing` is written from at least three independent paths —
admin moderation (`AdminListingServiceImpl`), source sync ingestion
(`ListingIngestionServiceImpl`), and geocoding (`GeocodingJob`) — with no coordination between
them. The `@Version` column detects a lost-update race instead of letting the last writer silently
overwrite another's change:
- `AdminListingServiceImpl.updateStatus`/`unlinkDuplicateGroup` flush immediately
  (`saveAndFlush`) so a version conflict surfaces inside the method, and translate it to
  `ListingConcurrentModificationException` → HTTP `409 Conflict` — an admin action loses a race
  outright rather than silently applying to stale data.
- `ListingIngestionServiceImpl.ingestBatch` instead retries the conflicting listing against the
  latest row version (same retry path already used for a concurrent-insert `DataIntegrityViolationException`,
  issue #366) — a background sync job's write should not fail a whole batch item over a
  transient race with another writer.
- `GeocodingJob` already isolates per-listing errors (issue #372); a version conflict there is
  simply skipped and logged, picked up again on the job's next run.

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

Raw listing data is transferred via `com.flatio.integration.core.RawListing` (Java Record, 23
fields). Optional fields are nullable; the service layer is responsible for validation and mapping
to domain types. Construct via `RawListing.builder()` (issue #422) — the canonical constructor's
positional arguments include several same-typed neighbors (`floorNumber`/`floorsTotal`,
`latitude`/`longitude`) where a transposed pair compiles silently; the builder's named setters
remove that risk at each connector's call site.

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
| `/admin`, `/admin/**` | Public — static admin SPA shell (see [Admin Interface](#admin-interface)), not the API |
| `/swagger-ui/**`, `/v3/api-docs/**`, `/actuator/health/**`, `/actuator/info` | Public |
| `/actuator/prometheus` | `ADMIN` role required — same as `/api/v1/admin/**` (issue #417, see [Observability](#observability-issue-417)) |
| `POST /<bot-token>` | Public — Telegram webhook (see below) |
| Everything else | Denied — HTTP 403 (fail-closed) |

`anyRequest().denyAll()` ensures no route is accidentally left open.

### JWT revocation (issue #365)

A JWT's `roles` claim is signed at issue time and normally stays valid for the token's whole
lifetime (access token: 1 hour) even if the user is deactivated or has their role changed a second
later. `JwtAuthenticationFilter` closes that gap by never trusting the token's own `roles` claim —
authorities are instead sourced from `UserStatusCache`, a 30-second-TTL, DB-backed cache keyed by
user id (`active` + `role` only, nothing more sensitive).

**Known limitation — up to 30 seconds of stale access.** `AdminUserServiceImpl.update()` evicts the
cache entry immediately after its transaction commits (`TransactionSynchronizationManager.afterCommit`,
not before — evicting pre-commit would let a concurrent request re-read the still-uncommitted row and
re-cache the stale state for a fresh TTL). Evicting on write closes the gap for that specific user
immediately in the common case, but any request already served from a cache entry populated in the
previous few seconds — or any user not explicitly evicted — still sees the old `active`/`role` for
up to `UserStatusCache.TTL_SECONDS` (30s). This is a deliberate trade-off: re-validating against the
database on every request would defeat the purpose of a stateless JWT. Do not treat deactivation or
a role downgrade as taking effect instantly when reasoning about incident response timing.

### Telegram webhook security (two layers)

The webhook path is `/<bot-token>` — Telegram's own recommended technique
(token-as-path makes the endpoint unguessable). Two additional layers protect it:

**Layer 1 — nginx access log suppression.**
`docker/nginx/nginx.conf` matches the webhook path with `~* "^/[0-9]+:[A-Za-z0-9_-]+$"` and sets
`access_log off` for that location. This prevents the bot token from appearing in plain text in
every access log line on each incoming Telegram update.

**Layer 2 — `X-Telegram-Bot-Api-Secret-Token` header check.**
When `TELEGRAM_WEBHOOK_SECRET_TOKEN` is set, `TelegramWebhookConfig` passes it to Telegram's
`setWebhook` API. Telegram then includes it as `X-Telegram-Bot-Api-Secret-Token` in every update.
`TelegramWebhookSecretFilter` validates this header before the bot handler runs — rejecting any
request that doesn't carry the expected value with HTTP 403. This means a leaked URL token alone
is not enough to forge an update.

The secret token is optional: if `TELEGRAM_WEBHOOK_SECRET_TOKEN` is not set, the filter passes
all requests through unchanged (backward-compatible). Setting it is strongly recommended in all
production deployments.

The webhook `SecurityFilterChain` is registered with `@Order(1)` in
`TelegramWebhookSecurityConfig` (`@Profile("!local")`) so it takes precedence over the main
chain for the webhook path. In the `local` profile, long-polling is used and the webhook
security config is inactive.

**Known limitation — the webhook route matches any single path segment (issue #361).**
`telegrambots-springboot-webhook-starter` registers the webhook handler as
`@PostMapping("/{botPath}")` — a wildcard on one path segment, not a literal route scoped to the
configured token. At the Spring MVC routing level this matches `POST` to *any* single-segment
path, including one that happens to collide with another route (e.g. `/admin`); the actual token
value is only checked inside the handler, not by the router. A `GET` request to such a colliding
path finds this mapping by path but not by method, and previously fell through
`GlobalExceptionHandler`'s generic exception branch as an opaque `500` instead of the correct
`405 Method Not Allowed` — fixed by an explicit `@ExceptionHandler(HttpRequestMethodNotSupportedException.class)`.
`GET /admin` specifically is additionally routed by `AdminSpaRedirectController`
(`@GetMapping("/admin")` → redirect to `/admin/`), so for that one path the collision no longer
surfaces at all: the literal `GET` mapping wins over the wildcard `POST`-only one before either
exception path is reached.

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
| `TELEGRAM_WEBHOOK_SECRET_TOKEN` | Second-layer secret sent by Telegram as `X-Telegram-Bot-Api-Secret-Token` | `""` (disabled) |
| `TELEGRAM_EXECUTOR_CORE_POOL_SIZE` | Core threads in Telegram update executor | `10` |
| `TELEGRAM_EXECUTOR_MAX_POOL_SIZE` | Max threads in Telegram update executor | `20` |
| `TELEGRAM_EXECUTOR_QUEUE_CAPACITY` | Task queue capacity before extra threads spawn | `100` |

---

## Admin Interface

**Decision (OQ-18, closed 2026-08-14):** the admin UI is a **separate SPA**, not Swagger-UI-only.

A design (screens: Дашборд, Объявления, Источники, Пользователи) was provided by the product
owner as a Claude Design artifact. It informed the shape of the admin REST API below and the
frontend scaffold (#320). Until the SPA has real screens wired to the admin endpoints, Swagger
UI (`/swagger-ui.html`) remains available as an interim way to exercise them.

### Admin endpoints (M1.6)

| Endpoint | Purpose |
|----------|---------|
| `GET /api/v1/admin/sources` | List sources with status, sync interval, last successful sync |
| `PATCH /api/v1/admin/sources/{sourceId}` | Enable/disable a source and/or change its sync interval |
| `GET /api/v1/admin/sync-runs` | Paginated sync run history, optional `sourceId` filter |
| `GET /api/v1/admin/sync-runs/latest` | Most recent sync run per source |
| `GET /api/v1/admin/listings` | Search listings across all statuses (moderation view) |
| `PATCH /api/v1/admin/listings/{id}` | Manually change a listing's status |
| `DELETE /api/v1/admin/listings/{id}/duplicate-group` | Unlink a listing from its duplicate group |
| `GET /api/v1/admin/users` | Search users, paginated, filter by `role`/`active` |
| `PATCH /api/v1/admin/users/{id}` | Deactivate/reactivate a user and/or change their role |
| `GET /api/v1/admin/audit-log` | Paginated feed of recent admin actions (actor, action, object, time) |

All admin endpoints require the `ADMIN` role (see [Security](#security)) and are implemented by
`AdminSourceController`, `AdminListingController`, `AdminUserController`, and
`AdminAuditLogController` in `com.flatio.web.controller`.

**`PATCH /api/v1/admin/listings/{id}` and `DELETE .../duplicate-group` can return `409 Conflict`**
(issue #367) — the target listing's `version` changed since it was loaded, most often because a
sync job re-ingested it concurrently. The admin action is rejected rather than silently applied to
stale data (`ListingConcurrentModificationException`); the SPA should surface this as "someone/something
else just changed this listing, reload and retry."

**Audit log (#326).** Admin actions were already logged to SLF4J (`Admin action: action=..., ...`)
but that wasn't queryable from the SPA. Issue #326 asked the PO to choose between an API built on
log aggregation (ELK/Loki — no such infra exists in this project yet) or a dedicated DB table; the
PO chose the DB table (issue comment, 2026-08-18). `admin_audit_log` (`V50`/`V51`) is written
manually at each of the 4 existing admin-mutation call sites (`AdminListingServiceImpl#updateStatus`
/`#unlinkDuplicateGroup`, `AdminSourceServiceImpl#update`, `AdminUserServiceImpl#update`) via
`AdminAuditLogService#record`, in the same transaction as the mutation itself — not through an AOP
aspect, to avoid a new `spring-boot-starter-aop` dependency and to mirror the already-established
manual `log.info("Admin action: ...")` pattern at those same sites. `AdminSourceService#update` and
`AdminSourceController#updateSource` gained an `adminId`/`Authentication` parameter they didn't
previously have — source updates had no recorded actor at all before this issue.

An admin cannot change their own role away from `ADMIN` via `PATCH /api/v1/admin/users/{id}`
(`AdminUserServiceImpl#validateNotSelfDowngrade` → `SelfRoleChangeForbiddenException`, HTTP 403) —
guards against the last admin accidentally locking themselves out. Deactivating one's own account
is not guarded the same way — issue #325's AC scoped the check to role changes only.

Implementation notes and known gaps between the design and the M1.6 backend are recorded in the
pull request that introduced these endpoints (issues #33, #35), for the product owner to
reconcile with the SPA design before it is built.

### Known deviations from Issue AC

- The issue text for #33 refers to a `DataSource` entity. The existing `Source` entity (already
  used by every connector's scheduler) already covers `sourceId`/`displayName`/`isEnabled`, so it
  was extended instead of introducing a duplicate entity. `lastSyncAt` is derived from
  `SyncRunService` rather than stored, to avoid a second source of truth. The entity was originally
  also extended with `syncIntervalMinutes`, but no scheduler ever read it — actual sync cadence
  comes from the static `flatio.sync.<source>.{delta,full}.cron` properties, not a per-source DB
  value — so the field was dead weight that also broke `SourcesPage`'s health check (an
  interval of `0`/undefined always evaluated as "healthy", issue #390). Removed entirely
  (backend: `V55`; frontend: issue #354) rather than made functional — wiring it into the
  scheduler would mean redesigning all 28 connector jobs around dynamic cron intervals, which is
  disproportionate to a `low`-priority admin field and was outside every issue's AC (issues #387,
  #390).
- The issue text for #35 refers to a listing `status` value of `DUPLICATE`, but `ListingStatus`
  only has `ACTIVE` / `INACTIVE` / `REPOSTED` (confirmed against the design, which shows the same
  three status badges). Duplicate listings are instead identified by a shared `dedupHash` —
  `GET /api/v1/admin/listings?duplicatesOnly=true` filters for them, and
  `DELETE /api/v1/admin/listings/{id}/duplicate-group` clears the hash on the given listing only,
  leaving the rest of its former group intact.

### Admin SPA frontend (#320, #321)

The admin SPA lives in `frontend/admin/` as a self-contained npm project — Vite + React 18 +
TypeScript (strict) + Tailwind CSS (dark theme) + React Router + TanStack Query.
`frontend/admin/src/api/schema.ts` holds TypeScript types generated from the live OpenAPI spec
(`npm run generate:api-types`, backed by `openapi-typescript` against `/v3/api-docs`); regenerate
it after any admin API change and commit the result — it is not generated at build time, so the
frontend type-checks without a running backend.

**Build pipeline — one deploy artifact, not two services.**

```
npm ci && npm run build          (frontend/admin, via node-gradle plugin)
        ↓ dist/
Sync → src/main/resources/static/admin/   (Gradle "copyFrontendToStatic" task)
        ↓
./gradlew build                  (bundles static/admin/** into app.jar, via processResources)
        ↓
AdminSpaWebConfig                (serves it at /admin with SPA-fallback routing)
```

`copyFrontendToStatic` is wired as a `processResources` dependency, so it runs automatically
for every `./gradlew build`/`bootRun`/`bootJar` — no separate frontend deploy step. The
`com.github.node-gradle.node` plugin is configured with `download.set(false)`: it expects Node on
`PATH` rather than fetching its own copy, so the same Node install (v24) is used locally, in the
Dockerfile builder stage (`apk add nodejs npm`), and in CI (`actions/setup-node`, cached on
`frontend/admin/package-lock.json`). `src/main/resources/static/admin/` itself is generated and
gitignored — never hand-edit it.

`AdminSpaWebConfig` (`com.flatio.config`) serves `static/admin` at `/admin`: an existing file
(JS/CSS/`index.html`) is returned as-is; any other `/admin/**` path (e.g. `/admin/listings`, a
route only React Router knows about) falls back to `index.html` so the SPA can resolve
client-side routes instead of 404ing. `/admin/**` is `permitAll()` in `SecurityConfig` — it is
only the static shell, not the API; the SPA itself calls the JWT-protected
`/api/v1/admin/**` endpoints once loaded.

**Screens (#318, sub-issues #322–#326 — all landed):** all four sidebar screens are wired to real
data — Источники (#322, `SourcesPage.tsx`: table + enable/disable toggle + sync interval +
per-source sync-run history), Объявления (#323, `ListingsPage.tsx` + `ListingDetailModal.tsx`:
filtered search, moderation actions), Дашборд (#324, `DashboardPage.tsx`: aggregate stat cards,
source health strip, recent sync runs, a "new users" block and an admin action audit log feed
(#326) both hidden gracefully on HTTP 404 rather than depending on deploy order), Пользователи
(#325, `UsersPage.tsx`: paginated table, role/active filters, inline role change and activation
toggle). `PlaceholderPage.tsx` was removed once the last two screens landed. #318 itself has no
remaining open sub-issues.

**Authentication (#321).** `/admin/login` renders Telegram's official Login Widget (a script tag
pointing at `telegram.org/js/telegram-widget.js`, wired up by `auth/TelegramLoginWidget.tsx`).
The bot username it needs is not baked into the build — the SPA fetches it at runtime from the
public `GET /api/v1/auth/telegram-bot-username` rather than a `VITE_`-prefixed build-time env var,
because the SPA is built once into the Docker image and that image is reused across environments,
so a build-time value could not vary per deployment. On a successful widget callback, the frontend
POSTs the payload to `POST /api/v1/auth/telegram-login-widget`, which validates its Telegram
signature (a different HMAC-secret derivation than WebApp `initData` — see
`TelegramLoginWidgetValidator`'s Javadoc) and issues a JWT only if the Telegram identity already
belongs to a registered `ADMIN` user; it never creates a new user, unlike the bot's own
`/api/v1/auth/telegram` exchange. The JWT is stored in `sessionStorage`, not `localStorage`, to
shrink the window an XSS payload could exfiltrate a live token in. `ProtectedRoute` still only
checks that a token is *present*; `api/client.ts`'s `apiFetch` wrapper is what actually reacts to
an expired/invalid one — any admin API call it makes that comes back HTTP 401 clears the token and
hard-redirects to `/admin/login`. Logout (sidebar) simply clears the token and navigates there too.

**Routing and error handling (#359, #360).** `App.tsx` nests a `path="*"` route inside
`ProtectedRoute > AdminLayout`, rendering `NotFoundPage.tsx` (sidebar still visible, link back to
the dashboard) for any unmatched path — React Router previously rendered nothing at all on a
route miss. The auth guard still runs first regardless of which child route matched, so an
unauthenticated visitor on an unknown path is redirected to `/admin/login`, not shown the 404
page. Admin API calls throw `ApiError` (carries the HTTP status) instead of a bare `Error`;
`QueryErrorMessage.tsx` renders it, showing a distinct explanation with a retry button for `429`
instead of the generic failure text, and `QueryClient`'s default retry no longer auto-retries 4xx
responses (retrying an already-rate-limited caller only makes it worse).

---

## REST API Conventions

- Base path: `/api/v1/`
- Version in URL, always
- Swagger UI: `/swagger-ui.html`
- OpenAPI spec: `/v3/api-docs`

See `docs/api.md` for endpoint reference.
