# Testing Standards — Flatio

Этот файл читает только `quality-assurance-engineer`.
`software-engineer` тесты не пишет — это исключительная зона QA Engineer.

---

## Принципы

- Тест должен ловить реальный баг, а не подтверждать что код существует
- Тест проверяет поведение, не реализацию
- Если тест можно удалить и ничего не сломается — он не нужен
- Красный тест блокирует PR. Никаких исключений.

---

## Уровни тестов

### Unit тесты
- Тестируют один класс в изоляции
- Все зависимости — моки (Mockito)
- Быстрые: не поднимают Spring контекст, не используют БД
- Запускаются: после каждого коммита (`./gradlew test`)
- Расположение: `src/test/java/com/flatio/`

### Интеграционные тесты
- Тестируют взаимодействие слоёв: Service + Repository + PostgreSQL
- Реальная БД через Testcontainers
- Запускаются: перед закрытием milestone (`./gradlew integrationTest`)
- Расположение: `src/integrationTest/java/com/flatio/`
- Суффикс класса: `IT` (например `ListingServiceIT`)

---

## Целевое покрытие

| Слой | Покрытие | Уровень |
|------|----------|---------|
| Service | **100%** | Unit |
| Connector | **100%** | Unit |
| Repository | **100%** | Интеграционный |
| Controller | 80%+ | Unit (MockMvc) |
| Mapper | не требуется | MapStruct генерирует |
| Config | не требуется | — |

100% означает: все публичные методы, все ветки (`if/else`), все граничные случаи.

---

## Структура теста — Given/When/Then

Каждый тест строго следует структуре:

```java
@Test
void should_return_listing_when_valid_id_provided() {
    // Given
    var listing = buildListing(1L, "Test listing", BigDecimal.valueOf(50000));
    when(listingRepository.findById(1L)).thenReturn(Optional.of(listing));

    // When
    var result = listingService.findById(1L);

    // Then
    assertThat(result).isNotNull();
    assertThat(result.id()).isEqualTo(1L);
    assertThat(result.title()).isEqualTo("Test listing");
}
```

Правила:
- Три секции разделены пустой строкой с комментарием `// Given`, `// When`, `// Then`
- Одна проверка одного поведения на тест — не мешать несколько сценариев
- Название метода: `should_do_something_when_condition` — snake_case, на английском, без `test` префикса

---

## Именование

### Классы тестов
```
ListingServiceTest         — unit тест сервиса
RealtConnectorTest         — unit тест коннектора
ListingControllerTest      — unit тест контроллера
ListingServiceIT           — интеграционный тест сервиса
ListingRepositoryIT        — интеграционный тест репозитория
```

### Методы тестов
```java
// Формат: should_do_something_when_condition
should_return_listing_when_valid_id_provided()
should_throw_exception_when_listing_not_found()
should_skip_duplicate_when_listing_already_exists()
should_return_empty_list_when_no_listings_match_criteria()
should_apply_rate_limit_when_connector_exceeds_threshold()
should_handle_broken_response_when_source_returns_invalid_markup()
```

---

## Unit тесты — сервисы

```java
@ExtendWith(MockitoExtension.class)
class ListingServiceTest {

  @Mock
  private ListingRepository listingRepository;

  @Mock
  private ListingMapper listingMapper;

  @InjectMocks
  private ListingServiceImpl listingService;

  @Test
  void should_return_listing_when_valid_id_provided() {
    // Given
    var listing = buildListing(1L, "Квартира в Минске", BigDecimal.valueOf(50_000));
    var expectedResponse = new ListingResponse(1L, "Квартира в Минске", BigDecimal.valueOf(50_000), "BYN", null, Instant.now());
    when(listingRepository.findById(1L)).thenReturn(Optional.of(listing));
    when(listingMapper.toResponse(listing)).thenReturn(expectedResponse);

    // When
    var result = listingService.findById(1L);

    // Then
    assertThat(result).isNotNull();
    assertThat(result.id()).isEqualTo(1L);
    verify(listingRepository).findById(1L);
  }

  @Test
  void should_throw_exception_when_listing_not_found() {
    // Given
    when(listingRepository.findById(99L)).thenReturn(Optional.empty());

    // When / Then
    assertThatThrownBy(() -> listingService.findById(99L))
        .isInstanceOf(ListingNotFoundException.class)
        .hasMessageContaining("99");
  }
}
```

### Что обязательно тестировать в сервисах
- Happy path: нормальный сценарий с валидными данными
- Not found: объект не найден → правильное исключение
- Validation: невалидные входные данные → исключение
- Boundary: граничные значения (0, null, пустая строка, максимальное значение)
- Duplicate: попытка создать дубль → правильное поведение

---

## Unit тесты — коннекторы

Коннекторы — приоритет тестирования. Внешние источники ненадёжны, тесты должны это покрывать.

```java
@ExtendWith(MockitoExtension.class)
class RealtConnectorTest {

  @Mock
  private OkHttpClient httpClient;

  @InjectMocks
  private RealtConnector realtConnector;

  @Test
  void should_fetch_listings_when_valid_response_provided() {
    // Given
    var html = loadTestResource("realt/valid-listing-page.html");
    mockHttpResponse(httpClient, html, 200);

    // When
    var result = realtConnector.fetch();

    // Then
    assertThat(result).hasSize(20);
    assertThat(result.get(0).title()).isNotBlank();
    assertThat(result.get(0).price()).isPositive();
    assertThat(result.get(0).externalId()).isNotBlank();
  }

  @Test
  void should_skip_listing_when_price_is_missing() {
    // Given — ответ без цены у одного объявления
    var html = loadTestResource("realt/listing-without-price.html");
    mockHttpResponse(httpClient, html, 200);

    // When
    var result = realtConnector.fetch();

    // Then — объявление без цены пропускается, остальные обрабатываются
    assertThat(result).hasSize(19);
  }

  @Test
  void should_return_empty_list_when_response_is_broken() {
    // Given
    mockHttpResponse(httpClient, "<html>broken</html>", 200);

    // When
    var result = realtConnector.fetch();

    // Then — не бросает исключение, возвращает пустой список
    assertThat(result).isEmpty();
  }

  @Test
  void should_retry_when_source_returns_503() {
    // Given — три попытки, первые две возвращают 503
    mockHttpResponseSequence(httpClient, 503, 503, 200);
    mockHttpResponse(httpClient, loadTestResource("realt/valid-listing-page.html"), 200);

    // When
    var result = realtConnector.fetch();

    // Then
    assertThat(result).isNotEmpty();
    verify(httpClient, times(3)).newCall(any());
  }

  @Test
  void should_not_propagate_exception_when_single_listing_fails() {
    // Given — ответ с одним битым объявлением среди нормальных
    var html = loadTestResource("realt/page-with-broken-listing.html");
    mockHttpResponse(httpClient, html, 200);

    // When / Then — не бросает исключение
    assertThatNoException().isThrownBy(() -> realtConnector.fetch());
  }
}
```

### Обязательные тест-кейсы для каждого коннектора
- [ ] Валидный ответ → корректный список объявлений
- [ ] Битый/невалидный ответ → пустой список, без исключения
- [ ] Пустой ответ от источника → пустой список
- [ ] Отсутствует обязательное поле (цена, заголовок) → объявление пропускается
- [ ] HTTP 429 (rate limit) → retry с backoff
- [ ] HTTP 503 → retry, затем circuit breaker
- [ ] Изменилась структура ответа → graceful degradation, не крэш
- [ ] Дедупликация: одно объявление не попадает дважды

### Тестовые ресурсы
Фикстуры ответов источников хранятся в:
```
src/test/resources/fixtures/
├── realt/
│   ├── valid-listing-page.html
│   ├── listing-without-price.html
│   ├── page-with-broken-listing.html
│   └── empty-page.html
├── onliner/
│   ├── valid-response.json
│   └── empty-response.json
└── {source}/
    └── ...
```

Фикстуры — реальные снапшоты ответов источников, сохранённые вручную.
Обновлять при изменении структуры источника.

---

## Интеграционные тесты — репозитории

```java
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class ListingRepositoryIT {

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
      .withDatabaseName("flatio_test");

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  @Autowired
  private ListingRepository listingRepository;

  @Test
  void should_find_listings_by_region_and_price_range() {
    // Given
    var region = buildRegion("BY-MIN");
    var listing1 = buildListing("Listing 1", BigDecimal.valueOf(40_000), region);
    var listing2 = buildListing("Listing 2", BigDecimal.valueOf(80_000), region);
    listingRepository.saveAll(List.of(listing1, listing2));

    // When
    var result = listingRepository.findByRegionAndPriceRange(
        "BY-MIN",
        BigDecimal.valueOf(30_000),
        BigDecimal.valueOf(50_000)
    );

    // Then
    assertThat(result).hasSize(1);
    assertThat(result.get(0).getTitle()).isEqualTo("Listing 1");
  }
}
```

---

## Интеграционные тесты — сервисы

```java
@SpringBootTest
@Testcontainers
@Transactional
class ListingServiceIT {

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

  @Autowired
  private ListingService listingService;

  @Autowired
  private ListingRepository listingRepository;

  @Test
  void should_not_create_duplicate_when_listing_already_exists() {
    // Given
    var request = buildCreateRequest("source-1", "ext-id-123");
    listingService.create(request);

    // When
    listingService.create(request);  // повторный вызов с теми же данными

    // Then
    var count = listingRepository.countBySourceIdAndExternalId("source-1", "ext-id-123");
    assertThat(count).isEqualTo(1);
  }
}
```

---

## Тесты контроллеров — MockMvc

```java
@WebMvcTest(ListingController.class)
class ListingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ListingService listingService;

    @Test
    void should_return_200_when_listing_found() throws Exception {
        // Given
        var response = buildListingResponse(1L);
        when(listingService.findById(1L)).thenReturn(response);

        // When / Then
        mockMvc.perform(get("/api/v1/listings/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1L))
                .andExpect(jsonPath("$.title").isNotEmpty());
    }

    @Test
    void should_return_404_when_listing_not_found() throws Exception {
        // Given
        when(listingService.findById(99L)).thenThrow(new ListingNotFoundException(99L));

        // When / Then
        mockMvc.perform(get("/api/v1/listings/99"))
                .andExpect(status().isNotFound());
    }

    @Test
    void should_return_400_when_request_body_invalid() throws Exception {
        // Given — запрос без обязательного поля regionCode
        var invalidRequest = """
                {
                  "priceMin": -100
                }
                """;

        // When / Then
        mockMvc.perform(post("/api/v1/listings/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidRequest))
                .andExpect(status().isBadRequest());
    }
}
```

---

## Вспомогательные методы — Test Builder

Не дублировать создание тестовых объектов. Выносить в статические методы:

```java
// src/test/java/com/flatio/util/TestBuilders.java
public class TestBuilders {

    public static Listing buildListing(Long id, String title, BigDecimal price) {
        var listing = new Listing();
        listing.setId(id);
        listing.setTitle(title);
        listing.setPrice(price);
        listing.setCreatedAt(Instant.now());
        listing.setUpdatedAt(Instant.now());
        return listing;
    }

    public static Region buildRegion(String code) {
        var region = new Region();
        region.setCode(code);
        region.setName("Test Region");
        return region;
    }
}
```

---

## Запрещённые практики

```java
// ❌ Тест без Assert
@Test
void shouldProcessListing() {
    listingService.process(listing);
    // нет проверки — бесполезный тест
}

// ❌ Несколько сценариев в одном тесте
@Test
void shouldHandleAllCases() {
    // тест нормального случая
    assertThat(service.findById(1L)).isNotNull();
    // тест случая не найдено — отдельный тест
    assertThatThrownBy(() -> service.findById(99L))...
}

// ❌ Тест реализации вместо поведения
@Test
void shouldCallRepositoryOnce() {
    service.findById(1L);
    verify(repository, times(1)).findById(1L); // проверяем детали реализации
}

// ❌ @Disabled без причины и апрува PO
@Disabled
@Test
void shouldHandleEdgeCase() { ... }

// ❌ Thread.sleep в тестах
@Test
void shouldProcessAsync() throws Exception {
    service.processAsync();
    Thread.sleep(1000); // недетерминированно
}
```

---

## Режимы запуска QA Engineer

### Быстрый режим — после каждого коммита
```bash
./gradlew test
```
Только unit тесты. Должны выполняться менее 60 секунд.
Красный — немедленный отчёт разработчику, работа блокируется.

### Полный режим — перед закрытием milestone
```bash
./gradlew test integrationTest jacocoTestReport
```
Unit + интеграционные тесты + отчёт о покрытии.
Результат сохраняется в `docs/qa-reports/milestone-N.md`.
Красный или покрытие ниже цели — milestone не закрывается.

---

## Шаблон QA отчёта

Файл: `docs/qa-reports/milestone-N.md`

```markdown
# QA Report — Milestone N

**Дата:** YYYY-MM-DD
**Статус:** ✅ Passed | ❌ Failed

## Покрытие
| Слой | Цель | Факт | Статус |
|------|------|------|--------|
| Service | 100% | 100% | ✅ |
| Connector | 100% | 97% | ❌ |

## Результаты тестов
- Unit тесты: 142 passed, 0 failed
- Интеграционные: 38 passed, 2 failed

## Найденные баги
### BLOCKER
- [ ] #N — описание бага

### NON-BLOCKER
- [ ] #N — описание бага

## Решение
Milestone [закрыт | заблокирован до исправления #N, #M]
```