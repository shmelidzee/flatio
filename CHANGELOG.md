# Changelog

All notable changes to Flatio are documented here.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

---

## [Unreleased]

### Fixed
- **PR #353 — Четыре багфикса admin-панели: 500 на /admin, город Onliner, тултип дашборда, баннер ошибки (issues #349, #350, #351, #352)**
  - **#349** — `AdminSpaWebConfig.SpaFallbackResourceResolver` не обрабатывал пустой `resourcePath`,
    возникающий при точном (non-wildcard) совпадении паттерна `/admin` — `GET /admin` без слэша
    отдавал 500 вместо SPA-шелла; `GET /admin/` работал и раньше. Исправлено явной отдачей
    `index.html` для пустого `resourcePath`
  - **#350** — `OnlinerConnector`/`OnlinerSaleConnector`: добавлен `resolveCity(address)` — город
    извлекается как сегмент `location.address` до первой запятой (Onliner API не отдаёт отдельное
    поле города); затрагивает и admin-таблицу, и публичный `GET /api/v1/listings?city=...`
  - **#351** — `DashboardPage.tsx` (`SourceHealthRow`) — добавлен `title` с полным `displayName`
    на обрезанное название источника в health-стрипе
  - **#352** — `adminUsers.ts#updateUser` теперь парсит `ErrorResponse.message` из тела ответа
    (fallback на `HTTP {status}`); баннер ошибки на `UsersPage` сбрасывается при смене фильтров
    и автоматически через 5 секунд

### Added
- **PR #345 — Лента админ-действий (audit log) на дашборде (issue #326)**
  - PO выбрал подход из двух предложенных в issue: отдельная таблица `admin_audit_log` в БД
    вместо API поверх агрегации SLF4J-логов (лог-агрегация потребовала бы новой инфраструктуры —
    ELK/Loki — которой в проекте нет)
  - Flyway `V50`/`V51` — таблица `admin_audit_log` (`admin_id`, `action`, `object_type`,
    `object_id`, `created_at`), индекс по `created_at DESC` отдельной миграцией
  - `GET /api/v1/admin/audit-log` — пагинированная лента последних действий, обогащённая
    `adminDisplayName` одним batch-запросом к `UserRepository` (без N+1)
  - Запись подключена вручную (не через AOP) в 4 существующих admin-мутациях: обновление статуса
    объявления и отвязка дубликата, обновление источника, обновление пользователя — в той же
    транзакции, что и само действие
  - Попутно исправлен пробел: `AdminSourceController`/`AdminSourceService.update` не принимали
    `Authentication` — у изменений источника не было записанного актора вообще
  - Frontend: блок «Лента админ-действий» на дашборде, скрывается на HTTP 404 как и блок «Новые
    пользователи» из #324
  - Побочный фикс: 4 существующих full-context `@SpringBootTest`
    (`SecurityConfigTest`/`LogbackProdProfileTest`/`KufarAdDetailClientResilienceTest`/
    `KufarSyncExecutorIsolationTest`, каждый мокает все JPA-репозитории вручную) не мокали новый
    `AdminAuditLogRepository` — добавлено
- **PR #343 — Admin SPA: экраны «Дашборд» и «Пользователи» (issues #324, #325)**
  - `GET /api/v1/admin/users` — поиск пользователей с пагинацией, фильтры `role`/`active`;
    `PATCH /api/v1/admin/users/{id}` — деактивация и/или смена роли, с audit-логом
    (`Admin action: action=updateUser, ...`, тот же формат что и у `AdminListingService`/
    `AdminSourceService`)
  - Админ не может понизить собственную роль с `ADMIN` (`SelfRoleChangeForbiddenException` → 403)
    — защита от случайной потери доступа последним админом; self-деактивация не блокируется, это
    сознательно вне AC issue #325
  - `AdminUserService`/`AdminUserServiceImpl`, `AdminUserController`, `AdminUserMapper`
    (MapStruct), DTO (`AdminUserResponse`/`AdminUserSearchCriteria`/`AdminUserUpdateRequest`),
    `UserNotFoundException`; `UserRepository` расширен `JpaSpecificationExecutor`
  - Frontend: `DashboardPage.tsx` — карточки агрегатов (активные объявления, источники
    активны/всего, ошибки синков за 24ч по `sync-runs/latest`), health-стрип источников,
    последние 5 запусков синка, блок «новые пользователи» (скрывается на HTTP 404 вместо ошибки,
    чтобы не зависеть от порядка деплоя относительно #325); кнопка «Обновить» инвалидирует все
    TanStack Query с ключом `["admin", ...]`
  - Frontend: `UsersPage.tsx` — таблица с фильтрами по роли/статусу, инлайн-смена роли и
    активация/деактивация
  - `PlaceholderPage.tsx` удалён — экраны, для которых он был последней заглушкой, реализованы
  - `frontend/admin/src/api/schema.ts` дополнен вручную (не через `generate:api-types`) —
    `TelegramStartupValidator` не даёт локально поднять бэкенд без настоящего Telegram-токена;
    типы приведены в соответствие `@Schema`-аннотациям DTO, перегенерировать при следующем запуске
    с реальным ботом
- **PR #342 — Admin SPA: экран «Объявления» (issue #323)**
  - `ListingsPage.tsx` — таблица + фильтры (источник/город/сделка/статус/цена/площадь/комнаты/
    поиск/только дубли) по `GET /api/v1/admin/listings`; `ListingDetailModal.tsx` — фото/описание/
    адрес/история цены, действия «Деактивировать» (`PATCH /api/v1/admin/listings/{id}`) и
    «Отвязать дубликат» (`DELETE /api/v1/admin/listings/{id}/duplicate-group`)
  - `AdminListingSearchCriteria` расширен фильтрами по площади (`areaMin`/`areaMax`) и ключевому
    слову (`query`, обычный `LIKE` по title/description/address — не full-text поиск публичного
    эндпоинта); `ListingResponse` получил `hasDuplicates` (отдельный запрос на объявление, не
    вычисляется для списков — избегает N+1)
- **PR #341 — Admin SPA: экран «Источники» (issue #322)**
  - `SourcesPage.tsx` — таблица источников (`GET /api/v1/admin/sources`) с toggle
    enable/disable и полем `syncIntervalMinutes` (`PATCH /api/v1/admin/sources/{sourceId}`);
    клик по строке разворачивает историю запусков синка (`GET /api/v1/admin/sync-runs?sourceId=...`,
    пагинация по 20) с индикатором «здоровья» источника (устарел, если пропущено ≥2 интервалов
    синка)
  - `formatRelativeTime` (`frontend/admin/src/lib`) — переиспользуемое форматирование
    относительного времени на русском («12 мин назад», «никогда»)
- **PR #339 — Вход в admin-панель через Telegram Login Widget (issue #321)**
  - `POST /api/v1/auth/telegram-login-widget` — валидирует HMAC-SHA256 подпись Telegram Login
    Widget (`secret_key = SHA-256(bot_token)` — отдельный алгоритм от WebApp `initData`, который
    использует `HMAC-SHA256("WebAppData", bot_token)`), выдаёт JWT только пользователю с
    `role=ADMIN`; в отличие от `/api/v1/auth/telegram` никогда не создаёт новых пользователей —
    и неизвестный Telegram-аккаунт, и найденный не-админ получают одинаковый 403
  - `TelegramLoginWidgetValidator` (`com.flatio.security`) — новый класс, намеренно отдельный от
    `TelegramInitDataValidator` (разный вывод HMAC-секрета для двух форматов Telegram-данных)
  - `GET /api/v1/auth/telegram-bot-username` — публичный конфиг-эндпоинт, отдаёт SPA имя бота для
    рендера виджета вместо `VITE_`-переменной сборки (SPA собирается один раз в Docker-образ,
    переиспользуемый во всех окружениях — build-time значение не годится)
  - `UserService.findByTelegramId` — новый find-only метод (не создаёт пользователя, в отличие
    от `findOrCreate`); `AdminAccessDeniedException` → 403 через `GlobalExceptionHandler`
  - Frontend (`frontend/admin`): `/login` рендерит настоящий Telegram Login Widget; JWT теперь
    хранится в `sessionStorage`, не в `localStorage` (было в каркасе #320 — фикс, не регрессия);
    `api/client.ts` (`apiFetch`) — на HTTP 401 чистит токен и делает hard-redirect на
    `/admin/login`; logout-кнопка в Sidebar; dev-прокси `/api` → `localhost:8080` в
    `vite.config.ts` для `npm run dev`
  - `docs/api.md`, `docs/local-setup.md` обновлены
- **PR #329 — Каркас admin-фронтенда: Vite+React+TS+Tailwind, сборка через Gradle (issue #320)**
  - `frontend/admin/` — новый npm-проект: Vite + React 18 + TypeScript (strict) + Tailwind CSS
    (тёмная тема) + React Router v7 (не v6 — из-за CVE в 6.x) + TanStack Query
  - `frontend/admin/src/api/schema.ts` — TypeScript-типы, сгенерированные `openapi-typescript`
    из `/v3/api-docs` (`npm run generate:api-types`); коммитятся в репозиторий, не генерируются
    в билде — фронтенд типизируется без запущенного бэкенда
  - Gradle: плагин `com.github.node-gradle.node`, таски `npmCiFrontend` / `buildFrontend` /
    `copyFrontendToStatic` копируют `dist/` в `src/main/resources/static/admin`; подключены как
    зависимость `processResources` — `./gradlew build` / `bootRun` теперь требуют Node.js 24
    на `PATH` (плагин настроен с `download.set(false)`, свою копию Node не скачивает)
  - `com.flatio.config.AdminSpaWebConfig` (новый) — отдаёт `/admin/**` как статику SPA с
    фолбэком на `index.html` для client-side роутинга React Router
  - `com.flatio.security.SecurityConfig` — явный `permitAll()` на `/admin`, `/admin/`, `/admin/**`
    (только статический шелл SPA; `/api/v1/admin/**` остаётся под `hasRole(ADMIN)`)
  - `Dockerfile` — `apk add nodejs npm` в builder-стадии; `.github/workflows/ci.yml` —
    `actions/setup-node@v4` (node-version 24) с npm-кэшем перед `./gradlew build integrationTest`;
    `check` теперь зависит от `lintFrontend` и `testFrontend`
  - Sidebar-layout на 4 пункта (Дашборд/Объявления/Источники/Пользователи) — заглушки без
    реальных данных; `ProtectedRoute` редиректит на `/admin/login` при отсутствии токена в
    `localStorage`; сам логин-экран — заглушка (реальный Telegram Login Widget — issue #321,
    заблокирован этим PR)
  - `docs/architecture.md` — раздел «Admin SPA frontend (#320)» с полным описанием пайплайна сборки

### Fixed
- **PR #347 — Kufar-адрес: читать `account_parameters` вместо хрупкого скрейпа detail-страницы (issue #334)**
  - Найдена настоящая причина fallback-адреса "область, город": `re.kufar.by/vi/{adId}` теперь
    отдаёт `301` на новый SEO-путь, а `KufarAdDetailClient` намеренно не следует редиректам
    (SSRF-защита, #315) — каждый запрос точного адреса молча падал независимо от rate
    limiter/circuit breaker (#328/#332)
  - Обнаружено (живая выборка 60 объявлений, 4 категории, 2026-08-18): точный адрес уже приходит
    в самом ответе search API, в `account_parameters[p="address"]` — поле ошибочно
    документировалось как «только данные продавца» (issue #311) и никогда не читалось
  - `KufarApiClient.resolveAddress` — новый порядок приоритета: `account_parameters` →
    `KufarAdDetailClient` (оставлен как fallback, не удалён) → `region`/`area`
  - Расследование проведено без обхода гео-ограничений источника — явный отказ от VPN/подмены
    геолокации, запрошенных в исходной постановке задачи (см. комментарий в issue #334)
- **PR #338 — Порог деактивации, graceful shutdown executor'ов, фоллбэк фото в Telegram, диагностика Kufar-адреса (issues #335, #337, #333, #334)**
  - `flatio.sync.inactive-threshold` снижен с `3` до `1` — максимальная задержка деактивации
    пропавшего объявления теперь ~1 сутки вместо ~3 (#335)
  - `kufarSyncExecutor` и `startupSyncExecutor` (`SchedulerConfig`) — включён graceful shutdown
    (`waitForTasksToCompleteOnShutdown=true`, `awaitTerminationSeconds=20`), меньше шума в
    логах/метриках при деплое на Railway (#337)
  - `SearchResultSender.sendCard` — при ошибке загрузки фото карточка теперь уходит с
    плейсхолдер-фото вместо текстовой; текстовая карточка остаётся крайним случаем (#333)
  - `KufarAdDetailClient` — `reason=`-тегированное логирование на каждом пути fallback на грубый
    `region, area` адрес, чтобы измерить частоту каждой причины в прод-логах (#334 — issue
    остаётся открытым до накопления прод-данных, инструментация не равна фиксу)
  - issue #327 (пустой Onliner-адрес) закрыт отдельным комментарием — уже был решён ранее в
    PR #331, чек-лист issue просто не был отмечен
- **PR #336 — Изолировать Kufar sync job'ы от общего thread pool планировщика (issue #332)**
  - Новый bean `kufarSyncExecutor` (`core=7, max=12, queue=20`) в `SchedulerConfig`; все 12
    `@Scheduled`-методов Kufar sync job'ов (6 категорий × delta/full) помечены
    `@Async("kufarSyncExecutor")`
  - Onliner/Realt sync и health-freshness watchdog больше не блокируются конкуренцией за
    `connector-kufar-detail` RateLimiter независимо от его `timeout-duration`
- **PR #331 — Увеличить timeout rate limiter Kufar-detail; расследовать address Onliner (issues #328, #327)**
  - `connector-kufar-detail.timeout-duration` увеличен с `5s` до `15s` — устраняет массовый
    fallback на грубый адрес из-за конкуренции до 6 параллельных Kufar sync job за общий
    `RateLimiter`, а не из-за реальных сбоев сети (#328)
  - Расследование #327 (пустой `address` у Onliner-объявлений в БД): код-дефекта в `develop` не
    найдено — путь `location.address → RawListing → Listing` корректен и покрыт регрессионными
    тестами; добавлено DEBUG-логирование адреса сразу после парсинга для диагностики рецидивов
- **PR #300 — «Договорная» вместо «0 BYN»; извлечение адреса из Kufar (issues #298, #299)**
  - Flyway V45 — `ALTER TABLE listings ADD COLUMN is_negotiable BOOLEAN NOT NULL DEFAULT FALSE`
  - `RawListing` — добавлено поле `isNegotiable` (23-е)
  - `KufarApiClient` — адрес извлекается из `ad_parameters[key="address"].vl`; `priceByn == null || 0` → `isNegotiable = true`, `price = 0`
  - `OnlinerConnector` / `OnlinerSaleConnector` — `price = null` → `isNegotiable = true` вместо пропуска объявления
  - `RealtHtmlParser` — `price = 0` → `isNegotiable = true` вместо пропуска объявления
  - `ListingResponse` — новые поля `priceLabel` (`"Договорная"` когда `isNegotiable`, иначе `null`) и `isNegotiable`
  - `ListingSummaryResponse` — новое поле `isNegotiable`
  - `ListingFormatter` — `isNegotiable = true` → «Договорная» (жирным) вместо числовой цены в Telegram-карточке
  - `ListingServiceImpl` — ценовой фильтр (`priceMin` / `priceMax`) исключает `isNegotiable` объявления
  - `ListingRepository` — FTS-запрос обновлён: `l.is_negotiable` в `SELECT`, фильтр в `WHERE` при активном ценовом фильтре
  - `ListingMapper` — константа `LABEL_NEGOTIABLE = "Договорная"`, `default`-метод `resolveNegotiableLabel()` в интерфейсе

- **PR #285 — Исправлен URL RealtRoomSaleConnector: /sale-room/object/ → /sale-rooms/object/ (issue #283)**
  - `application.yml` — `realt-room-sale.object-path-prefix` исправлен с `/sale-room/object/` на `/sale-rooms/object/`
  - `RealtRoomSaleConnectorTest` — обновлены `setUp()` и assertion в `should_use_room_sale_specific_source_url_in_listings`
  - Flyway V40 — UPDATE всех существующих записей `listings` где `source_id = REALT_ROOM_SALE` и `source_url LIKE '%/sale-room/object/%'`

- **PR #284 — Telegram-карточка показывает читаемое название источника (issue #281)**
  - `ListingFormatter.resolveSourceDisplayName()` — prefix-matching по верхнему регистру вместо точного lowercase lookup: `REALT*` → `Realt`, `ONLINER*` → `Onliner`, `KUFAR*` → `Kufar`
  - Исправляет: `REALT_HOUSE_SALE`, `REALT_ROOM`, `REALT_ROOM_SALE`, `REALT_SALE`, `ONLINER_SALE` отображались как сырые ID вместо читаемых названий

- **PR #280 — Исправлен URL RealtHouseSaleConnector: /sale/houses/ → /sale/cottages/ (issue #278)**
  - `application.yml` — обновлены `listings-path` (`/sale/houses/` → `/sale/cottages/`) и `object-path-prefix` (`/sale-house/object/` → `/sale-cottages/object/`) для коннектора `realt-house-sale`; URL остаётся переопределяемым через env vars без перекомпиляции
  - `RealtHouseSaleConnectorTest` — обновлены хардкоженные URL в setUp(), исправлен тест `should_use_house_sale_specific_source_url_in_listings`, добавлен тест `should_return_empty_list_when_404_received` для документирования graceful degradation при смене URL источника

### Changed
- **PR #279 — Delta sync cron для новых Realt коннекторов: 20 мин → 5 мин (issue #277)**
  - `flatio.sync.realt-room.delta.cron`: `0 */20 * * * *` → `0 */5 * * * *`
  - `flatio.sync.realt-room-sale.delta.cron`: `0 */20 * * * *` → `0 */5 * * * *`
  - `flatio.sync.realt-house-sale.delta.cron`: `0 */20 * * * *` → `0 */5 * * * *`
  - Все три значения переопределяемы через env vars; реальная нагрузка на realt.by ограничена Resilience4j rate limiter независимо от частоты cron

### Added
- **PR #276 — Новые Realt коннекторы: комнаты (аренда/продажа) и дома (продажа) (issue #273)**
  - `RealtHtmlParser` — общий парсер `__NEXT_DATA__` JSON для всех realt.by коннекторов (устраняет ~300 строк дублирования между `RealtConnector` и `RealtSaleConnector`); параметры категории передаются через `RealtPageContext` record
  - `RealtRoomConnector` → `/rent/room-for-long/` (RENT + ROOM): использует `realtRestClient` и `connector-realt` Resilience4j
  - `RealtRoomSaleConnector` → `/sale/rooms/` (SELL + ROOM): использует `realtSaleRestClient` и `connector-realt-sale` Resilience4j
  - `RealtHouseSaleConnector` → `/sale/cottages/` (SELL + HOUSE): использует `realtSaleRestClient` и `connector-realt-sale` Resilience4j
  - Для каждого нового коннектора: `@ConfigurationProperties` record, full-sync и delta-sync Job (`@Scheduled` + `@EventListener(ApplicationReadyEvent)`)
  - Flyway V37–V39 — записи источников `REALT_ROOM`, `REALT_ROOM_SALE`, `REALT_HOUSE_SALE` в таблице `sources`
  - `SourceRepositoryIT` — обновлён: ожидает 7 активных источников для BY (было 4)
  - Тесты: `RealtHtmlParserTest` (18 тестов), `RealtRoomConnectorTest`, `RealtRoomSaleConnectorTest`, `RealtHouseSaleConnectorTest`

### Fixed
- **PR #238 — Проверка размера фото до отправки в Telegram; SendDocument для файлов 10–50 MB (issue #233)**
  - `SearchResultSender.sendCard()` — размер буфера проверяется **до** вызова Telegram API:
    файлы ≤ 10 MB → `SendPhoto` (основной путь); 10–50 MB → `SendDocument` (документ вместо фото);
    > 50 MB → текстовая карточка
  - Лимиты вынесены в `@Value`-поля (`${telegram.bot.max-photo-bytes:10485760}`,
    `${telegram.bot.max-document-bytes:52428800}`) для инъекции в тестах без аллокации 50 MB-массивов
  - `PhotoCard` — приватный record для группировки параметров отправки (устраняет 7-параметрные сигнатуры)
  - Elapsed time download + send логируется на DEBUG (`elapsed=Nms`) для измерения P95
  - 3 новых теста: document path, too-large fallback, document upload failure

- **PR #237 — Исправлено некорректное отображение USD-цены в картчоках Realt.by (issue #232)**
  - `RealtConnector.toRawListing()` — `priceUsd` теперь всегда `null` для объявлений Realt:
    поле `price` уже в USD, BYN-эквивалент будет заполнен будущим слоем конвертации курсов
  - До фикса: `priceUsd = price` при `currency = "USD"` → `ListingFormatter` рендерил `$650 (650 BYN)`
  - После фикса: `priceUsd = null` → ветка `"USD".equals(currency) → $650` работает корректно
  - Обновлены 3 существующих теста (валидировали неверное поведение), добавлен тест с фикстурой
    `listing-with-usd-price.html` (`priceCurrency=840`, `price=650`)

- **PR #236 — Proxy-загрузка фото CDN перед отправкой в Telegram (issue #233)**
  - `PhotoProxyClient` (`telegram/handler/`) — загружает байты фото на нашей стороне через RestClient;
    `download(url, listingId)` → `Optional<byte[]>`; `Optional.empty()` при любой ошибке (4xx, 5xx, timeout)
  - `PhotoDownloadConfig` (`telegram/config/`) — `@Bean("photoDownloadRestClient")` с таймаутами 5s/5s
  - `SearchResultSender.sendCard()` — вместо передачи URL в Telegram теперь: скачать байты →
    при пустом Optional → текстовая карточка; при байтах → `InputFile(ByteArrayInputStream, filename)` → `SendPhoto`
  - Устраняет `400 Bad Request: failed to get HTTP URL content` при попытке Telegram получить фото
    с CDN `content.onliner.by` (CDN блокирует IP-адреса серверов Telegram)
  - 7 новых тестов `PhotoProxyClientTest`, обновлён `SearchResultSenderTest`

- **PR #235 — Delta sync для RealtConnector + RealtDeltaSyncJob (issue #231)**
  - `RealtConnector.fetchDelta(Instant since)` — проходит страницы realt.by (новейшие первые),
    останавливается при `publishedAt < since`; та же Resilience4j-цепочка что у `fetch()`
  - `RealtDeltaSyncJob` (`com.flatio.integration.realt.scheduler`) — каждые 15 минут;
    читает последний успешный `SyncRun` из БД: при наличии курсора → DELTA, иначе → FULL (первый запуск / длительный сбой)
  - `SyncRunService.findLastSuccessfulRunAt(String sourceId)` — новый метод для source-специфичного курсора;
    использует индекс `(source_id, started_at DESC)` из Flyway V27
  - `SyncRunServiceImplTest` — 3 новых теста на `findLastSuccessfulRunAt`
  - 536 тестов, 0 failures

- **PR #234 — Кнопки навигации при пустых результатах поиска (issue #230)**
  - `SearchResultSender.sendNoResultsMessage()` — при пустом поиске бот показывает inline-клавиатуру
    с кнопками «Изменить фильтры» и «Главное меню» вместо голого текстового сообщения
  - Все три точки входа (`handle`, `handleLastSearch`, `handlePageCallback`) используют единый метод
  - `ACTION_MENU = "action:menu"` — новая публичная константа для роутинга в `FlatioBot`
  - `StartCommandHandler.buildMenuMessage(chatId)` — публичный метод для вызова из callback-контекста

### Added
- **PR #226 — RealtFullSyncJob: ежедневный полный обход realt.by (issue #225)**
  - `RealtFullSyncJob` (`com.flatio.integration.realt.scheduler`) — ежедневный полный синк по образцу
    `OnlinerFullSyncJob`; `@Scheduled(cron = "${flatio.sync.realt.full.cron}")` — запуск в 04:00
    (после Onliner 02:00 / OnlinerSale 03:00)
  - `@EventListener(ApplicationReadyEvent.class)` — при старте запускает полный синк если БД пуста по источнику REALT
  - Пустой `fetch()` → деактивация пропускается (защита от массового удаления при временной недоступности источника)
  - `SyncRunService.record()` фиксирует SUCCESS / FAILURE с полными счётчиками и длительностью
  - `CallNotPermittedException` от circuit breaker — `log.warn`, не пробрасывается
  - `application.yml` — `flatio.sync.realt.full.cron: ${FLATIO_SYNC_REALT_FULL_CRON:0 0 4 * * *}`
  - `RealtFullSyncJobTest` — 10 unit-тестов (startup: пустая БД / непустая БД / исключение;
    scheduled: happy path / пустой fetch / deactivation; изоляция ошибок: source not found / fetch throws / CB open;
    запись результатов: SUCCESS / FAILURE)

- **PR #224 — RealtConnector: парсер объявлений realt.by через __NEXT_DATA__ JSON (issue #42)**
  - `RealtConnector` (`com.flatio.integration.realt.client`) — коннектор для Realt.by (Next.js SSR);
    данные извлекаются из `<script id="__NEXT_DATA__" type="application/json">`, а не из HTML-разметки;
    пагинация через `a[data-testid='nextBtn']`; ограничение `MAX_PAGES = 100` страниц за один запуск
  - `RealtProperties` — `@ConfigurationProperties(prefix = "connector.realt")`: `baseUrl`, `sourceId`,
    `regionCode`, `listingsPath`, `objectPathPrefix`
  - `RealtClientConfig` — `@Bean("realtRestClient")` с Chrome User-Agent, connect timeout 5s, read timeout 10s
  - Цена из JSON `price` (USD, `priceCurrency=840` → `"USD"`); `companyUuid` → `isOwner`: `null` — поле
    отсутствует (field missing), JSON null → `true` (private owner), UUID-строка → `false` (агентство)
  - Resilience4j: rate limiter 1 req/2s, retry 3 попытки (2s→4s→8s), circuit breaker (5 сбоев, 60s)
  - Защита: `isSafeImageUrl()` — SSRF guard (только `https://` схемы с непустым хостом для фото);
    `MAX_NEXT_DATA_SIZE = 5 MB` — OOM guard до `objectMapper.readTree()`;
    `.replaceAll("[\r\n\t]", "_")` на внешних данных в логах — защита от log injection
  - Flyway V31 — запись источника `REALT` в таблице `sources`
  - `RealtConnectorTest` — 29 unit-тестов; фикстуры: `src/test/resources/fixtures/realt/`

### Security
- **PR #220 — Rate limiting для публичных/аутентифицированных REST-эндпоинтов (issue #219)**
  - `RateLimitFilter` (новый) — `OncePerRequestFilter`, лимитирует `/api/v1/**` по вызывающей стороне:
    `/api/v1/auth/**` по клиентскому IP (`X-Real-IP`, который nginx выставляет в `$remote_addr` и
    клиент не может подделать — `X-Forwarded-For` намеренно не используется, так как
    `$proxy_add_x_forwarded_for` лишь дополняет значение от клиента, а не заменяет его), остальной
    `/api/v1/**` — по `sub` из JWT
  - Один `RateLimiter` на каждый вызывающий ключ через `RateLimiterRegistry.rateLimiter(key, configName)`,
    конфиг — `resilience4j.ratelimiter.configs.api-auth-telegram` / `api-authenticated` (именно `configs`,
    не `instances` — туда лимиты для коннекторов, общий лимитер на всех вызывающих не годится для входящих запросов)
  - Лимиты настраиваются через env: `RATE_LIMIT_AUTH_REQUESTS`, `RATE_LIMIT_AUTH_REFRESH_SECONDS`,
    `RATE_LIMIT_API_REQUESTS`, `RATE_LIMIT_API_REFRESH_SECONDS`
  - При превышении — `429` в формате `ErrorResponse`, без утечки деталей реализации лимитера
  - `SecurityConfig` — фильтр подключён через `addFilterAfter(rateLimitFilter, JwtAuthenticationFilter.class)`

### Added
- **PR #218 — Выдача JWT через обмен Telegram WebApp initData (issue #217)**
  - `POST /api/v1/auth/telegram` (новый, публичный) — до этого `/api/v1/**` требовал JWT, который
    нечем было выдать (`JwtService.generateToken` не вызывался ни одним контроллером)
  - `TelegramInitDataValidator` (новый) — проверяет подпись `initData` по официальному алгоритму
    Telegram (HMAC-SHA256, секрет — `HMAC_SHA256(<bot_token>, "WebAppData")`, дальше
    `hash = hex(HMAC_SHA256(data_check_string, secret_key))`), сравнение хэшей — `MessageDigest.isEqual`
    (защита от timing-атаки); `auth_date` не старше 24 часов
  - `AuthService`/`AuthServiceImpl` — находит или создаёт пользователя (тот же путь, что `/start` в боте)
    и выдаёт токен через существующий `JwtService`; роль в JWT берётся из `User.role` в БД, не из запроса
  - `SecurityConfig` — `/api/v1/auth/**` теперь `permitAll` (это и есть точка выдачи токена)
  - `InvalidTelegramAuthException` → 401 через `GlobalExceptionHandler`

### Fixed
- **PR #215 — Убран сломанный шаг выбора города из визарда поиска (issues #213, #214)**
  - Поиск с заданным `cityId` крашился (`InvalidDataAccessApiUsageException`): `ListingServiceImpl.buildSearchSpec`
    строил предикат по `root.get("cityRef")`, но у `Listing` нет такой relation, только строковое поле `city`
  - Шаг `CITY` (ручной выбор + геолокация) убран из `SearchFilterWizard`, `FilterKeyboardFactory`,
    `FilterCallbackHandler`, `FlatioBot` — после `OWNER_ONLY` визард ведёт прямо на `KEYWORD`
  - `V30__add_city_to_search_vector.sql` — `city` добавлен в полнотекстовый `search_vector` вместе
    с `title`/`description`/`address`, чтобы город можно было найти через обычный ключевой поиск

### Added
- **PR #216 — Нагрузочный тест GET /listings — p95<500ms (issue #37, NFR-PERF-1)**
  - `scripts/load-test/listings-search.js` — k6-сценарий: 50 vUsers, 5 минут, `GET /api/v1/listings?dealType=RENT`,
    пороги `p95<500ms`/`p99<1000ms`/`error rate<0.1%`
  - `scripts/load-test/seed-listings.sql` — ручной (не Flyway) скрипт наполнения disposable БД
  - Локальный прогон (нет доступа к staging/prod): p95=82.74ms, p99=118.39ms, error rate=0.01% —
    пороги пройдены с большим запасом
  - `docs/local-setup.md` — раздел «Load Testing»
- **PR #209 — Двойная защита Telegram webhook от утечки токена (issue #205)**
  - `docker/nginx/nginx.conf` — `location ~* "^/[0-9]+:[A-Za-z0-9_-]+$"` с `access_log off`:
    токен бота больше не появляется в access-логах nginx на каждое входящее обновление от Telegram
  - `BotConfig` — добавлено поле `webhookSecretToken` (`TELEGRAM_WEBHOOK_SECRET_TOKEN` env var, опциональный)
  - `TelegramWebhookConfig.registerWebhook()` — передаёт `secretToken` в `SetWebhook.secretToken()`, если настроен
  - `TelegramWebhookSecretFilter` (новый) — `OncePerRequestFilter`; проверяет заголовок
    `X-Telegram-Bot-Api-Secret-Token` с timing-safe сравнением (`MessageDigest.isEqual`);
    отклоняет запрос с HTTP 403 при несовпадении или отсутствии заголовка
  - `TelegramWebhookSecurityConfig` (новый) — `SecurityFilterChain` с `@Order(1)` и `AntPathRequestMatcher`
    для пути вебхука; активен только вне профиля `local`
  - Фильтр opt-in: без `TELEGRAM_WEBHOOK_SECRET_TOKEN` работает в no-op режиме (backward-compatible)
  - `docs/architecture.md` — задокументирован двухслойный подход к безопасности вебхука

### Added
- **PR #210 — Автосохранение фильтра поиска после завершения мастера (issue #157)**
  - `SearchResultSender.handle()` — после успешного поиска (при наличии результатов) фильтр автоматически
    сохраняется в `user_saved_searches` через `UserSavedSearchService.save(telegramId, filter)`
  - Graceful degradation: ошибка сохранения перехватывается, логируется с `log.error`, пользователь
    получает результаты поиска независимо от успеха сохранения
  - Фильтр не сохраняется при нулевых результатах поиска (нет смысла сохранять фильтр без совпадений)
  - Маппинг `SearchFilterState → SearchFilter`: `rooms → roomsMin`, `ownerOnly → isOwner`,
    `query → keyword`; `regionCode = null` (state хранит `cityId`, не код региона)

### Security
- **PR #202 — Ужесточение SSL nginx + ограничение размера тела запроса (issues #200, #201)**
  - `docker/nginx/nginx.conf` — заменён cipher suite на Mozilla Intermediate profile:
    `ECDHE-ECDSA-AES128-GCM-SHA256`, `ECDHE-RSA-AES128-GCM-SHA256`, `ECDHE-ECDSA-AES256-GCM-SHA384`,
    `ECDHE-RSA-AES256-GCM-SHA384`, `ECDHE-ECDSA-CHACHA20-POLY1305`, `ECDHE-RSA-CHACHA20-POLY1305`,
    `DHE-RSA-AES128-GCM-SHA256`, `DHE-RSA-AES256-GCM-SHA384`
  - `ssl_prefer_server_ciphers off` — клиент выбирает cipher (современный best practice)
  - `ssl_session_tickets off` — отключены TLS session tickets для предотвращения Forward Secrecy downgrade
  - HSTS обновлён: добавлен `preload` флаг (`max-age=31536000; includeSubDomains; preload`)
  - Добавлены HTTP security headers: `Referrer-Policy: strict-origin-when-cross-origin`,
    `Permissions-Policy: geolocation=(), microphone=(), camera=()`, `X-XSS-Protection: 1; mode=block`
  - Все `add_header` — с флагом `always` (заголовки применяются и к ошибочным ответам)
  - `client_max_body_size 10m` — ограничение размера тела запроса для защиты от DoS
  - `proxy_send_timeout 60s` — добавлен таймаут отправки ответа через proxy

### Added
- **PR #199 — HTTPS prod деплой + оптимизация CI/CD (issues #41, #197)**
  - `docker/docker-compose.prod.example.yml` — шаблон продакшн Docker Compose: сервисы `flatio-app`,
    `postgres`, `nginx`, `certbot`; все переменные окружения через `.env`; named volume для
    Let's Encrypt сертификатов; помечен как `.example` — реальный файл в `.gitignore`
  - `docker/nginx/nginx.conf` — nginx reverse proxy с HTTPS: HTTP→HTTPS редирект (301),
    SSL терминация с Let's Encrypt, proxy к `flatio-app:8080`; базовые security headers
    (`X-Frame-Options DENY`, `X-Content-Type-Options nosniff`, HSTS)
  - `docs/local-setup.md` — полная инструкция по локальному запуску и деплою на VPS:
    требования, Docker Compose, переменные окружения, SSL настройка, обновление приложения
  - `.gitignore` — добавлен `docker/docker-compose.prod.yml` (реальный файл с секретами)
  - `.github/workflows/ci.yml` — оптимизация pipeline (#197):
    - Job'ы `build` и `test` объединены в один (`build-and-test`) — исключает двойной checkout и Gradle daemon
    - Job `docker-build` теперь запускается только для веток `develop` и `master` (не для `feature/**`, `fix/**`)

### Added
- **PR #193 — Коннектор Onliner ПРОДАЖА + ценовые фильтры по типу сделки (issue #192)**
  - `com.flatio.integration.onliner.client.OnlinerSaleConnector` — коннектор к `pk.api.onliner.by`
    для объявлений о продаже (`deal_type=SELL`); отдельный домен, отличная структура ответа от аренды
  - DTO пакета `com.flatio.integration.onliner.dto`: `OnlinerSaleApartment`, `OnlinerSaleArea`,
    `OnlinerSaleSeller`, `OnlinerSaleSearchResponse` — отражают разницу в полях:
    `number_of_rooms` (целое число) вместо `rent_type`, `seller.type` вместо `contact.owner`
  - `com.flatio.integration.onliner.config.OnlinerSaleClientConfig` + `OnlinerSaleProperties` —
    конфиг коннектора через `@ConfigurationProperties(prefix = "connector.onliner-sale")`
  - `OnlinerSaleDeltaSyncJob` + `OnlinerSaleFullSyncJob` — джобы синхронизации, зеркалят
    аналоги аренды; Resilience4j (rate limiter, retry, circuit breaker) для `connector-onliner-sale`
  - `FilterKeyboardFactory` — клавиатура ценовых диапазонов теперь зависит от типа сделки:
    аренда показывает диапазоны BYN/мес (до 4 000), продажа — полную стоимость BYN (до 400 000+)
  - `SearchFilterWizard.applyPriceRange` — разрешает корректные пороги из `state.dealType`
  - Flyway V28 — запись источника `ONLINER_SALE` в таблице `sources`
  - `application.yml` — новые секции Resilience4j и коннектора для `connector-onliner-sale`
  - Защита: `MAX_RETRY_AFTER_SECONDS = 60L` в обоих коннекторах (аренда и продажа) —
    кап заголовка `Retry-After` для защиты от блокировки scheduler-потока сервером

### Changed
- **PR #196 — Пороги ценовых фильтров продажи вынесены в конфигурацию (issue #195)**
  - `com.flatio.config.SellPriceFilterProperties` — новый `@ConfigurationProperties(prefix = "flatio.filter.price.sell")`:
    три порога `lowMax`, `mediumMax`, `highMax` (BigDecimal); валидируются `@NotNull @Positive` при старте
  - `com.flatio.config.FilterPriceConfig` — `@Configuration` бин, владеет `@EnableConfigurationProperties`;
    изолирует Telegram-конфиг от конфига цен
  - `FilterKeyboardFactory` — метки кнопок строятся динамически из конфига; `formatPrice` использует
    `setScale(0, HALF_UP).longValueExact()` для корректного форматирования
  - `SearchFilterWizard` — хардкоды `100_000`, `200_000`, `400_000` BYN заменены на значения из `SellPriceFilterProperties`
  - `application.yml` — новая секция `flatio.filter.price.sell.*` с env-переменными:
    `FLATIO_FILTER_PRICE_SELL_LOW_MAX`, `FLATIO_FILTER_PRICE_SELL_MEDIUM_MAX`, `FLATIO_FILTER_PRICE_SELL_HIGH_MAX`

### Added
- **PR #191 — DataFreshnessHealthIndicator и аудит-таблица sync_runs (issue #38)**
  - `com.flatio.domain.source.SyncRun` — новый Entity: аудитная запись одного запуска синхронизации;
    поля: `source`, `syncType`, `status`, `startedAt`, `finishedAt`, `fetchedCount`, `addedCount`,
    `updatedCount`, `errorCount`, `errorMessage`
  - Enum `SyncRunStatus`: `SUCCESS` | `FAILURE`; enum `SyncType`: `DELTA` | `FULL`
  - `com.flatio.repository.SyncRunRepository` — `findTopBySourceCodeOrderByStartedAtDesc()` для
    получения последнего запуска по источнику
  - `com.flatio.service.SyncRunService` + `SyncRunServiceImpl` — сервис записи результатов синка;
    `SyncRunRequest` Record инкапсулирует параметры создания записи
  - `com.flatio.scheduler.DataFreshnessHealthIndicator` — реализует Spring Boot `HealthIndicator`;
    статус `DOWN` если последний успешный синк старше порога `flatio.freshness.max-age-minutes`
    (default 30 мин для дельта, 1440 для полного)
  - `com.flatio.scheduler.DataFreshnessWatchdog` — `@Scheduled` компонент; периодически вызывает
    `DataFreshnessHealthIndicator` и логирует предупреждение при статусе `DOWN`
  - `OnlinerDeltaSyncJob` + `OnlinerFullSyncJob` — интегрированы с `SyncRunService`:
    записывают `SUCCESS` после успешного синка и `FAILURE` в catch-блоках
  - Flyway V26 — DDL таблицы `sync_runs` с FK на `sources`
  - Flyway V27 — индексы `(source_id, started_at DESC)` и `(status, started_at DESC)`
  - `application.yml` — `flatio.freshness.max-age-minutes`

### Changed
- **PR #189 — CI pipeline: GitHub Actions build/test/docker-build (issue #40)**
  - `.github/workflows/ci.yml` — полностью переработан: три последовательных job'а через `needs`:
    - `build`: `./gradlew build -x test --no-daemon` + кэш Gradle через `actions/setup-java cache: gradle`
    - `test`: `./gradlew test integrationTest --no-daemon` — запускает unit и Testcontainers IT-тесты; Docker доступен нативно на `ubuntu-latest`
    - `docker-build`: `docker build -t flatio:${{ github.sha }}`; push в Docker Hub выполняется только при `push` в `master` через `secrets.DOCKER_USERNAME` / `secrets.DOCKER_PASSWORD`
  - Триггеры: `push` в `feature/**`, `fix/**`, `docs/**`, `develop`, `master`; `pull_request` в `develop`, `master`
  - `build.gradle.kts` — добавлена задача `integrationTest` (`dependsOn("test")`) — алиас, позволяющий вызывать `./gradlew test integrationTest` в CI без ошибки "Task not found"

### Added
- **PR #188 — Поддержка RENT_DAILY в фильтре поиска API (issue #92)**
  - `ListingSearchCriteria.dealType` — `@Schema` обновлён: `example = "RENT_DAILY"`, описание перечисляет все допустимые значения `RENT`, `SELL`, `RENT_DAILY`
  - `ListingResponse.dealType` — описание `@Schema` включает `RENT_DAILY`
  - Бизнес-логика не изменялась: `buildSearchSpec` и FTS-путь обрабатывали все значения `DealType` без ограничений

### Fixed
- **PR #187 — Geocoding language ru + deactivation of stale listings (issues #185, #186)**
  - **#186 — Название города на русском из Nominatim:**
    - `NominatimProperties` — добавлено поле `language`; читается из `${NOMINATIM_LANGUAGE:ru}` (`application.yml`)
    - `NominatimClient` — добавлен параметр `.queryParam("accept-language", language)` в URI `/reverse`; ранее Nominatim возвращал названия на белорусском (дефолт OSM для RB)
    - Существующие записи в кэше не мигрируются — фикс применяется к новым geocoding-вызовам
  - **#185 — Деактивация устаревших объявлений:**
    - `ListingIngestionServiceImpl.applyMissedSyncPenalty(Source, Set<String>)` — для ACTIVE объявлений, отсутствующих в последнем fetch, инкрементирует `missedSyncsCount`; при достижении порога `flatio.sync.inactive-threshold` (default: 3) устанавливает статус `INACTIVE`
    - `ListingRepository.incrementMissedSyncsForAbsent()` + `deactivateByMissedSyncsThreshold()` — два JPQL `@Modifying` запроса
    - `OnlinerFullSyncJob.performFullSync()` — вызывает `applyMissedSyncPenalty()` после каждого полного синка
    - `application.yml` — `flatio.sync.inactive-threshold: ${FLATIO_SYNC_INACTIVE_THRESHOLD:3}`

- **PR #184 — Восстановление адреса в карточке Telegram + исправление сортировки (issues #180, #181)**
  - **#181 — Пропавший адрес в карточке:**
    - `ListingSummaryResponse` — добавлено поле `address`; MapStruct маппит автоматически из `Listing.address`
    - `ListingFormatter.formatLocation` — принимает три параметра (`address`, `district`, `city`); `address` имеет приоритет, `district`/`city` — fallback
  - **#180 — Порядок сортировки листингов:**
    - `SearchResultSender` — сортировка изменена с `createdAt DESC` на `publishedAt DESC NULLS LAST` в обоих методах (`handle` и `handlePageCallback`)
    - Причина: карточка отображает `publishedAt`, а сортировка по `createdAt` (время вставки в БД) создавала видимое несоответствие дат

### Fixed
- **PR #172 — Исправление декодирования URL фото Onliner (issue #170)**
  - `OnlinerConnector.resolvePhotoUrl()` — исправлено декодирование imgproxy URL:
    Onliner разбивает base64-закодированный оригинальный URL на несколько сегментов пути
    по 16 символов (разделитель `/`); теперь все сегменты после трансформаций (`w:`, `h:`, `dpr:`)
    объединяются перед декодированием; ранее брался только последний сегмент (`Zw` → `"g"`)
  - Результат после исправления: `https://content.onliner.by/...` — валидный прямой URL изображения

- **PR #171 — Параллельная обработка Telegram апдейтов (issue #167)**
  - `com.flatio.config.TelegramExecutorConfig` — новый `@Configuration` бин `ThreadPoolTaskExecutor`
    с именем `telegramUpdateExecutor`; `CallerRunsPolicy` обеспечивает back-pressure при перегрузке;
    graceful shutdown: ожидает завершения задач до 30 секунд
  - `FlatioBot.consume()` — каждый апдейт отправляется в `telegramUpdateExecutor` через
    `executor.execute()`; апдейты разных пользователей обрабатываются независимо и параллельно
  - `application.yml` — новая секция `telegram.bot.executor.*` с env-переменными:
    `TELEGRAM_EXECUTOR_CORE_POOL_SIZE` (default 10), `TELEGRAM_EXECUTOR_MAX_POOL_SIZE` (default 20),
    `TELEGRAM_EXECUTOR_QUEUE_CAPACITY` (default 100)

- **PR #169 — Валидация URL фото перед отправкой в Telegram (issue #166)**
  - `SearchResultSender.sendCard()` — перед вызовом `SendPhoto` проверяется, что `photoUrl`
    начинается с `http://` или `https://`; невалидный URL (например, `"g"`) заменяется
    на `noPhotoUrl` (placeholder) без лишнего вызова Telegram API

- **PR #168 — Глобальный обработчик исключений в боте (issue #165)**
  - `FlatioBot.handleUpdate()` — добавлен `try-catch (Exception)` верхнего уровня;
    непойманное исключение в любом обработчике больше не останавливает обработку всех
    последующих апдейтов; ошибка логируется с `updateId` и не пробрасывается наверх

### Added
- **PR #151 — Пагинация, полнотекстовый поиск, /help, меню бота, фото-fallback (issues #30, #31, #140, #148, #149)**
  - `com.flatio.telegram.config.BotCommandsRegistrar` — `@PostConstruct` bean; регистрирует команды
    `/start`, `/search`, `/help` через `SetMyCommands` при старте приложения
  - `com.flatio.telegram.command.HelpCommandHandler` — обработчик команды `/help` и callback `action:help`;
    возвращает пользователю справочное сообщение
  - `com.flatio.telegram.state.SearchSession` — in-memory сессия результатов поиска пользователя;
    хранит страницы результатов, TTL 30 минут; поддерживает `PAGE:NEXT` / `PAGE:PREV` callbacks
  - Пагинация результатов поиска — 5 карточек на страницу; навигация через inline-кнопки
    `PAGE:NEXT` и `PAGE:PREV`; состояние сессии хранится в `SearchSession`
  - Шаг `KEYWORD` в `SearchFilterWizard` — свободный текстовый ввод ключевого слова поиска
    (или кнопка «Пропустить»); введённое значение передаётся в FTS-запрос через `search_vector`
  - Photo fallback в `SearchResultSender` — при ошибке отправки фото или отсутствии `photoUrl`
    карточка отправляется как текстовое сообщение с заглушкой «📷 Фото не добавлено»

### Fixed
- **PR #150 — Исправление 6 проблем (#137, #138, #139, #145, #146, #147)**
  - **#137** — `com.flatio.telegram.config.TelegramStartupValidator` — `@PostConstruct` bean;
    валидирует токен бота и регистрацию вебхука при старте приложения; блокирует запуск при
    некорректной конфигурации
  - **#138** — `SearchFilterWizard`: шаг `ROOMS` пропускается в обоих направлениях (вперёд и назад)
    если выбранный `propertyType` — `ROOM`; пользователь переходит сразу к шагу `PRICE`
  - **#139** — `ListingIngestionServiceImpl.isPriceChanged()` — сравнивает `priceUsd` вместо BYN-цены,
    чтобы не записывать ложные записи в `price_history` при изменении курса валют
  - **#145** — `ListingSummaryResponse` расширен двумя полями: `priceUsd` и `propertyType`;
    `ListingFormatter` теперь отображает формат `$USD (BYN)` при наличии `priceUsd`;
    `SearchResultSender` сортирует результаты по `createdAt DESC`
  - **#146** — `OnlinerDeltaSyncJob`: добавлен отдельный `catch (DataAccessException)` с уровнем
    логирования WARN вместо общего `catch (Exception)` для ошибок работы с БД
  - **#147** — шаг `OWNER_ONLY` добавлен в `SearchFilterWizard`; предикат `ownerOnly` добавлен
    в `buildSearchSpec()` для фильтрации объявлений только от собственников

### Added
- **PR #143 — Карточка объявления: форматирование и отправка результатов поиска (issue #29)**
  - `com.flatio.telegram.formatter.ListingFormatter` — форматирует `ListingSummaryResponse` в HTML-caption
    и `InlineKeyboardMarkup`; caption состоит из трёх зон: заголовок + цены (BYN и USD), геолокация + площадь,
    время публикации + источник; единственная кнопка «Открыть объявление →» содержит `sourceUrl`
  - `com.flatio.telegram.handler.SearchResultSender` — обрабатывает callback `FILTER:SEARCH`;
    вызывает `ListingService.search()` с критериями из `SearchFilterState`, затем отправляет карточки
    через `ListingFormatter`; каждая карточка отправляется методом `sendPhoto` / `sendMessage`
  - `ListingSummaryResponse` — добавлено поле `sourceUrl` (URL объявления на сайте-источнике)
  - `FlatioBot` — роутинг callback `FILTER:SEARCH` передан в `SearchResultSender`

- **PR #136 — Пошаговый wizard выбора фильтров поиска (issue #28)**
  - `com.flatio.telegram.state.FilterStep` — enum шагов wizard: `DEAL_TYPE`, `PROPERTY_TYPE`,
    `ROOMS`, `PRICE`, `DONE`
  - `com.flatio.telegram.state.SearchFilterState` — in-memory состояние фильтра пользователя;
    хранится в `ConcurrentHashMap<Long, SearchFilterState>` (ключ — Telegram userId)
  - `com.flatio.telegram.state.SearchFilterWizard` — Spring `@Component`; управляет переходами
    между шагами FSM: определяет текущий шаг, применяет выбор пользователя, возвращает следующий шаг
  - `com.flatio.telegram.keyboard.FilterKeyboardFactory` — строит `InlineKeyboardMarkup`
    для каждого шага wizard
  - `com.flatio.telegram.callback.FilterCallbackHandler` — обрабатывает callback-запросы
    с префиксом `FILTER:*`; при достижении шага `DONE` инициирует action `search`
  - `FlatioBot` — добавлена обработка callback queries; роутинг `FILTER:*` передан
    в `FilterCallbackHandler`

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
