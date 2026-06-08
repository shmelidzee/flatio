# Flatio

Платформа агрегации недвижимости. Собирает объявления из различных источников, предоставляет поиск и аналитику.

## Требования

- **Java 21** (JDK)
- **Docker** и **Docker Compose**
- **Gradle 8.x** (или используйте обёртку `./gradlew`)

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

При локальной разработке значения по умолчанию применяются автоматически, если переменные не заданы.
`TELEGRAM_BOT_TOKEN` и `TELEGRAM_BOT_USERNAME` не имеют дефолтов — приложение не стартует без них.
Для деплоя на Railway задайте эти переменные в Railway Dashboard → Variables, используя данные подключения к PostgreSQL.

## Документация API

Swagger UI доступен по адресу `http://localhost:8080/swagger-ui.html` при локальном запуске.

OpenAPI-спецификация: `http://localhost:8080/v3/api-docs`

## Документация проекта

| Файл | Содержание |
|------|------------|
| `docs/architecture.md` | Архитектура, стек, структура пакетов, доменная модель, миграции |
| `CHANGELOG.md` | История изменений |
