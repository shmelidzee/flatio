# /sync

Синхронизирует состояние после merge — обновляет документацию и роадмап.

## Использование
```
/sync [pr-number]
```

## Примеры
```
/sync 15       — синхронизировать после merge PR #15
/sync          — синхронизировать после последнего merge
```

## Что делает

Запускает два агента последовательно:

**1. technical-writer** — обновляет документацию в репо:
- README.md, docs/api.md, docs/parsers.md, docs/architecture.md
- CHANGELOG.md
- Коммитит: `docs: update after #N`

**2. product-manager** — обновляет роадмап в Notion:
- Статус закрытых Issues → Done
- Если все Issues milestone закрыты → статус milestone → Done
- Роадмап в Notion актуализируется
- Актуализируются Issue в GitHub

```
merge PR #N
  ↓
[technical-writer] обновляет docs/ и CHANGELOG
  ↓
[product-manager] обновляет статусы в Roadmap Notion
  ↓
Готово — петля замыкается
```

## Результат
```
✅ Синхронизация завершена.

Документация: обновлены [список файлов]
Changelog: добавлена запись для #N
Notion Roadmap: [milestone] — X/Y Issues закрыты
```