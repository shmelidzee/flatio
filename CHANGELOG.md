# Changelog

All notable changes to Flatio are documented here.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

---

## [Unreleased]

### Added
- **PR #77 — M1.3.4 + M1.3.8: ListingSyncScheduler — periodic sync + structured logging (issue #15)**
  - `com.flatio.config.SchedulerConfig` — activates Spring scheduling via `@EnableScheduling`
  - `com.flatio.scheduler.ListingSyncScheduler` — iterates all `ListingConnector` beans and syncs each source sequentially:
    - `@Scheduled(fixedDelayString = "${flatio.sync.interval-ms}", initialDelay = 0)` — runs at startup then after configurable delay
    - Resolves `Source` entity from `SourceRepository.findByCode(sourceId)` for each connector
    - Calls `ListingIngestionService.ingestBatch(rawListings, source)` and logs structured sync result
    - Per-connector error isolation: exception in one connector does not abort others; caught and logged as `log.error`
    - Structured `key=value` logging: `source`, `fetched`, `added`, `updated`, `errors`, `durationMs` per sync cycle
  - `application.yml` — `flatio.sync.interval-ms: ${FLATIO_SYNC_INTERVAL_MS:1800000}` (default 30 min)
  - `LogbackProdProfileTest` — stabilised with `@MockBean SourceRepository` for the new scheduler dependency
  - Tests: `ListingSyncSchedulerTest` — 7 unit tests (happy path, empty fetch, source not found, connector throws,
    ingest throws, second-connector isolation); manual construction in `@BeforeEach` to handle `List<ListingConnector>`
  - 91 tests passed, 0 failed — M1.3.4 + M1.3.8 closed

- **PR #75 — M1.3.3 + M1.3.5: ListingIngestionService — upsert + PriceHistory (issue #14)**
  - `com.flatio.service.ListingIngestionService` — interface with two methods:
    - `ingest(RawListing raw, Source source): IngestOutcome` — transactional upsert for a single listing
    - `ingestBatch(List<RawListing> raws, Source source): BatchIngestResult` — batch orchestrator with per-item isolation
  - `com.flatio.service.ListingIngestionServiceImpl`:
    - **CREATE path**: maps via `RawListingMapper.toEntity()`, sets `source`, `currency`, `country`, `status=ACTIVE`,
      `dedupHash`; records initial `PriceHistory` before `listingRepository.save()`
    - **UPDATE path**: updates fields via `RawListingMapper.updateEntity(@MappingTarget)`, refreshes `status` and
      `dedupHash`; records `PriceHistory` only if price changed
    - `@Transactional` per item with `@Propagation.NOT_SUPPORTED` on `ingestBatch` — broken items roll back
      independently without aborting the batch
    - Self-proxy via `@Lazy @Autowired ListingIngestionService self` — ensures `@Transactional` AOP is applied
      on `ingest()` calls from within the same bean
  - `com.flatio.service.IngestOutcome` — enum: `CREATED` | `UPDATED`
  - `com.flatio.service.BatchIngestResult` — Java Record: `added`, `updated`, `errors` counters
  - `com.flatio.service.DedupHashService` — interface extracted from `ListingService` to decouple ingestion
    from listing management; single method `computeDedupHash(address, rooms, areaTotalM2, dealType): String`
  - `com.flatio.service.DedupHashServiceImpl` — SHA-256 hash with field normalisation
    (lowercase, trim, collapse whitespace, `stripTrailingZeros` for `BigDecimal`); separator `|` between fields
    to prevent adjacent-null collisions
  - `com.flatio.service.RawListingMapper` — MapStruct `@Mapper(componentModel = "spring")` moved from
    `connector.core` to `service` package; added `void updateEntity(RawListing raw, @MappingTarget Listing listing)`
    for the update path; `default DealType toDealType(String)` — case-insensitive, graceful null/unknown fallback
  - `com.flatio.service.ListingService` — interface retained; `computeDedupHash` moved to `DedupHashService`;
    listing query methods deferred to M1.4
  - Tests: `ListingIngestionServiceImplTest` (12 unit tests), `RawListingMapperTest` (10 tests, moved to
    `service` package), `DedupHashServiceImplTest` (12 unit tests, renamed from `ListingServiceImplTest`)
  - `LogbackProdProfileTest` — stabilised with `@MockBean ListingIngestionService` to satisfy
    `ListingIngestionServiceImpl` constructor dependencies when JPA autoconfiguration is excluded
  - 84 tests passed, 0 failed — M1.3.3 + M1.3.5 closed

- **PR #73 — M1.3.2: OnlinerConnector — API request and response parsing (issue #13)**
  - `com.flatio.connector.onliner.OnlinerConnector` — implements `ListingConnector` for the Onliner API:
    - `@RateLimiter(name = "connector-onliner")` — 1 request/second, 5s timeout on permit acquire
    - `@Retry(name = "connector-onliner", fallbackMethod = "fetchFallback")` — 3 attempts with
      exponential backoff (2s → 4s → 8s); exceptions propagate to trigger retry (no inner try-catch)
    - `fetchFallback(Exception e)` — invoked after exhausted retries; returns empty list (never throws)
    - Per-listing error isolation in `parseListings()` — broken entry is skipped, rest are returned
    - Realistic Chrome/125 `User-Agent` header on every request
    - `sourceId` and `regionCode` from `OnlinerProperties` — never hard-coded
  - `com.flatio.connector.onliner.OnlinerProperties` — `@ConfigurationProperties(prefix = "connector.onliner")`:
    `baseUrl`, `sourceId`, `regionCode`, `apartmentsPath`, `pageSize`
  - DTO package `com.flatio.connector.onliner.dto` — 6 Java Records with Jackson `@JsonProperty`:
    `OnlinerSearchResponse`, `OnlinerApartment`, `OnlinerPrice`, `OnlinerLocation`, `OnlinerArea`, `OnlinerPage`
  - `com.flatio.config.ConnectorConfig` — registers `@Bean("onlinerRestClient")` with:
    - Connect timeout: 5s, Read timeout: 10s (via `ClientHttpRequestFactorySettings`)
    - Base URL and User-Agent header pre-configured
    - `@EnableConfigurationProperties(OnlinerProperties.class)`
  - `application.yml` — Resilience4j config for `connector-onliner` rate limiter and retry;
    connector config with env-variable overrides (`ONLINER_BASE_URL`, `ONLINER_SOURCE_ID`, `ONLINER_REGION_CODE`)
  - `OnlinerConnectorTest` — 10 unit tests (Mockito, no Spring context):
    valid response → 2 listings, field mapping, empty/null response, fallback behavior,
    broken price amount isolation, fallback title, null photo
  - Fixtures: `src/test/resources/fixtures/onliner/` — 3 JSON snapshots
  - 72 tests passed, 0 failed — M1.3.2 closed

- **PR #71 — M1.3.1: ListingConnector interface + RawListing record (issue #12, M1.3.1)**
  - `com.flatio.connector.core.ListingConnector` — интерфейс контракта для всех коннекторов источников данных
    с тремя методами: `getSourceId()`, `getSupportedRegionCode()` (код из конфига, не хардкод), `fetch()`
  - `com.flatio.connector.core.RawListing` — Java Record с 18 полями для передачи сырых данных от коннектора
    к сервису; 4 обязательных поля (externalId, title, price, sourceUrl), остальные nullable
  - Javadoc на интерфейсе документирует security-требования к реализациям: rate limiting, retry, изоляция
    ошибок, запрет хранения raw HTML
  - 6 unit-тестов в `RawListingTest`: конструирование, nullable поля, equals/hashCode, graceful degradation,
    мультирегиональность (non-BY регион)
  - 62 теста passed, 0 failed — M1.3.1 закрыт

- **PR #69 — M1.2.6: paginated ListingRepository + IT тесты репозиториев (issue #11, M1.2.6)**
  - `ListingRepository.findPageByCountryCodeAndStatus(String, ListingStatus, Pageable)` — пагинированный метод с явным `countQuery` (без JOIN FETCH, без in-memory пагинации)
  - 4 новых IT-теста пагинации в `ListingRepositoryIT`: первая страница, вторая страница, фильтрация INACTIVE, пустая страница
  - 56 тестов passed, 0 failed — M1.2 полностью закрыт

- **PR #67 — dedup_hash в Listing + SHA-256 вычисление в ListingService (issue #10, M1.2.5)**
  - Поле `dedup_hash VARCHAR(64)` в таблице `listings` с индексом (Flyway V10)
  - Поле `dedupHash` в Entity `Listing`
  - Интерфейс `ListingService` с методом `computeDedupHash(address, rooms, areaTotalM2, dealType)`
  - `ListingServiceImpl` — SHA-256 через `java.security.MessageDigest`, нормализация:
    lowercase, trim, collapse whitespace; разделитель `|` для устранения коллизий смежных null-полей
  - `ListingRepository.findByDedupHashAndSourceNot(String, Source)` для cross-source поиска
  - 11 unit-тестов нормализации и хэширования + 5 IT-тестов репозитория

- **PR #66 — Entity PriceHistory + Flyway миграция (issue #8, M1.2.3)**
  - Entity `PriceHistory`: append-only история цен объявлений
  - Поля: `id`, `listing` (FK LAZY), `price`, `currency` (FK LAZY), `recordedAt` (NOT NULL, auto-set via `@PrePersist`)
  - Flyway V9 — DDL таблицы `price_history`, составной индекс `(listing_id, recorded_at DESC)`
  - `PriceHistoryRepository.findByListingOrderByRecordedAtDesc` с JOIN FETCH currency
  - 5 IT-тестов: ordering, empty list, isolation between listings, eager currency, persist check

### Changed
- **PR #64 — README переведён на русский язык (issue #63, M1.1)**
  - README.md полностью переведён на русский язык
  - Все разделы актуализированы: Быстрый старт, Конфигурация, Запуск тестов, Логирование, Документация API
  - Секция переменных окружения с `DB_FLATIO_URL`, `DB_FLATIO_USER`, `DB_FLATIO_PASSWORD`
  - Таблица ссылок на `docs/architecture.md` и `CHANGELOG.md`

### Added
- **PR #58 — User and UserAuthProvider entities (issue #9)**
  - Entity `User`: `id`, `displayName`, `email` (nullable), `active`, `createdAt`, `updatedAt`
  - Entity `UserAuthProvider`: `id`, `user` (FK LAZY), `provider` (enum), `externalId`, `createdAt`;
    unique constraint `(provider, external_id)`
  - Enum `AuthProvider`: `TELEGRAM`, `GOOGLE`, `EMAIL`
  - Flyway V7 — DDL for `users` and `user_auth_provider` tables
  - Flyway V8 — index on `user_auth_provider.user_id`
  - `UserRepository` with JPQL `findByProviderAndExternalId` (active users only) and
    convenience `findByTelegramId` default method
  - `UserAuthProviderRepository` — standard CRUD
  - Integration tests for `UserRepository` (10 test cases)
- **Commit a03a0e1 — Railway deployment infrastructure**
  - Multi-stage `Dockerfile`: builder stage (JDK 21, compiles JAR), runtime stage (JRE 21)
  - `railway.json`: DOCKERFILE builder, `/actuator/health` healthcheck, ON_FAILURE restart policy
  - `.github/workflows/ci.yml`: build + test on every push to `feature/**`, `fix/**`, `develop`
    and on PRs to `develop`/`master`; Gradle cache via `actions/setup-java`

### Changed
- **PR #60 — Standardized environment variable names (issue #59)**
  - `SPRING_DATASOURCE_URL` → `DB_FLATIO_URL`
  - `SPRING_DATASOURCE_USERNAME` → `DB_FLATIO_USER`
  - `SPRING_DATASOURCE_PASSWORD` → `DB_FLATIO_PASSWORD`

### Fixed
- **PR #61 — LogbackProdProfileTest context pollution (issue #55)**
  - Added `@DirtiesContext(BEFORE_CLASS)` so Spring re-creates context and re-initializes
    Logback with the `prod` profile after preceding Testcontainers IT tests reset `LoggerContext`

### Added
- **PR #56 — Entity Listing + Flyway migrations V5/V6 (issue #7, M1.2.2)**
  - Entity `Listing` with 23 fields: `externalId`, `source`, `title`, `description`, `dealType`,
    `propertyType`, `price`, `currency`, `priceUsd`, `rooms`, `floorNumber`, `floorsTotal`,
    `areaTotalM2`, `areaLivingM2`, `areaKitchenM2`, `address`, `latitude`, `longitude`,
    `country`, `city`, `district`, `status`, `sourceUrl`, `publishedAt`, `createdAt`, `updatedAt`;
    three `@ManyToOne(fetch = LAZY)` relations to `Source`, `Currency`, and `Country`
  - Enum `DealType` (RENT / SELL)
  - Enum `ListingStatus` (ACTIVE / INACTIVE)
  - Flyway migration V5 — DDL for table `listings` with UNIQUE constraint `(external_id, source_id)`
  - Flyway migration V6 — 5 indexes on `listings`: `source_id`, `status`, `deal_type`, `price`, `published_at`
  - `ListingRepository` with JPQL queries `findByExternalIdAndSourceId` (deduplication) and
    `findByCountryCodeAndStatus` (filtered feed with eager-joined relations)
  - Integration tests for `ListingRepository` (6 test cases)

## [0.0.1-SNAPSHOT] — 2026-06-07

### Added
- **PR #52 — Structured JSON logging (issue #5, M1.1.8)**
  - Added `net.logstash.logback:logstash-logback-encoder:7.4` dependency
  - Added `logback-spring.xml`: `prod` profile outputs structured JSON via `LogstashEncoder`
    (fields: `@timestamp`, `level`, `logger_name`, `thread_name`, `message`);
    all other profiles use a human-readable console pattern
  - Added unit tests: `LogbackConfigurationTest`, `LogbackProdProfileTest`

- **PR #51 — Springdoc OpenAPI / Swagger UI (issue #4)**
  - Added `springdoc-openapi-starter-webmvc-ui:2.4.0` dependency
  - Swagger UI available at `/swagger-ui.html`; OpenAPI spec at `/v3/api-docs`

- **PR #50 — README and local setup (issue #3)**
  - Added `README.md` with quick-start instructions
  - Added `src/main/resources/application-local.yml.example`

- **PR #2 — Project skeleton (issue #1)**
  - Java 21 + Spring Boot 3.2.12 + Gradle 8.x (Kotlin DSL)
  - PostgreSQL 16 + Flyway migrations
  - Lombok, MapStruct, Resilience4j, Testcontainers baseline
  - Docker Compose for local PostgreSQL
  - GitHub Actions CI workflow
