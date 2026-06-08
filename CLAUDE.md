## Язык

Все агенты общаются с владельцем продукта **только на русском языке**.
Это распространяется на все ответы, комментарии, отчёты и сообщения в терминале.
Исключение: код, идентификаторы, имена переменных, Javadoc — на английском (стандарт проекта).

---

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
| Product Analyst | `.claude/agents/product-analyst.md` | Исследование рынка, ТЗ, Notion |
| Product Manager | `.claude/agents/product-manager.md` | Бэклог, GitHub Issues, роадмап |
| Software Engineer | `.claude/agents/software-engineer.md` | Код, ветки, PR |
| Security Engineer | `.claude/agents/security-engineer.md` | Безопасность кода и инфраструктуры |
| DevOps Engineer | `.claude/agents/devops-engineer.md` | CI/CD, деплой, мониторинг |
| QA Engineer | `.claude/agents/quality-assurance-engineer.md` | Тесты (быстрый и полный режим) |
| Technical Reviewer | `.claude/agents/technical-reviewer.md` | Code review, архитектура |
| Technical Writer | `.claude/agents/technical-writer.md` | Документация в репо |

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

Пакеты необходимо держать в порядке и создавать файлы в правильных местах.

Корневой пакет: `com.flatio`

```text
com.flatio

├── domain/                    # доменные модели
│   ├── listing/               # объявления
│   ├── user/                  # пользователи
│   ├── subscription/          # подписки
│   ├── notification/          # уведомления
│   └── region/                # регионы и рынки

├── repository/                # Spring Data JPA репозитории

├── service/                   # интерфейсы бизнес-сервисов
├── service/impl/              # реализации бизнес-сервисов

├── web/
│   ├── controller/            # REST контроллеры
│   ├── dto/                   # request/response DTO
│   └── mapper/                # DTO ↔ Domain маппинг

├── telegram/
│   ├── сonfig/                # config файлы для настройки исключительно при работе с телеграм (бот, клиент)
│   ├── handler/               # обработчики Telegram Update
│   ├── command/               # Telegram команды
│   ├── callback/              # callback query обработчики
│   ├── keyboard/              # inline/reply клавиатуры
│   ├── state/                 # FSM и пользовательские сценарии
│   └── client/                # Telegram Bot API клиент

├── integration/
│   ├── core/                  # базовые интерфейсы интеграций
│   ├── realt/                 # интеграция с Realt
│   ├── kufar/                 # интеграция с Kufar
│   └── onliner/               # интеграция с Onliner

├── scheduler/                 # scheduled задачи

├── security/                  # Spring Security, Keycloak, JWT

├── config/                    # @Configuration и настройки приложения

├── common/
│   ├── exception/             # кастомные исключения
│   ├── util/                  # утилиты
│   └── constants/             # константы

└── event/                     # доменные события и event-модели
```

### Архитектурные правила

* Контроллеры не содержат бизнес-логику.
* Telegram Handler не работает с Repository напрямую.
* Repository используются только сервисами.
* Вся бизнес-логика находится в сервисном слое.
* Интеграции с внешними системами изолированы в пакете `integration`.
* Domain слой не зависит от Web, Telegram и Integration слоев.
* Telegram и REST API используют одни и те же сервисы.
* Не размещать бизнес-логику в Controller, Mapper, Handler и Repository.
* При наличии интерфейса сервиса реализация должна размещаться в `service.impl`.

````

---

## Структура репозитория

```text
flatio/

├── src/
│   ├── main/java/com/flatio/
│   └── test/java/com/flatio/

├── src/main/resources/
│   ├── application.yml
│   ├── application-local.yml      # локальный оверрайд, в .gitignore
│   └── db/migration/              # Flyway миграции

├── docker/
│   ├── docker-compose.yml
│   └── docker-compose.prod.yml    # в .gitignore

├── docs/
│   ├── architecture.md
│   ├── api.md
│   ├── integrations.md
│   ├── local-setup.md
│   ├── product/
│   │   ├── requirements.md
│   │   └── roadmap.md
│   ├── drafts/                    # черновики
│   └── qa-reports/                # QA отчёты

├── .github/
│   └── workflows/

├── .claude/
│   ├── agents/
│   ├── commands/
│   └── rules/

├── CLAUDE.md
├── CHANGELOG.md
├── README.md
└── build.gradle.kts
````


---

## Ветки и работа с Git

| Ветка | Назначение |
|---|---|
| `master` | продакшн, только через PR |
| `develop` | интеграционная ветка |
| `feature/issue-N-slug` | фича по конкретному issue |
| `fix/issue-N-slug` | баг по конкретному issue |
| `docs/issue-N` | документация по конкретному issue |
| `docs/post-pr-N` | документация после merge PR N (technical-writer) |

**Правило именования:** `feature/issue-42-listing-search`, `fix/issue-17-duplicate-parser`

---

## Ограничения GitHub

### Self-review restriction
GitHub не позволяет автору PR апрувить собственный PR. В single-owner репозитории это означает что `technical-reviewer` и `security-engineer` физически не могут выставить статус `APPROVE` через API — GitHub возвращает 422.

**Workaround:** оба агента используют `event: COMMENT` с явным вердиктом в теле ревью:
```
**Verdict: ✅ APPROVED** — Security Engineer
**Verdict: ✅ APPROVED** — Technical Reviewer
```
Это функционально эквивалентно апруву и служит документацией того что оба ревьюера проверили PR.

Когда в проекте появится второй collaborator — workaround убрать, перейти на `event: APPROVE`.

---

## Жёсткие запреты

Нарушение любого из этих правил — стоппер. Агент останавливается и сообщает владельцу продукта.

### Git
- ❌ Нельзя пушить напрямую в `master` или `develop`
- ❌ Нельзя делать force push в любую ветку
- ❌ Нельзя мержить PR без апрувов `technical-reviewer` и `security-engineer`
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

Полный список открытых вопросов — в Notion, страница **Открытые вопросы** (OQ-1..OQ-27).
Агенты не принимают по ним решения самостоятельно — эскалируют владельцу продукта.

Критические нерешённые вопросы на уровне стека (требуют решения PO до старта разработки):

- **M1.0.4 — Технологический стек**: текущий скелет проекта — Java 21 + Spring Boot 3.2.x + Gradle 8.x.
  До явного апрува PO агенты работают с тем что в коде (Java 21 + Spring Boot 3.2.x).
- **OQ-13 — Охват городов**: только Минск или вся РБ на старте
- **OQ-18 — Форма Админки**: отдельный UI, Swagger или admin-фреймворк на MVP
- **OQ-25 — Регистрация в боте**: обязательная или анонимный режим