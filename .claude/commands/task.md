# /task

Быстрое создание задачи через Product Manager без захода в GitHub вручную.

## Использование
```
/task "описание задачи"
```

## Примеры
```
/task "создать скелет проекта"
/task "настроить springdoc для Swagger UI"
/task "добавить health check endpoint"
/task "настроить GitHub Actions CI"
```

## Что делает
Читает агента из `agents/product-manager.md` (сценарий прямой задачи от PO):
1. Product Manager оформляет Issue по шаблону
2. Определяет тип (chore/feature/docs), приоритет, milestone
3. Показывает черновик Issue
4. Ждёт твоего апрува
5. После «да» — создаёт Issue в GitHub

**Стоппер:** не создаёт Issue без явного апрува.
