# Dev Standards — Flatio

Этот файл читают `software-engineer`, `quality-assurance-engineer`, `technical-reviewer`, `security-engineer`.
Стандарты обязательны. Отклонение — причина для Request Changes от technical-reviewer.

---

## Архитектура слоёв

Строгое разделение ответственности. Логика не перетекает между слоями.

```
Controller → Service → Repository → Database
               ↓
            Domain (Entity)
               ↓
            DTO (Record)
```

**Controller** — только HTTP: принять запрос, вызвать сервис, вернуть ответ. Никакой бизнес-логики.
**Service** — вся бизнес-логика. Транзакции (`@Transactional`) только здесь.
**Repository** — только запросы к БД через Spring Data JPA.
**Domain** — JPA Entity. Никаких аннотаций из других слоёв (не Jackson, не validation).
**DTO** — Java Record. Только данные, никакой логики.

---

## Именование

### Пакеты
```
com.flatio.domain.listing
com.flatio.service
com.flatio.service.impl
com.flatio.web.controller
com.flatio.web.dto
com.flatio.web.mapper
com.flatio.connector.core
com.flatio.connector.realt    # пример конкретного источника

- `com.plantcare.bot.domain` — entity, value objects
- `com.plantcare.bot.repository` — Spring Data репозитории
- `com.plantcare.bot.service` — бизнес-логика
- `com.plantcare.bot.telegram` — хендлеры Telegram, апдейты, клавиатуры
- `com.plantcare.bot.scheduler` — крон-задачи, отправка напоминаний
- `com.plantcare.bot.config` — `@Configuration` и `@ConfigurationProperties`
- `com.plantcare.bot.web` — actuator-расширения, healthcheck-эндпоинты

**Telegram-слой не смешивать с бизнес-логикой.** Хендлер парсит апдейт → дёргает сервис → формирует ответ. Никаких репозиториев в хендлерах.

```

### Классы
| Тип | Суффикс | Пример |
|-----|---------|--------|
| JPA Entity | нет | `Listing`, `User`, `Subscription` |
| DTO (Record) | `Request` / `Response` / `Dto` | `ListingResponse`, `SearchRequest` |
| Controller | `Controller` | `ListingController` |
| Service (интерфейс) | `Service` | `ListingService` |
| Service (реализация) | `ServiceImpl` | `ListingServiceImpl` |
| Repository | `Repository` | `ListingRepository` |
| Mapper (MapStruct) | `Mapper` | `ListingMapper` |
| Коннектор | `Connector` | `RealtConnector` |
| Exception | `Exception` | `ListingNotFoundException` |
| Конфиг | `Config` | `SecurityConfig`, `JwtConfig` |

### Методы и переменные
- `camelCase` для методов и переменных
- Глаголы для методов: `findById`, `createListing`, `parseSource`
- Булевы переменные с префиксом: `isActive`, `hasSubscription`
- Константы: `UPPER_SNAKE_CASE`

### REST endpoints
```
GET    /api/v1/listings          — список с фильтрами
GET    /api/v1/listings/{id}     — одно объявление
POST   /api/v1/listings/search   — поиск с телом запроса
POST   /api/v1/subscriptions     — создать подписку
DELETE /api/v1/subscriptions/{id} — удалить подписку
GET    /api/v1/regions           — список регионов
```
Версионирование через URL (`/api/v1/`). Всегда.

---

## DTO — Java Records

```java
// Правильно
public record ListingResponse(
    Long id,
    String title,
    BigDecimal price,
    String currency,
    RegionDto region,
    Instant createdAt
) {}

// Запрос с валидацией
public record SearchRequest(
    @NotNull RegionCode regionCode,
    @Min(0) BigDecimal priceMin,
    @Max(100_000_000) BigDecimal priceMax,
    @Min(1) @Max(500) Integer roomsMin
) {}
```

- DTO — всегда Record, не класс с геттерами
- Валидация (`@Valid`, `@NotNull`, `@Min`) — только в DTO, не в Entity
- Nullable поля — `Optional<T>` или явный комментарий

---

## Маппинг — MapStruct

```java
@Mapper(componentModel = "spring")
public interface ListingMapper {
    ListingResponse toResponse(Listing listing);
    List<ListingResponse> toResponseList(List<Listing> listings);
}
```

- Маппер — интерфейс с аннотацией `@Mapper(componentModel = "spring")`
- Никакого ручного маппинга `new Dto(entity.getField(), ...)` — только MapStruct
- Если поля не совпадают — `@Mapping(source = "...", target = "...")`

---

## JPA Entity

```java
@Entity
@Table(name = "listings")
public class Listing {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal price;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "region_id", nullable = false)
    private Region region;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;
}
```

- `FetchType.LAZY` по умолчанию для всех связей — никогда EAGER
- `createdAt` и `updatedAt` — в каждой Entity
- Никаких `@Data` от Lombok на Entity — только `@Getter`, `@Setter` по необходимости
- Никаких bidirectional связей без крайней необходимости

---

## Транзакции

```java
@Service
@Transactional(readOnly = true)   // дефолт для сервиса — только чтение
public class ListingServiceImpl implements ListingService {

    public ListingResponse findById(Long id) { ... }  // наследует readOnly

    @Transactional                // переопределяем для записи
    public ListingResponse create(CreateListingRequest request) { ... }
}
```

- `@Transactional(readOnly = true)` на уровне класса сервиса
- `@Transactional` (без readOnly) — только на методах записи
- Транзакции — только в сервисах, никогда в контроллерах или репозиториях

---

## Обработка ошибок

```java
// Кастомные исключения
public class ListingNotFoundException extends RuntimeException {
    public ListingNotFoundException(Long id) {
        super("Listing not found: " + id);
    }
}

// Глобальный обработчик
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ListingNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorResponse handleNotFound(ListingNotFoundException ex) {
        return new ErrorResponse(ex.getMessage());
    }
}
```

- Кастомное исключение для каждого доменного случая
- `@RestControllerAdvice` — единственное место обработки ошибок
- Никогда: пустой `catch` блок, `catch (Exception e) {}`  без логирования
- `Optional.orElseThrow()` вместо проверки `if (result == null)`

---

## Логирование

```java
@Slf4j  // Lombok
public class ListingServiceImpl {

    public void process() {
        log.info("Processing listing: id={}", id);
        log.debug("Details: {}", details);
        log.error("Failed to parse listing: source={}, url={}", source, url, ex);
    }
}
```

- `@Slf4j` (Lombok) — единственный способ получить логгер
- Никогда `System.out.println`
- `log.error` — всегда с исключением как последний аргумент
- Структурированные сообщения: `key=value` формат

---

## База данных и Flyway

### Миграции
```
src/main/resources/db/migration/
├── V1__create_regions.sql
├── V2__create_listings.sql
├── V3__create_users.sql
├── V4__create_subscriptions.sql
└── V5__add_listing_source_index.sql
```

- Имя файла: `V{N}__{описание_через_подчёркивание}.sql`
- Каждое изменение схемы — новый файл миграции. Никогда не редактировать существующий.
- Миграция содержит только DDL (`CREATE`, `ALTER`, `CREATE INDEX`) — данные отдельно
- Индексы — в отдельной миграции, не в CREATE TABLE

### JPQL и нативные запросы
```java
// Правильно — JPQL с параметрами
@Query("SELECT l FROM Listing l WHERE l.region.code = :regionCode AND l.price BETWEEN :min AND :max")
List<Listing> findByRegionAndPriceRange(
    @Param("regionCode") String regionCode,
    @Param("min") BigDecimal min,
    @Param("max") BigDecimal max
);

// Запрещено
@Query(value = "SELECT * FROM listings WHERE region_code = '" + regionCode + "'", nativeQuery = true)
```

- Никогда `SELECT *` — только нужные поля
- Параметры — только через `@Param`, никакой конкатенации строк
- Нативные SQL запросы — только если JPQL не справляется, с комментарием почему

---

## JWT аутентификация

```java
// Структура токена
{
  "sub": "userId",
  "email": "user@example.com",
  "roles": ["ROLE_USER"],
  "iat": 1700000000,
  "exp": 1700086400
}
```

- Access token: 1 час
- Refresh token: 30 дней, хранится в БД (таблица `refresh_tokens`)
- Токены передаются в заголовке: `Authorization: Bearer <token>`
- Никогда не хранить токены в логах
- `SECRET_KEY` — только из environment variable, никогда в коде или `application.yml`

---

## Коннекторы источников

Каждый коннектор реализует интерфейс:

```java
public interface ListingConnector {
    String getSourceId();            // уникальный идентификатор источника
    RegionCode getSupportedRegion(); // регион этого коннектора
    List<RawListing> fetch();        // основной метод получения объявлений
}
```

Именование реализаций: `OnlinerConnector`, `RealtConnector`, `KufarConnector`.
Пакет: `com.flatio.connector.core` (интерфейс), `com.flatio.connector.{source}` (реализации).

### Обязательные требования
```java
// Rate limiting через Resilience4j
@RateLimiter(name = "connector-realt")
@Retry(name = "connector-realt")
public List<RawListing> fetch() {
    // ...
}
```

- **Rate limiting** — обязателен для каждого коннектора. Конфиг в `application.yml`.
- **Retry с exponential backoff** — 3 попытки, задержка 2s → 4s → 8s
- **Изоляция ошибок** — ошибка одного объявления не останавливает весь fetch
- **Никогда не хранить сырой HTML** — только распарсенные структурированные данные
- **Регион через параметр** — `getSupportedRegion()` возвращает код региона, не хардкод
- **User-Agent** — всегда выставлять реалистичный, не дефолтный OkHttp

### Дедупликация
- Уникальность объявления: хэш от `(sourceId + externalId)`
- При повторном парсинге — обновлять `updatedAt` и изменившиеся поля, не создавать дубль

---

## Документация API — Swagger/OpenAPI

Используется `springdoc-openapi`. Аннотации обязательны для всех публичных Controller-методов и DTO.

### Controller

```java
@Operation(
    summary = "Get listing by ID",
    description = "Returns a single listing by its identifier"
)
@ApiResponse(responseCode = "200", description = "Listing found")
@ApiResponse(responseCode = "404", description = "Listing not found")
@ApiResponse(responseCode = "401", description = "Unauthorized")
@GetMapping("/{id}")
public ListingResponse findById(@PathVariable Long id) { ... }
```

Правила:
- `summary` — одна строка, глагол ("Get", "Create", "Search"), не копировать имя метода
- `description` — только если `summary` недостаточно
- `@ApiResponse` — все возможные коды ответа включая ошибки

### DTO Record

```java
@Schema(description = "Listing search request")
public record SearchRequest(
    @Schema(description = "Region code", example = "BY-MIN", requiredMode = REQUIRED)
    @NotNull RegionCode regionCode,

    @Schema(description = "Minimum price in BYN", example = "30000")
    BigDecimal priceMin
) {}
```

Правила:
- `@Schema` — на каждом поле DTO с `description` и `example`
- `requiredMode = REQUIRED` — явно на обязательных полях

### Что запрещено
- ❌ Controller-метод без `@Operation`
- ❌ DTO-поле без `@Schema`
- ❌ `summary` копирует название метода дословно

---

## Безопасность (базовые правила)

Детальные правила — у `security-engineer`. Здесь минимум который знают все:

- Никаких секретов в коде, `application.yml`, комментариях, логах
- Все секреты — через environment variables
- Входящие данные от пользователя — всегда валидировать через `@Valid`
- SQL — только через JPA/JPQL параметры, никогда конкатенация
- Пароли — только через `BCryptPasswordEncoder`, никогда plaintext

---

## Запрещённые паттерны

```java
// ❌ Бизнес-логика в контроллере
@GetMapping("/listings/{id}")
public ListingResponse get(@PathVariable Long id) {
    var listing = repository.findById(id).orElseThrow();
    listing.setViewCount(listing.getViewCount() + 1); // логика здесь
    return mapper.toResponse(listing);
}

// ❌ EAGER загрузка
@ManyToOne(fetch = FetchType.EAGER)
private Region region;

// ❌ Пустой catch
try {
    parser.parse();
} catch (Exception e) {
    // ignore
}

// ❌ Хардкод региона
if (source.equals("realt.by")) { ... }  // привязка к конкретному источнику

// ❌ SELECT *
@Query("SELECT * FROM listings")

// ❌ System.out
System.out.println("parsed: " + listing);

// ❌ Конкатенация в запросе
"SELECT * FROM listings WHERE id = " + id

// ❌ Секрет в коде
private final String SECRET = "mysecretkey123";
```

---

## Стиль коммитов

Формат: `<type>: <описание> (#<issue>)`

| Тип | Когда |
|-----|-------|
| `feat` | новая функциональность |
| `fix` | исправление бага |
| `chore` | зависимости, конфиг, рефакторинг без изменения поведения |
| `docs` | только документация |
| `test` | только тесты |
| `migration` | Flyway миграция |

Примеры:
```
feat: add listing search by price range (#12)
fix: prevent duplicate listings on re-parse (#18)
migration: add index on listings.region_id (#21)
chore: update Resilience4j to 2.2.0 (#25)
```

- Описание на английском
- Без точки в конце
- Номер issue обязателен