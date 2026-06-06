# /qa-full

Запускает QA Engineer в полном режиме — перед закрытием milestone.

## Использование
```
/qa-full [milestone-number]
```

## Примеры
```
/qa-full 1     — полное QA для milestone v0.1
/qa-full       — полное QA для текущего milestone
```

## Что делает
Читает агента из `agents/quality-assurance-engineer.md` в полном режиме:
1. Запускает unit тесты + интеграционные тесты через Testcontainers
2. Генерирует Jacoco отчёт о покрытии
3. Проверяет покрытие: Service 100%, Parser 100%, Repository 100%, Controller 80%+
4. Проверяет чеклист парсеров (8 обязательных тест-кейсов)
5. Пишет QA отчёт в `docs/qa-reports/milestone-N.md`
6. Если есть блокеры — создаёт Issues с меткой `bug` + `blocker`

**Результат:** milestone можно закрывать ✅ или заблокирован до исправления ❌
