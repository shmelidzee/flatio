# /dev

Запускает Software Engineer для реализации задачи.

## Использование
```
/dev [issue-number]
```

## Примеры
```
/dev 42        — взять issue #42 в работу
/dev           — взять следующий issue по приоритету
```

## Что делает
Читает агента из `agents/software-engineer.md`:
1. Читает указанный Issue (или берёт следующий по приоритету из текущего milestone)
2. Создаёт ветку `feature/issue-N-slug`
3. Реализует задачу строго по Acceptance Criteria
4. После каждого коммита сигналит QA Engineer
5. После зелёных тестов создаёт PR

**Стоппер:** если Issue не указан и все Issues в milestone заблокированы — сообщает PO.
