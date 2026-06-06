# Code Style — Flatio

Этот файл читают `software-engineer`, `technical-reviewer`, `security-engineer`.
Базовый стиль: **Google Java Style Guide**. Здесь зафиксированы отклонения и дополнения под проект.

Ссылка на полный гайд: https://google.github.io/styleguide/javaguide.html

---

## Форматирование

### Отступы и длина строки
- Отступ: **2 пробела** (Google style)
- Максимальная длина строки: **120 символов**
- Перенос длинных выражений — перед оператором, не после

### Скобки
K&R стиль (открывающая скобка на той же строке):
```java
// Правильно
public void process() {
  if (condition) {
    doSomething();
  } else {
    doOther();
  }
}

// Запрещено
public void process()
{
  ...
}
```

Скобки обязательны даже для однострочных блоков:
```java
// Правильно
if (condition) {
  return;
}

// Запрещено
if (condition) return;
```

### Пустые строки
- Одна пустая строка между методами
- Одна пустая строка между логическими блоками внутри метода
- Не более одной пустой строки подряд

### Импорты
- Никаких wildcard импортов (`import java.util.*`)
- Порядок: static импорты → java.* → javax.* → org.* → com.* → com.flatio.*
- Неиспользуемые импорты удалять

---

## Структура класса

Порядок элементов в классе:

```
1. Константы (static final)
2. Поля (static, потом instance)
3. Конструкторы
4. Публичные методы
5. Пакетные / protected методы
6. Приватные методы
7. Геттеры / сеттеры (если не Lombok)
```

Пример:
```java
@Service
@Slf4j
@RequiredArgsConstructor
public class ListingServiceImpl implements ListingService {

  private static final int MAX_RESULTS = 100;

  private final ListingRepository listingRepository;
  private final ListingMapper listingMapper;

  @Override
  public ListingResponse findById(Long id) {
    ...
  }

  @Override
  @Transactional
  public ListingResponse create(CreateListingRequest request) {
    ...
  }

  private void validateRequest(CreateListingRequest request) {
    ...
  }
}
```

---

## Структура метода

### Размер
- Максимум **30 строк** на метод
- Если метод длиннее — разбить на приватные вспомогательные методы
- Один метод = одна ответственность

### Параметры
- Максимум **4 параметра**. Больше — создать отдельный объект-параметр (Record)
- Не использовать `boolean` параметры — создать отдельные методы или enum

```java
// Запрещено
public List<Listing> find(String region, BigDecimal min, BigDecimal max,
    Integer rooms, Boolean active, Boolean withPhotos) { ... }

// Правильно
public List<Listing> find(ListingSearchCriteria criteria) { ... }

public record ListingSearchCriteria(
    String regionCode,
    BigDecimal priceMin,
    BigDecimal priceMax,
    Integer roomsMin,
    ListingStatus status
) {}
```

### Возвращаемые значения
- Никогда не возвращать `null` — использовать `Optional<T>` или бросать исключение
- Пустые коллекции — `Collections.emptyList()`, не `null`

```java
// Правильно
public Optional<Listing> findByExternalId(String externalId) {
  return listingRepository.findByExternalId(externalId);
}

// Запрещено
public Listing findByExternalId(String externalId) {
  return listingRepository.findByExternalId(externalId).orElse(null);
}
```

### Ранний возврат (Early Return)
Предпочитать ранний возврат вместо глубокой вложенности:

```java
// Правильно
public void process(Listing listing) {
  if (listing == null) {
    throw new IllegalArgumentException("Listing must not be null");
  }
  if (!listing.isActive()) {
    log.debug("Skipping inactive listing: id={}", listing.getId());
    return;
  }
  // основная логика без вложенности
  doProcess(listing);
}

// Запрещено — глубокая вложенность
public void process(Listing listing) {
  if (listing != null) {
    if (listing.isActive()) {
      doProcess(listing);
    }
  }
}
```

---

## Javadoc

Обязателен на всех `public` методах кроме тривиальных геттеров/сеттеров и методов Record.

### Формат
```java
/**
 * Finds listings matching the given search criteria.
 *
 * <p>Results are paginated and sorted by creation date descending.
 * Returns an empty list if no listings match the criteria.
 *
 * @param criteria search parameters including region, price range, and room count
 * @param pageable pagination and sorting configuration
 * @return page of listings matching the criteria, never null
 * @throws IllegalArgumentException if criteria or pageable is null
 */
public Page<ListingResponse> search(ListingSearchCriteria criteria, Pageable pageable) {
  ...
}
```

### Правила
- Первая строка — краткое описание что делает метод (глагол в настоящем времени)
- `@param` — для каждого параметра, кроме очевидных
- `@return` — что возвращается, может ли быть null/empty
- `@throws` — для checked исключений и важных unchecked
- Javadoc на английском
- Не копировать название метода в Javadoc — описание должно добавлять информацию

### Что не требует Javadoc
```java
// Тривиальные методы — не нужен Javadoc
public Long getId() { return id; }
public boolean isActive() { return active; }

// Override методы — не нужен если родительский задокументирован
@Override
public ListingResponse findById(Long id) { ... }
```

### Комментарии в коде
Инлайн-комментарии — только для неочевидной логики:
```java
// Правильно — объясняет почему, не что
// Realt.by returns prices in USD, we store in BYN
BigDecimal priceInByn = price.multiply(exchangeRate);

// Запрещено — очевидно из кода
// increment counter
counter++;
```

---

## Использование Lombok

Разрешённые аннотации:
```java
@Slf4j                    // логгер
@RequiredArgsConstructor  // конструктор для final полей (вместо @Autowired)
@Getter                   // геттеры на Entity если нужны
@Setter                   // сеттеры на Entity если нужны
@Builder                  // билдер для сложных объектов
```

Запрещённые аннотации на Entity:
```java
@Data        // генерирует equals/hashCode по всем полям — проблемы с JPA
@ToString    // может вызвать LazyInitializationException
@EqualsAndHashCode  // без явного указания полей — опасно для JPA
```

Инъекция зависимостей — только через конструктор (`@RequiredArgsConstructor`):
```java
// Правильно
@Service
@RequiredArgsConstructor
public class ListingServiceImpl {
  private final ListingRepository listingRepository;
}

// Запрещено
@Service
public class ListingServiceImpl {
  @Autowired
  private ListingRepository listingRepository;
}
```

---

## Работа с коллекциями и Stream API

```java
// Предпочитать Stream API для трансформаций
List<ListingResponse> responses = listings.stream()
    .filter(Listing::isActive)
    .map(listingMapper::toResponse)
    .toList();  // Java 21 — .toList() вместо .collect(Collectors.toList())

// Для простых случаев — не усложнять
if (listings.isEmpty()) {
  return Collections.emptyList();
}
```

- `.toList()` вместо `.collect(Collectors.toList())` — Java 21
- Не использовать Stream там где обычный цикл читается лучше
- Никаких вложенных Stream — вынести во вспомогательный метод

---

## Константы и Magic Numbers

```java
// Запрещено
if (listings.size() > 100) { ... }
Thread.sleep(2000);

// Правильно
private static final int MAX_SEARCH_RESULTS = 100;
private static final long PARSER_DELAY_MS = 2_000;

if (listings.size() > MAX_SEARCH_RESULTS) { ... }
Thread.sleep(PARSER_DELAY_MS);
```

- Все magic numbers — в именованные константы
- Разделитель тысяч в числах: `1_000_000` вместо `1000000`

---

## Чеклист перед коммитом

Каждый файл проверяется по этому списку перед коммитом:

- [ ] Нет неиспользуемых импортов
- [ ] Нет закомментированного кода
- [ ] Нет `System.out.println`
- [ ] Все публичные методы имеют Javadoc
- [ ] Методы не длиннее 30 строк
- [ ] Нет magic numbers без константы
- [ ] Нет `null` в возвращаемых значениях
- [ ] Lombok аннотации на Entity только разрешённые
- [ ] `@Operation` и `@ApiResponse` на каждом публичном методе Controller
- [ ] `@Schema` с `description` и `example` на каждом поле DTO