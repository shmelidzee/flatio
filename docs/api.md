# REST API — Flatio

Base URL: `/api/v1`  
Формат: JSON  
Аутентификация: Bearer JWT (заголовок `Authorization: Bearer <token>`)

---

## Справочник значений

### ListingStatus

| Значение | Описание |
|----------|----------|
| `ACTIVE` | Объявление активно |
| `INACTIVE` | Объявление снято с публикации или исчезло из источника |
| `REPOSTED` | Объявление признано повторной публикацией существующего объявления того же источника |

### PriceUnit

| Значение | Описание |
|----------|----------|
| `PER_MONTH` | Цена за месяц (тип сделки `RENT`) |
| `PER_DAY` | Цена за сутки (тип сделки `RENT_DAILY`) |
| `null` | Не применимо (тип сделки `SELL`)

---

## Listings

### GET /api/v1/listings

Пагинированный поиск объявлений с необязательными фильтрами.
По умолчанию возвращает только объявления со статусом `ACTIVE`.

**Query parameters:**

| Параметр | Тип | Описание |
|----------|-----|----------|
| `dealType` | `RENT` \| `SELL` \| `RENT_DAILY` | Тип сделки |
| `propertyType` | `APARTMENT` \| `ROOM` | Тип недвижимости |
| `city` | String | Частичное совпадение (case-insensitive) |
| `priceMin` | BigDecimal | Минимальная цена в BYN |
| `priceMax` | BigDecimal | Максимальная цена в BYN |
| `rooms` | Integer | Количество комнат |
| `sourceId` | String | Код источника (`ONLINER`, `REALT`, …) |
| `status` | `ACTIVE` \| `INACTIVE` \| `REPOSTED` | По умолчанию `ACTIVE` |
| `page` | Integer | Номер страницы (от 0), по умолчанию 0 |
| `size` | Integer | Размер страницы, по умолчанию 20 |
| `sort` | String | Поле и направление, например `publishedAt,desc` |

**Ответ 200:**

```json
{
  "content": [
    {
      "id": 1,
      "externalId": "12345",
      "sourceId": "ONLINER",
      "title": "Минск, пр-т Независимости, 72",
      "dealType": "RENT",
      "propertyType": "APARTMENT",
      "price": 1470.00,
      "currency": "BYN",
      "priceUnit": "PER_MONTH",
      "rooms": 2,
      "areaTotalM2": 55.5,
      "city": "Минск",
      "photoUrl": null,
      "sourceUrl": "https://r.onliner.by/ak/apartments/12345",
      "publishedAt": "2026-05-15T10:00:00Z",
      "status": "ACTIVE"
    }
  ],
  "totalElements": 1,
  "totalPages": 1,
  "size": 20,
  "number": 0
}
```

**Ошибки:**

| Код | Описание |
|-----|----------|
| 400 | Невалидный параметр фильтра |
| 401 | Не авторизован |

---

### GET /api/v1/listings/{id}

Возвращает полные данные объявления по идентификатору, включая историю изменений цены.

**Параметры пути:**

| Параметр | Тип | Описание |
|----------|-----|----------|
| `id` | Long | Внутренний идентификатор объявления |

**Ответ 200:**

```json
{
  "id": 1,
  "externalId": "12345",
  "sourceId": "ONLINER",
  "title": "Минск, пр-т Независимости, 72",
  "description": null,
  "dealType": "RENT",
  "propertyType": "APARTMENT",
  "price": 1470.00,
  "currency": "BYN",
  "priceUnit": "PER_MONTH",
  "rooms": 2,
  "floorNumber": 5,
  "floorsTotal": 9,
  "areaTotalM2": 55.5,
  "address": "Минск, пр-т Независимости, 72",
  "city": "Минск",
  "latitude": 53.9272,
  "longitude": 27.6244,
  "photoUrl": null,
  "isOwner": true,
  "status": "ACTIVE",
  "sourceUrl": "https://r.onliner.by/ak/apartments/12345",
  "publishedAt": "2026-05-15T10:00:00Z",
  "updatedAt": "2026-06-01T12:00:00Z",
  "priceHistory": [
    {
      "price": 1470.00,
      "currency": "BYN",
      "recordedAt": "2026-05-15T10:00:00Z"
    }
  ]
}
```

**Ошибки:**

| Код | Описание |
|-----|----------|
| 400 | `id` не является числом |
| 401 | Не авторизован |
| 404 | Объявление не найдено |

---

## Обработка ошибок

Все ошибки возвращаются в едином формате:

```json
{
  "timestamp": "2026-06-09T13:00:00Z",
  "status": 404,
  "error": "Not Found",
  "message": "Listing not found: 99",
  "path": "/api/v1/listings/99",
  "errors": []
}
```

При ошибке валидации поле `errors` содержит список нарушений:

```json
{
  "timestamp": "2026-06-09T13:00:00Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Validation failed",
  "path": "/api/v1/listings/search",
  "errors": [
    { "field": "priceMin", "message": "must be greater than or equal to 0" }
  ]
}
```

| HTTP-код | Причина |
|----------|---------|
| 400 | Невалидный параметр или тело запроса |
| 401 | Отсутствует или невалидный токен |
| 404 | Ресурс не найден |
| 500 | Внутренняя ошибка сервера |

---

## Swagger UI

Интерактивная документация доступна при локальном запуске:

- Swagger UI: `http://localhost:8080/swagger-ui.html`
- OpenAPI spec: `http://localhost:8080/v3/api-docs`
