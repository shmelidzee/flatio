# Flatio

Real estate aggregation platform. Collects listings from multiple sources, provides search and analytics.

## Requirements

- **Java 21** (JDK)
- **Docker** and **Docker Compose**
- **Gradle 8.x** (or use the included wrapper `./gradlew`)

## Quick Start

```bash
# 1. Start PostgreSQL
docker compose -f docker/docker-compose.yml up -d

# 2. Copy local config
cp src/main/resources/application-local.yml.example src/main/resources/application-local.yml

# 3. Start the application
./gradlew bootRun --args='--spring.profiles.active=local'
```

The application will be available at `http://localhost:8080`.

## Configuration

Local configuration lives in `src/main/resources/application-local.yml` — it is git-ignored and must not be committed.

Copy the example and adjust values as needed:

```bash
cp src/main/resources/application-local.yml.example src/main/resources/application-local.yml
```

See `application-local.yml.example` for all available overrides and their descriptions.

## Running Tests

```bash
# Unit tests (fast, no database required)
./gradlew test

# Integration tests (requires Docker — starts PostgreSQL via Testcontainers)
./gradlew integrationTest
```

## Logging

Log format depends on the active Spring profile:

| Profile | Format |
|---------|--------|
| `prod` | Structured JSON via Logstash encoder (`@timestamp`, `level`, `logger_name`, `thread_name`, `message`) |
| any other (e.g. `local`) | Human-readable: `yyyy-MM-dd HH:mm:ss.SSS [thread] LEVEL logger - message` |

No additional configuration is required — the format switches automatically via `logback-spring.xml`.

## API Documentation

Swagger UI is available at `http://localhost:8080/swagger-ui.html` when running locally.

OpenAPI spec: `http://localhost:8080/v3/api-docs`
