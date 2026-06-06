# /status

Показывает текущее состояние milestone — что сделано, что в работе, что заблокировано.

## Использование
```
/status [milestone-number]
```

## Примеры
```
/status        — статус текущего milestone
/status 2      — статус milestone v0.2
```

## Что показывает
Через GitHub MCP читает Issues текущего milestone и выводит:

```
📊 Milestone v0.1 — Парсинг и базовый поиск

Прогресс: 6/10 Issues закрыты (60%)

✅ Закрыты:
  #1 chore: project skeleton setup
  #2 feat: Flyway migrations setup
  ...

🔄 В работе:
  #7 feat: listing search by price range

⏳ Готовы к разработке:
  #8 feat: subscription to search filters
  #9 feat: price history tracking

🚫 Заблокированы:
  #10 feat: analytics dashboard (blocked by #8)
```
