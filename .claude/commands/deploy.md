# /deploy

Запускает DevOps Engineer для деплоя на Railway.

## Контуры
| Контур | Railway Environment | Ветка |
|--------|--------------------|----|
| Development | development | develop |
| Production | production | master |

Railway автоматически деплоит при пуше в develop или master.
Feature ветки не деплоятся — только мержатся в develop через PR.

## Использование
```
/deploy check [dev|prod]   — проверить готовность контура
/deploy status             — статус обоих контуров
/deploy logs [dev|prod]    — последние логи контура
```

## Примеры
```
/deploy check dev    — проверить development перед демо
/deploy check prod   — проверить production перед релизом
/deploy status       — быстрый статус обоих контуров
/deploy logs prod    — посмотреть логи production
```

## Что делает

### /deploy check dev
Проверяет development контур:
- [ ] Приложение запущено (Railway статус Running)
- [ ] Health check `/actuator/health` возвращает 200
- [ ] Flyway миграции применились (`/actuator/health/db`)
- [ ] Логи не содержат ERROR при старте
- [ ] Environment variables настроены

### /deploy check prod
Проверяет production контур (строже):
- [ ] Все пункты из dev check
- [ ] Тесты зелёные в CI для master
- [ ] Нет открытых Issues с меткой `blocker`
- [ ] QA отчёт для текущего milestone существует и зелёный

### /deploy status
```
🚂 Railway Status

Development (develop → development)
  Статус: ✅ Running | ❌ Failed | 🔄 Deploying
  Последний деплой: [время]
  Health: ✅ OK | ❌ DOWN

Production (master → production)
  Статус: ✅ Running | ❌ Failed | 🔄 Deploying
  Последний деплой: [время]
  Health: ✅ OK | ❌ DOWN
```

## Как работает деплой

```
feature/issue-N  →  PR  →  ты мержишь в develop
                              ↓
                     Railway автодеплоит develop → development
                              ↓
                     /release  →  ты мержишь develop в master
                              ↓
                     Railway автодеплоит master → production
```

## Стопперы

DevOps Engineer не запускает ручной деплой в production если:
- Тесты красные в CI
- Есть открытые Issues с меткой `blocker`
- Health check development не проходит

```
❌ Production деплой заблокирован.
Причина: [описание]
Исправь и повтори /deploy check prod.
```