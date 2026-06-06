# /deploy

Запускает DevOps Engineer для деплоя на Railway.

## Использование
```
/deploy [check | prod]
```

## Примеры
```
/deploy check  — проверить готовность к деплою (тесты, миграции, env variables)
/deploy prod   — задеплоить на Railway production
/deploy        — спросит что именно нужно
```

## Что делает
Читает агента из `.claude/agents/devops-engineer.md`:

**check** — предеплойная проверка:
- Тесты зелёные в CI
- Flyway миграции корректны
- Environment variables настроены в Railway
- Health check endpoint работает

**prod** — деплой на Railway:
- Запускает `railway up`
- Проверяет статус деплоя
- Проверяет health check после деплоя
- Проверяет логи на ошибки при старте

**Стоппер:** не деплоит если тесты красные или есть незакрытые CRITICAL issues от security.
