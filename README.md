# Flatio

Платформа агрегации недвижимости. Собирает объявления из различных источников, предоставляет поиск и аналитику.

## Требования

- **Java 21** (JDK)
- **Docker** и **Docker Compose**
- **Gradle 8.x** (или используйте обёртку `./gradlew`)
- **Node.js 24** и npm на `PATH` — нужны для сборки admin SPA (`frontend/admin/`); `./gradlew build` / `bootRun` собирают его автоматически (см. `docs/architecture.md`, раздел «Admin Interface»)

## Быстрый старт

```bash
# 1. Запустить PostgreSQL
docker compose -f docker/docker-compose.yml up -d

# 2. Скопировать конфиг для локальной разработки
cp src/main/resources/application-local.yml.example src/main/resources/application-local.yml

# 3. Запустить приложение
./gradlew bootRun --args='--spring.profiles.active=local'
```

Приложение будет доступно по адресу `http://localhost:8080`.

## Конфигурация

Локальный конфиг хранится в `src/main/resources/application-local.yml` — файл добавлен в `.gitignore` и не коммитится.

Скопируйте пример и при необходимости скорректируйте значения:

```bash
cp src/main/resources/application-local.yml.example src/main/resources/application-local.yml
```

Все доступные настройки и их описания — в `application-local.yml.example`.

## Запуск тестов

```bash
# Unit-тесты (быстрые, без базы данных)
./gradlew test

# Интеграционные тесты (требуется Docker — PostgreSQL поднимается через Testcontainers)
./gradlew integrationTest
```

## Логирование

Формат логов зависит от активного Spring-профиля:

| Профиль | Формат |
|---------|--------|
| `prod` | Структурированный JSON через Logstash encoder (`@timestamp`, `level`, `logger_name`, `thread_name`, `message`) |
| любой другой (например `local`) | Human-readable: `yyyy-MM-dd HH:mm:ss.SSS [thread] LEVEL logger - message` |

Дополнительная конфигурация не требуется — формат переключается автоматически через `logback-spring.xml`.

## Переменные окружения

| Переменная | Описание | Локальное значение по умолчанию |
|------------|----------|---------------------------------|
| `DB_FLATIO_URL` | JDBC URL для PostgreSQL | `jdbc:postgresql://localhost:5432/flatio` |
| `DB_FLATIO_USER` | Имя пользователя PostgreSQL | `flatio` |
| `DB_FLATIO_PASSWORD` | Пароль PostgreSQL | `flatio_local` |
| `TELEGRAM_BOT_TOKEN` | Telegram Bot API токен | — (обязательно) |
| `TELEGRAM_BOT_USERNAME` | Имя Telegram-бота (без @) | — (обязательно) |
| `JWT_SECRET_KEY` | HMAC-SHA ключ для подписи JWT (минимум 256 бит) | — (обязательно) |
| `JWT_ACCESS_TOKEN_EXPIRY` | Срок жизни access токена, секунды | `3600` |
| `CORS_ALLOWED_ORIGINS` | Разрешённые CORS origins (через запятую) | `http://localhost:3000` |
| `RATE_LIMIT_AUTH_REQUESTS` | Лимит запросов на `/api/v1/auth/**` за период, на один IP | `10` |
| `RATE_LIMIT_AUTH_REFRESH_SECONDS` | Период обновления лимита `/api/v1/auth/**`, секунды | `60` |
| `RATE_LIMIT_API_REQUESTS` | Лимит запросов на остальной `/api/v1/**` (кроме `/api/v1/admin/**`) за период, на одного пользователя | `60` |
| `RATE_LIMIT_API_REFRESH_SECONDS` | Период обновления лимита `/api/v1/**` (кроме `/api/v1/admin/**`), секунды | `60` |
| `RATE_LIMIT_ADMIN_API_REQUESTS` | Лимит запросов на `/api/v1/admin/**` за период, на одного администратора | `240` |
| `RATE_LIMIT_ADMIN_API_REFRESH_SECONDS` | Период обновления лимита `/api/v1/admin/**`, секунды | `60` |

При локальной разработке значения по умолчанию применяются автоматически, если переменные не заданы.
`TELEGRAM_BOT_TOKEN`, `TELEGRAM_BOT_USERNAME` и `JWT_SECRET_KEY` не имеют дефолтов — приложение не стартует без них.
Для деплоя на Railway задайте эти переменные в Railway Dashboard → Variables, используя данные подключения к PostgreSQL.

## Документация API

Swagger UI доступен по адресу `http://localhost:8080/swagger-ui.html` при локальном запуске.

OpenAPI-спецификация: `http://localhost:8080/v3/api-docs`

## Документация проекта

| Файл | Содержание |
|------|------------|
| `docs/architecture.md` | Архитектура, стек, структура пакетов, доменная модель, миграции |
| `CHANGELOG.md` | История изменений |
