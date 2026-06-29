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

## Auth

### POST /api/v1/auth/telegram

Публичный эндпоинт (не требует токена — это и есть точка его выдачи). Принимает
`initData` из Telegram WebApp (`window.Telegram.WebApp.initData`), проверяет подпись и
свежесть (`auth_date` не старше 24 часов) по алгоритму, описанному в
[документации Telegram](https://core.telegram.org/bots/webapps#validating-data-received-via-the-mini-app),
находит или создаёт пользователя (как и `/start` в боте) и выдаёт JWT.

**Тело запроса:**

```json
{ "initData": "query_id=AAH...&user=%7B%22id%22%3A123%7D&auth_date=1700000000&hash=abc123" }
```

**Ответ 200:**

```json
{ "accessToken": "eyJhbGciOiJIUzI1NiJ9...", "expiresIn": 3600 }
```

**Ошибки:**

| Код | Описание |
|-----|----------|
| 400 | `initData` пустой или отсутствует в теле запроса |
| 401 | Подпись `initData` невалидна или просрочена |
| 429 | Превышен лимит запросов с этого IP (см. «Rate limiting» ниже) |

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
      "status": "ACTIVE",
      "isNegotiable": false
    }
  ],
  "totalElements": 1,
  "totalPages": 1,
  "size": 20,
  "number": 0
}
```

> **Примечание:** при активном ценовом фильтре (`priceMin` или `priceMax`) объявления с `isNegotiable=true` исключаются из результатов, так как для них числовая цена не задана.

**Ошибки:**

| Код | Описание |
|-----|----------|
| 400 | Невалидный параметр фильтра |
| 401 | Не авторизован |
| 429 | Превышен лимит запросов для этого пользователя (см. «Rate limiting» ниже) |

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
  "priceLabel": null,
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
  "isNegotiable": false,
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

> **Примечание о договорной цене:** когда `isNegotiable=true`, продавец не указал числовую цену. В этом случае `price=null` и `priceLabel="Договорная"`. Такие объявления не попадают в выдачу при активном ценовом фильтре.

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
| 429 | Превышен лимит запросов |
| 500 | Внутренняя ошибка сервера |

---

## Rate limiting

Лимиты — per-caller, не глобальные: у каждого IP (для `/api/v1/auth/**`) или пользователя
(для остального `/api/v1/**`, по `sub` из JWT) свой собственный счётчик.

| Эндпоинты | Ключ | Лимит по умолчанию | Настройка (env) |
|-----------|------|---------------------|------------------|
| `/api/v1/auth/**` | IP-адрес (`X-Forwarded-For`, иначе адрес соединения) | 10 запросов / 60 сек | `RATE_LIMIT_AUTH_REQUESTS`, `RATE_LIMIT_AUTH_REFRESH_SECONDS` |
| Остальной `/api/v1/**` | `sub` из JWT (анонимные запросы не лимитируются — их отклоняет Spring Security до контроллера) | 60 запросов / 60 сек | `RATE_LIMIT_API_REQUESTS`, `RATE_LIMIT_API_REFRESH_SECONDS` |

При превышении лимита — `429 Too Many Requests` в стандартном формате ошибки (см. выше), без деталей реализации лимитера.

---

## Swagger UI

Интерактивная документация доступна при локальном запуске:

- Swagger UI: `http://localhost:8080/swagger-ui.html`
- OpenAPI spec: `http://localhost:8080/v3/api-docs`
