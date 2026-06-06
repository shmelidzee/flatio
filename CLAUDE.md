## Продукт

**Flatio** — платформа агрегации недвижимости. Собирает объявления из различных источников,
предоставляет поиск, подписки и аналитику.

- Первый рынок: **Беларусь**
- Архитектура должна быть готова к расширению на другие рынки с первой строки кода
- Регион всегда передаётся параметром — никогда не захардкожен

---

## Команда

| Агент | Файл | Зона ответственности |
|---|---|---|
| Product Analyst | `agents/product-analyst.md` | Исследование рынка, ТЗ, Notion |
| Product Manager | `agents/product-manager.md` | Бэклог, GitHub Issues, роадмап |
| Software Engineer | `agents/software-engineer.md` | Код, ветки, PR |
| Security Engineer | `agents/security-engineer.md` | Безопасность кода и инфраструктуры |
| DevOps Engineer | `agents/devops-engineer.md` | CI/CD, деплой, мониторинг |
| QA Engineer | `agents/quality-assurance-engineer.md` | Тесты (быстрый и полный режим) |
| Technical Reviewer | `agents/technical-reviewer.md` | Code review, архитектура |
| Technical Writer | `agents/technical-writer.md` | Документация в репо |

---

## Технологический стек (строго)

### Платформа
- **Java 21** (LTS) — основной язык
- **Spring Boot 3.2.x** — фреймворк приложения
- **PostgreSQL 16** — единственная БД, NoSQL не используется
- **Flyway** — миграции схемы, обязательны для любых изменений БД
- **Gradle 8.x** (Kotlin DSL) — система сборки
- **Java 21 toolchain** — прописан явно в `build.gradle.kts`
- **JUnit 5** — unit и интеграционные тесты
- **Mockito** — моки
- **Testcontainers** — PostgreSQL в интеграционных тестах
- **Spring Boot Test** — контекст для интеграционных тестов
- **Docker** + **Docker Compose** — локальное окружение и деплой
- **GitHub Actions** — CI/CD
- **Lombok** — устранение boilerplate
- **MapStruct** — маппинг между слоями
- **Jackson** — сериализация JSON

---

## Структура пакетов

Корневой пакет: `com.flatio`

```
com.flatio
├── config/          # конфигурация Spring, бины
├── domain/          # доменные модели (JPA entities)
│   ├── listing/     # объявления
│   ├── user/        # пользователи
│   ├── subscription/ # подписки
│   └── region/      # регионы и рынки
├── repository/      # Spring Data JPA репозитории
├── service/         # бизнес-логика
├── web/             # REST контроллеры, DTO, маппинг
│   ├── controller/
│   ├── dto/
│   └── mapper/
├── parser/          # парсеры источников
│   ├── core/        # базовые классы, интерфейсы
│   └── {source}/    # парсер конкретного источника
├── scheduler/       # scheduled tasks
├── security/        # аутентификация, авторизация
└── util/            # утилиты
```

---

## Структура репозитория

```
flatio/
├── src/
│   ├── main/java/com/flatio/
│   └── test/java/com/flatio/
├── src/main/resources/
│   ├── application.yml
│   ├── application-local.yml   # локальный оверрайд, в .gitignore
│   └── db/migration/           # Flyway миграции
├── docker/
│   ├── docker-compose.yml
│   └── docker-compose.prod.yml
├── docs/
│   ├── architecture.md
│   ├── parsers.md
│   ├── api.md
│   └── drafts/                 # черновики ТЗ (product-analyst)
├── .github/
│   └── workflows/
├── agents/                     # файлы агентов
├── rules/                      # стандарты
├── CLAUDE.md
├── CHANGELOG.md
└── build.gradle.kts
```

---

## Ветки и работа с Git

| Ветка | Назначение |
|---|---|
| `main` | продакшн, только через PR |
| `develop` | интеграционная ветка |
| `feature/issue-N-slug` | фича по конкретному issue |
| `fix/issue-N-slug` | баг по конкретному issue |
| `docs/issue-N` | только документация |

**Правило именования:** `feature/issue-42-listing-search`, `fix/issue-17-duplicate-parser`

---

## Жёсткие запреты

Нарушение любого из этих правил — стоппер. Агент останавливается и сообщает владельцу продукта.

### Git
- ❌ Нельзя пушить напрямую в `main` или `develop`
- ❌ Нельзя делать force push в любую ветку
- ❌ Нельзя мержить PR без апрува `technical-reviewer`
- ❌ Нельзя коммитить файлы с секретами (`.env`, `application-local.yml`, ключи, пароли)
- ❌ Нельзя коммитить `*.class`, `build/`, `.gradle/` — они в `.gitignore`

### Код
- ❌ Нельзя захардкоживать регион, URL источника, креденшелы — только через конфиг
- ❌ Нельзя писать бизнес-логику в контроллерах — только в сервисах
- ❌ Нельзя использовать `SELECT *` в запросах
- ❌ Нельзя игнорировать исключения пустым `catch` блоком
- ❌ Нельзя делать изменения в БД без Flyway миграции
- ❌ Нельзя добавлять зависимость без обсуждения с владельцем продукта если она меняет архитектуру
- ❌ Нельзя использовать `System.out.println` — только логгер (`slf4j`)

### Тесты
- ❌ Нельзя помечать тест `@Disabled` или `@Ignore` без комментария и апрува владельца продукта
- ❌ Нельзя мержить PR с красными тестами

### Парсеры
- ❌ Нельзя запускать парсер без rate limiting
- ❌ Нельзя дать ошибке одного объявления уронить весь парсер
- ❌ Нельзя хранить сырой HTML в БД

### Документация и Notion
- ❌ `product-analyst` не пишет в Notion без явного апрува владельца продукта
- ❌ `technical-writer` не трогает Notion — только `docs/` в репо

---

## MCP-серверы

```json
{
  "mcpServers": {
    "notion": {
      "command": "npx",
      "args": ["-y", "@notionhq/notion-mcp-server"],
      "env": {
        "OPENAPI_MCP_HEADERS": "{\"Authorization\": \"Bearer <token>\", \"Notion-Version\": \"2022-06-28\"}"
      }
    },
    "github": {
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-github"],
      "env": {
        "GITHUB_PERSONAL_ACCESS_TOKEN": "<token>"
      }
    }
  }
}
```

Токены хранятся в `.claude/settings.local.json` — не в этом файле, не в git.

---

## Как запустить локально

```bash
# Поднять PostgreSQL
docker compose -f docker/docker-compose.yml up -d

# Запустить приложение
./gradlew bootRun --args='--spring.profiles.active=local'

# Запустить тесты
./gradlew test

# Запустить только интеграционные тесты
./gradlew integrationTest
```

---

## Открытые вопросы

Вещи которые ещё не решены — агенты не принимают по ним решения самостоятельно, эскалируют владельцу продукта:

- Telegram Bot vs Web UI — что является основным фронтендом
- Стратегия деплоя (VPS, облако, конкретный провайдер)
- Модель монетизации
- Стратегия rate limiting для каждого конкретного источника