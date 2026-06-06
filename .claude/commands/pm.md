# /pm

Запускает Product Manager для работы с бэклогом и Issues.

## Использование
```
/pm [decompose | task "описание" | status]
```

## Примеры
```
/pm decompose          — декомпозировать новые требования из Notion на GitHub Issues
/pm task "настроить Flyway миграции"   — создать конкретную задачу
/pm status             — показать статус Issues текущего milestone
/pm                    — спросит что нужно сделать
```

## Что делает
Читает агента из `agents/product-manager.md` и выполняет нужный сценарий:

**decompose** — читает Notion, сверяет с GitHub Issues, создаёт новые Issues для нереализованных FR.

**task** — создаёт одну конкретную задачу: показывает черновик Issue, ждёт апрув PO, создаёт в GitHub.

**status** — показывает открытые Issues текущего milestone, что готово, что заблокировано.
