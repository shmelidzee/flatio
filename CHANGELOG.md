# Changelog

All notable changes to Flatio are documented here.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

---

## [Unreleased]

### Added
- **PR #131 — Поле `price_unit` в `Listing` (issue #90)**
  - `com.flatio.domain.listing.PriceUnit` — новый enum: `PER_MONTH` | `PER_DAY`
  - `Listing.priceUnit` — новое поле `@Enumerated(EnumType.STRING)`, nullable; автоматически
    выводится из `dealType` при инжесте: `RENT`→`PER_MONTH`, `RENT_DAILY`→`PER_DAY`, `SELL`→`null`
  - `ListingResponse.priceUnit` — поле добавлено в DTO с `@Schema(nullable = true)`
  - `RawListing.priceUnit` — поле-расширение для будущих коннекторов (Realt, Kufar);
    Javadoc документирует что `OnlinerConnector` поле не заполняет — значение всегда выводится из `dealType`
  - Flyway V17 — `ALTER TABLE listings ADD COLUMN IF NOT EXISTS price_unit VARCHAR(10)`
  - `ListingIngestionServiceImpl.derivePriceUnit(DealType)` — приватный метод вывода единицы цены

- **PR #134 — Детектирование повторных объявлений REPOSTED (issue #44)**
  - `ListingStatus.REPOSTED` — новый статус для объявлений, признанных репостами
  - `Listing.repostedFrom` — новое nullable поле `BIGINT`; ссылка на `id` оригинального объявления
  - `Listing.lastRepostedAt` — новое nullable поле `TIMESTAMPTZ`; проставляется на оригинале
    при каждом обнаружении нового репоста
  - `ListingRepository.findFirstByDedupHashAndSourceAndExternalIdNotAndStatus(...)` — новый метод
    для поиска оригинала внутри одного источника по хэшу дедупликации
  - `ListingIngestionServiceImpl.detectRepost(Listing, Source)` — логика детектирования:
    при совпадении `dedupHash` у нового объявления статус устанавливается `REPOSTED`,
    заполняется `repostedFrom`; у оригинала обновляется `lastRepostedAt`
  - Flyway V18 — два новых столбца `reposted_from BIGINT` и `last_reposted_at TIMESTAMPTZ`
    + FK constraint `fk_listing_reposted_from` с `ON DELETE SET NULL`
  - Flyway V19 — составной частичный индекс `idx_listings_dedup_hash_source`
    на `(dedup_hash, source_id) WHERE dedup_hash IS NOT NULL`

### Security
- **PR #133 — Ужесточение Spring Security + CORS из env (issues #128, #129, #132)**
  - `SecurityConfig` — добавлен `anyRequest().denyAll()`: все маршруты, не объявленные явно,
    возвращают HTTP 403 (fail-closed политика)
  - `SecurityConfig.corsConfigurationSource()` — CORS origins теперь читаются из
    `flatio.cors.allowed-origins` (env: `CORS_ALLOWED_ORIGINS`); поддерживается
    comma-separated список; wildcard `*` не принимается; default `http://localhost:3000`
  - `application.yml` — добавлено `flatio.cors.allowed-origins: ${CORS_ALLOWED_ORIGINS:http://localhost:3000}`
  - `RawListing.priceUnit` — добавлен Javadoc: поясняет что поле является точкой расширения
    для будущих коннекторов; `OnlinerConnector` его не заполняет

### Added
- **PR #130 — Документация Telegram /start (docs/post-pr-127)**
  - Обновлена документация после merge PR #127 (Telegram /start, issue #27)

### Security
- **PR #127 — ADMIN роль и Spring Security с JWT (issue #32)**
  - `com.flatio.security.JwtService` — генерация и валидация JWT токенов (HMAC-SHA256);
    ключ обязателен через `JWT_SECRET_KEY` env variable без default значения
  - `com.flatio.security.JwtAuthenticationFilter` — `OncePerRequestFilter`: извлекает Bearer токен,
    проверяет через `JwtService`, устанавливает аутентификацию в `SecurityContextHolder`
  - `com.flatio.security.SecurityConfig` — stateless фильтр-цепочка:
    `/api/v1/admin/**` → ADMIN, `/api/v1/**` → authenticated, Swagger UI → public
  - `com.flatio.security.JwtProperties` — `@ConfigurationProperties(prefix = "flatio.jwt")`:
    `secret-key` (обязательно), `access-token-expiry` (default: 3600 сек)
  - `com.flatio.domain.user.UserRole` — enum: `USER` | `ADMIN`
  - `User.role` — новое поле `@Enumerated(EnumType.STRING)`, default `USER`
  - Flyway V16 — `ALTER TABLE users ADD COLUMN IF NOT EXISTS role VARCHAR(20) NOT NULL DEFAULT 'USER'`
  - `application.yml` — `flatio.jwt.secret-key: ${JWT_SECRET_KEY}`, `flatio.jwt.access-token-expiry: ${JWT_ACCESS_TOKEN_EXPIRY:3600}`
  - Тесты: `JwtServiceTest` (9 тестов), `JwtAuthenticationFilterTest` (5 тестов)
  - Follow-up issues: #128 (anyRequest политика), #129 (CORS конфигурация)

### Added
- **PR #120 — M1.3.10+M1.3.11: OnlinerDeltaSyncJob + OnlinerFullSyncJob (issue #104)**
  - `com.flatio.integration.onliner.scheduler.OnlinerDeltaSyncJob` — инкрементальный синк:
    - `@Scheduled(fixedDelayString = "${flatio.onliner.delta-sync.interval-ms}", initialDelay = 0)` — по умолчанию каждые 10 минут
    - `@EventListener(ApplicationReadyEvent.class)` — принудительный запуск при старте приложения
    - `AtomicReference<Instant> lastSyncCursor` — потокобезопасный курсор; передаётся в `OnlinerConnector.fetchDelta(since)`
    - Обрабатывает `CallNotPermittedException` (circuit breaker OPEN) — логирует WARN, не пробрасывает
    - Структурированное логирование: `source`, `since`, `fetched`, `added`, `updated`, `errors`, `durationMs`
  - `com.flatio.integration.onliner.scheduler.OnlinerFullSyncJob` — полный синк:
    - `@Scheduled(cron = "${flatio.onliner.full-sync.cron}", zone = "Europe/Minsk")` — по умолчанию каждый день в 02:00
    - Вызывает `OnlinerConnector.fetchAll()`, затем `ListingIngestionService.ingestBatch()`
    - Деактивирует объявления, которых нет в ответе Onliner: `listingRepository.deactivateMissingListings(sourceId, returnedExternalIds)`
    - Обрабатывает `CallNotPermittedException` аналогично дельта-синку
  - `application.yml` — добавлены конфиги:
    ```
    flatio.onliner.delta-sync.interval-ms: ${ONLINER_DELTA_SYNC_INTERVAL_MS:600000}
    flatio.onliner.full-sync.cron: ${ONLINER_FULL_SYNC_CRON:0 0 2 * * *}
    ```
  - Удалён `com.flatio.scheduler.ListingSyncScheduler` — дженерик-планировщик заменён двумя специализированными Onliner-джобами
  - Тесты: `OnlinerDeltaSyncJobTest` (9 тестов), `OnlinerFullSyncJobTest` (9 тестов) в пакете `com.flatio.integration.onliner.scheduler`
  - Удалён `ListingSyncSchedulerTest`
  - Старые тестовые файлы из `com.flatio.scheduler` удалены

### Added
- **PR #113 — REST API: поиск и получение объявлений (issues #21, #23)**
  - `GET /api/v1/listings` — пагинированный поиск с фильтрами: `dealType`, `city`, `priceMin`, `priceMax`,
    `rooms`, `sourceId`, `status`; по умолчанию возвращает только ACTIVE объявления; JPA `Specification`
    с JOIN FETCH source и currency (только в data query, не в count query) для устранения N+1
  - `GET /api/v1/listings/{id}` — полные данные объявления, включая историю цен (новейшая первой);
    история цен получается через `PriceHistoryRepository`
  - `GlobalExceptionHandler` — единый `@RestControllerAdvice`: `ListingNotFoundException` → 404,
    `MethodArgumentNotValidException` → 400 с перечислением ошибок по полям,
    `MethodArgumentTypeMismatchException` → 400, необработанные исключения → 500;
    4xx логируются на уровне WARN, 5xx — ERROR
  - `ListingNotFoundException` — доменное исключение в `common.exception`
  - DTO: `ListingSearchCriteria` (query-параметры через `@ModelAttribute`), `ListingResponse` (полный,
    с `priceHistory`), `ListingSummaryResponse` (для списка), `ErrorResponse`, `ValidationError`,
    `PriceHistoryEntry`
  - `ListingMapper` — multi-source маппинг: `toResponse(Listing, List<PriceHistoryEntry>)`,
    `toSummaryResponse(Listing)`, `toHistoryEntry(PriceHistory)`
  - `ListingRepository` теперь расширяет `JpaSpecificationExecutor<Listing>`

### Changed
- **PR #121 — M1.3.9: OnlinerConnector — обновление фикстур и тестов (issue #105)**
  - `OnlinerApartment` и `OnlinerLocation` — добавлен `@JsonIgnoreProperties(ignoreUnknown = true)`:
    без этой аннотации коннектор падал с `UnrecognizedPropertyException` при разборе реальных ответов Onliner
    (поля `up_available_in` в `OnlinerApartment`, `user_address` в `OnlinerLocation`)
  - Фикстуры `valid-response.json` и `response-without-price.json` обновлены до реальной структуры Onliner API:
    `rent_type` теперь содержит реальные значения (`2_rooms`, `3_rooms`, `1_room` вместо `"rent"`/`"sell"`),
    добавлены поля `up_available_in`, `user_address`, `contact.owner`
  - 8 новых тестов в `OnlinerConnectorTest`:
    - Маппинг `rent_type` → `rooms`: `1_room`→1, `2_rooms`→2, `3_rooms`→3, `4_rooms`→4, `room`→null
    - Маппинг `contact.owner` → `isOwner`: `true`, `false`, отсутствующий `contact`→null
  - Итого 30 тестов в `OnlinerConnectorTest` (было 22 до PR #121), 0 failed

- **PR #117 — Onliner: rent_type="room" → propertyType="ROOM" (issue #114)**
  - `OnlinerConnector`: добавлен метод `mapRentTypeToPropertyType(String rentType)` — возвращает `"ROOM"`
    при `rentType = "room"` (аренда комнаты), `"APARTMENT"` для всех остальных значений включая null
  - Ранее `propertyType` всегда был `"APARTMENT"` независимо от типа объявления

- **PR #118 — Onliner: price в BYN + priceUsd из converted (issue #115)**
  - `RawListing`: добавлено поле `BigDecimal priceUsd` (позиция 8, после `currency`)
  - `OnlinerConnector`: `price` теперь берётся из `price.converted.BYN.amount` (хранится в BYN),
    `currency` всегда `"BYN"`; `priceUsd` берётся из `price.converted.USD.amount` (nullable)
  - Ранее `price` брался из `price.amount` (обычно USD), `currency` — из `price.currency`
  - `RawListingMapper`: убран `@Mapping(target = "priceUsd", ignore = true)` в `toEntity` и `updateEntity` —
    MapStruct автоматически маппит поле при создании и обновлении листинга

### Changed
- **PR #100 — Project structure audit: package layout aligned with CLAUDE.md (issue #99)**
  - `com.flatio.connector.core` → `com.flatio.integration.core`: `ListingConnector`, `RawListing`,
    `ConnectorTransientException`
  - `com.flatio.service.mapper.RawListingMapper` → `com.flatio.integration.core.RawListingMapper`
  - `com.flatio.connector.onliner` → `com.flatio.integration.onliner.client`: `OnlinerConnector`
  - `com.flatio.config.ConnectorConfig` → `com.flatio.integration.onliner.config.OnlinerClientConfig`
  - `com.flatio.connector.onliner.OnlinerProperties` → `com.flatio.integration.onliner.config.OnlinerProperties`
  - `com.flatio.connector.onliner.dto` → `com.flatio.integration.onliner.dto`: all 6 DTO records unchanged
  - `com.flatio.bot.FlatioBot` → `com.flatio.telegram.handler.FlatioBot`
  - `com.flatio.bot.StartCommandHandler` → `com.flatio.telegram.command.StartCommandHandler`
  - `com.flatio.bot.config.BotConfig` + `BotConfiguration` → `com.flatio.telegram.config`
  - `application.yml` — FQN reference updated: `connector.core.ConnectorTransientException` →
    `integration.core.ConnectorTransientException`

### Added
- **PR #83 — M1.3.9: OnlinerConnector unit tests — fixture-based deserialization (issue #19)**
  - 3 new fixture-based tests in `OnlinerConnectorTest` that load real Onliner API JSON snapshots
    from classpath via `ObjectMapper`, verifying the complete `@JsonProperty` deserialization chain
    (`deal_type`, `rooms_count`, `number_of_floors`)
  - Tests: `should_correctly_deserialize_valid_response_fixture_including_json_property_mappings`,
    `should_return_empty_list_from_empty_response_fixture`,
    `should_skip_listing_with_null_price_when_loaded_from_fixture`

### Fixed
- **PR #83 — null price handling in `OnlinerConnector.toRawListing()`**
  - Previously a listing with `"price": null` in the API response was added to results with `null` price
    instead of being skipped; now throws `IllegalArgumentException` which is caught by `parseListings()`
    and logged at WARN level — listing is correctly skipped while others continue processing

### Added
- **PR #82 — M1.3.7: Resilience4j retry backoff + circuit breaker for connectors (issue #17)**
  - `com.flatio.connector.core.ConnectorTransientException` — new exception in `connector.core` for
    signalling retryable transient errors (HTTP 429); placed in core for reuse by all future connectors
  - `OnlinerConnector.fetch()` — added `@CircuitBreaker(name = "connector-onliner")` alongside existing
    `@RateLimiter` and `@Retry`; aspect order: RateLimiter (outermost) → Retry → CircuitBreaker (inner)
  - HTTP 429 handling: reads `Retry-After` header (default 5s), sleeps via `sleepQuietly()`, then throws
    `ConnectorTransientException` to trigger Resilience4j retry
  - HTTP 4xx (non-429): logged at ERROR, returns `List.of()` without retry
  - HTTP 5xx: propagates `HttpServerErrorException` for retry and circuit-breaker tracking
  - `application.yml` — Resilience4j retry updated with `retry-exceptions` and `ignore-exceptions`:
    retries on `HttpServerErrorException`, `ResourceAccessException`, `ConnectorTransientException`;
    ignores `CallNotPermittedException` (circuit breaker OPEN state — bypasses retry, goes to fallback)
  - `application.yml` — circuit breaker added: COUNT_BASED, window=5, failure-rate=100%,
    wait-duration-in-open-state=60s, auto-transition to HALF_OPEN, 1 probe call
  - `ListingSyncScheduler` — added explicit `catch (CallNotPermittedException)` before generic `catch (Exception)`:
    logs `log.warn("Circuit OPEN, skipping: source={}")` and continues to next connector
  - Tests: 4 new tests in `OnlinerConnectorTest` (HTTP 429, Retry-After header, 4xx non-retryable, 5xx propagation),
    1 new test in `ListingSyncSchedulerTest` (circuit OPEN — no propagation, ingest skipped)
  - 117 tests passed, 0 failed — M1.3.7 closed

- **PR #80 — M1.5.1: Telegram Bot dependency + base configuration (issue #26)**
  - `org.telegram:telegrambots-spring-boot-starter:6.9.0` added to `build.gradle.kts`;
    transitive `jackson-module-jaxb-annotations` excluded (incompatible with Java 21 — `javax.xml.bind` absent from JDK 21)
  - `BotConfig` — `@ConfigurationProperties(prefix = "telegram.bot")` Record; compact constructor
    throws `IllegalStateException` on startup if `TELEGRAM_BOT_TOKEN` or `TELEGRAM_BOT_USERNAME` not set
  - `BotConfiguration` — `@Configuration` + `@EnableConfigurationProperties(BotConfig.class)`
  - `FlatioBot extends TelegramLongPollingBot` — `@Component` Spring bean; delegates token/username to `BotConfig`;
    token never logged
  - `application.yml` — `telegram.bot.token=${TELEGRAM_BOT_TOKEN}`, `telegram.bot.username=${TELEGRAM_BOT_USERNAME}`
    (no default values — application fails to start without both env vars)
  - Note: `telegrambots-spring-boot-starter:6.9.0` uses legacy `spring.factories` autoconfiguration format
    incompatible with Spring Boot 3.2; long-polling registration requires explicit config in M1.5.2
  - Tests: `BotConfigTest` (5 unit tests), `FlatiBotTest` (2 unit tests)
  - 98 tests passed, 0 failed — M1.5.1 closed

- **PR #79 — M1.4.1: DTO + MapStruct mapping Listing ↔ ListingResponse (issue #20)**
  - `com.flatio.web.dto.ListingResponse` — Java Record with 19 fields, each annotated with `@Schema`;
    `sourceId` mapped from `source.code`, `currency` mapped from `currency.code`
  - `com.flatio.web.dto.ListingSummaryResponse` — compact 11-field summary Record for list displays;
    `photoUrl` field present but ignored in mapper (no photo storage in entity — placeholder for M1.4.x)
  - `com.flatio.web.mapper.ListingMapper` — MapStruct `@Mapper(componentModel = "spring")`:
    `toResponse(Listing)`, `toSummaryResponse(Listing)`, `toSummaryResponseList(List<Listing>)`
  - 91 tests passed, 0 failed — M1.4.1 closed

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
  - `com.flatio.service.impl.ListingIngestionServiceImpl`:
    - **CREATE path**: maps via `RawListingMapper.toEntity()`, sets `source`, `currency`, `country`, `status=ACTIVE`,
      `dedupHash`; records initial `PriceHistory` before `listingRepository.save()`
    - **UPDATE path**: updates fields via `RawListingMapper.updateEntity(@MappingTarget)`, refreshes `status` and
      `dedupHash`; records `PriceHistory` only if price changed
    - `@Transactional` per item with `@Propagation.NOT_SUPPORTED` on `ingestBatch` — broken items roll back
      independently without aborting the batch
    - Self-proxy via `@Lazy @Autowired ListingIngestionService self` — ensures `@Transactional` AOP is applied
      on `ingest()` calls from within the same bean
  - `com.flatio.service.domain.IngestOutcome` — enum: `CREATED` | `UPDATED`
  - `com.flatio.service.domain.BatchIngestResult` — Java Record: `added`, `updated`, `errors` counters
  - `com.flatio.service.DedupHashService` — interface extracted from `ListingService` to decouple ingestion
    from listing management; single method `computeDedupHash(address, rooms, areaTotalM2, dealType): String`
  - `com.flatio.service.impl.DedupHashServiceImpl` — SHA-256 hash with field normalisation
    (lowercase, trim, collapse whitespace, `stripTrailingZeros` for `BigDecimal`); separator `|` between fields
    to prevent adjacent-null collisions
  - `com.flatio.service.mapper.RawListingMapper` — MapStruct `@Mapper(componentModel = "spring")` moved from
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
