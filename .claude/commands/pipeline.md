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

Читает `.claude/agents/software-engineer.md`, `.claude/agents/quality-assurance-engineer.md`,
`.claude/agents/technical-reviewer.md`, `.claude/agents/security-engineer.md`
и выполняет их по цепочке:

```
Issue (ready, не blocked)
  ↓
[software-engineer] создаёт ветку, пишет код, коммитит
  ↓
[quality-assurance-engineer] быстрый режим
  ├── ❌ красный → [software-engineer] фиксит → [qa] снова
  │   (макс 3 итерации, потом эскалация PO)
  └── ✅ зелёный
        ↓
[technical-reviewer] + [security-engineer] — параллельно
  ├── Request Changes / CRITICAL+HIGH
  │     ↓
  │   [software-engineer] читает все comments из PR, правит код
  │     ↓
  │   [quality-assurance-engineer] снова — тесты после правок
  │     ├── ❌ красный → [software-engineer] фиксит → [qa] снова
  │     └── ✅ зелёный
  │           ↓
  │         [technical-reviewer] перепроверяет свои замечания
  │         [security-engineer] перепроверяет если были его замечания
  │           ├── ещё замечания → повторить петлю (макс 3 итерации суммарно)
  │           └── оба Approved
  └── оба Approved
        ↓
PR создан/обновлён → СТОП: ждать merge от PO
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