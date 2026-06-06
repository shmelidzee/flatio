# /release

Оркестратор релиза milestone. Запускает полное QA и деплой.

## Использование
```
/release [milestone-number]
```

## Примеры
```
/release 1     — зарелизить milestone v0.1
/release       — зарелизить текущий milestone
```

## Что делает

```
[quality-assurance-engineer] полный режим
  ├── ❌ есть блокеры → создаёт bug issues → СТОП: сообщает тебе
  └── ✅ всё зелёное →
        ↓
[devops-engineer] предеплойная проверка
  ├── ❌ не готово → СТОП: сообщает тебе что именно
  └── ✅ готово →
        ↓
СТОП: подтверждение деплоя от PO
        ↓ (после твоего «да»)
[devops-engineer] деплой на Railway
  ├── ❌ деплой упал → СТОП: сообщает тебе логи
  └── ✅ задеплоено →
        ↓
[product-manager] закрывает milestone в GitHub + обновляет Roadmap в Notion
```

## Точки остановки

**1. Есть QA блокеры:**
```
❌ Milestone v0.1 не готов к релизу.
QA отчёт: docs/qa-reports/milestone-1.md
Блокеры: #N, #M
Исправь блокеры и повтори /release.
```

**2. Перед деплоем (всегда):**
```
✅ QA пройден. Готов к деплою на Railway production.

Деплоим milestone v0.1?
Подтверди: да / нет
```

**3. После деплоя:**
```
✅ Milestone v0.1 задеплоен.
Railway: https://...
Milestone закрыт в GitHub.
Roadmap в Notion обновлён.
```