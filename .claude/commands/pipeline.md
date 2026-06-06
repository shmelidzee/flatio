# /pipeline

Оркестратор исполнительного слоя. Проводит issue через весь цикл разработки до PR.

## Использование
```
/pipeline [issue-number]
```

## Примеры
```
/pipeline 42       — провести issue #42 через весь цикл
/pipeline          — взять следующий issue по приоритету
```

## Что делает

Читает `agents/software-engineer.md`, `agents/quality-assurance-engineer.md`,
`agents/technical-reviewer.md`, `agents/security-engineer.md`, `agents/technical-writer.md`
и выполняет их по цепочке:

```
Issue (ready, не blocked)
  ↓
[software-engineer] создаёт ветку, пишет код
  ↓
[quality-assurance-engineer] быстрый режим
  ├── ❌ красный → software-engineer фиксит → qa снова (макс 3 итерации)
  └── ✅ зелёный →
        ↓
[technical-reviewer] + [security-engineer] — параллельно
  ├── Request Changes / CRITICAL/HIGH → software-engineer правит → оба снова (макс 3 итерации)
  └── Оба Approved →
        ↓
PR создан → СТОП: ждать merge от PO
```

## Точки остановки

Оркестратор останавливается и уведомляет тебя в двух случаях:

**1. Эскалация** — после 3 итераций правок без решения:
```
⚠️ Эскалация по issue #42
Агент: [technical-reviewer / security-engineer]
Проблема: [описание]
Требуется твоё решение.
```

**2. PR готов к merge:**
```
✅ PR #N готов к merge.
Issue: #42 — [заголовок]
Ветка: feature/issue-42-slug
Reviewer: Approved
Security: Approved
Тесты: зелёные

Смержи PR когда будешь готов, затем запусти /sync.
```

## После merge
Запусти `/sync` — обновит документацию и статусы.