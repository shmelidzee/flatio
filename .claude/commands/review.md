# /review

Запускает Technical Reviewer на PR.

## Использование
```
/review [pr-number]
```

## Примеры
```
/review 15     — проверить PR #15
/review        — проверить последний открытый PR в текущей ветке
```

## Что делает
Читает агента из `.claude/agents/technical-reviewer.md`:
1. Читает PR и связанные Issues
2. Проверяет соответствие Acceptance Criteria
3. Проверяет архитектуру, безопасность, производительность, мультирегиональность
4. Проверяет code style, Javadoc, Swagger аннотации
5. Оставляет inline review comments в PR
6. Выставляет Request Changes или Approved

**Запускать только после зелёных тестов от `/qa`.**
