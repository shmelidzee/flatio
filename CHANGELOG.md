# Changelog

All notable changes to Flatio are documented here.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

---

## [Unreleased]

## [0.0.1-SNAPSHOT] — 2026-06-07

### Added
- **PR #52 — Structured JSON logging (issue #5, M1.1.8)**
  - Added `net.logstash.logback:logstash-logback-encoder:7.4` dependency
  - Added `logback-spring.xml`: `prod` profile outputs structured JSON via `LogstashEncoder`
    (fields: `@timestamp`, `level`, `logger_name`, `thread_name`, `message`);
    all other profiles use a human-readable console pattern
  - Added unit tests: `LogbackConfigurationTest`, `LogbackProdProfileTest`

- **PR #51 — Springdoc OpenAPI / Swagger UI (issue #4)**
  - Added `springdoc-openapi-starter-webmvc-ui:2.4.0` dependency
  - Swagger UI available at `/swagger-ui.html`; OpenAPI spec at `/v3/api-docs`

- **PR #50 — README and local setup (issue #3)**
  - Added `README.md` with quick-start instructions
  - Added `src/main/resources/application-local.yml.example`

- **PR #2 — Project skeleton (issue #1)**
  - Java 21 + Spring Boot 3.2.12 + Gradle 8.x (Kotlin DSL)
  - PostgreSQL 16 + Flyway migrations
  - Lombok, MapStruct, Resilience4j, Testcontainers baseline
  - Docker Compose for local PostgreSQL
  - GitHub Actions CI workflow
