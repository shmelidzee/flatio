# Local Setup & Production Deploy

## Prerequisites

- Java 21 (Temurin recommended)
- Docker + Docker Compose
- Gradle 8.x (or use the included wrapper `./gradlew`)
- Node.js 24 + npm on `PATH` — required to build the admin SPA (`frontend/admin/`).
  `./gradlew build` / `bootRun` invoke `npm ci && npm run build` automatically via the
  `com.github.node-gradle.node` plugin (`download.set(false)` — it expects Node already
  installed, does not fetch its own copy). See `docs/architecture.md`, "Admin Interface"
  section, for the full pipeline.

---

## Local Development

### 1. Start PostgreSQL

```bash
docker compose -f docker/docker-compose.yml up -d
```

### 2. Configure environment

Create `src/main/resources/application-local.yml` (in `.gitignore` — never commit):

```yaml
telegram:
  bot:
    token: ${TELEGRAM_BOT_TOKEN}
    username: ${TELEGRAM_BOT_USERNAME}
```

Set environment variables before running (or export them in your shell):

```bash
export TELEGRAM_BOT_TOKEN=your_bot_token
export TELEGRAM_BOT_USERNAME=your_bot_username
```

### 3. Run the application

```bash
./gradlew bootRun --args='--spring.profiles.active=local'
```

### 4. Run tests

```bash
# Unit tests only (fast, ~30–60s)
./gradlew test

# Unit + integration tests (requires Docker for Testcontainers)
./gradlew test integrationTest
```

### 5. Admin SPA — Telegram Login Widget (local dev)

`npm run dev` inside `frontend/admin/` serves the SPA standalone (not through the Spring Boot
static resources pipeline). Requests to `/api/**` are proxied to `http://localhost:8080` by
`vite.config.ts` in dev mode, so the backend (`./gradlew bootRun`) must be running for
`/admin/login` to render the [Telegram Login Widget](https://core.telegram.org/widgets/login) —
the SPA fetches the bot username to render it from `GET /api/v1/auth/telegram-bot-username`
rather than needing its own copy of `TELEGRAM_BOT_USERNAME` at build time. The proxy also means
`CORS_ALLOWED_ORIGINS` does not need to include the Vite dev server's origin, since the browser
only ever talks to Vite, not the backend directly.

---

## Load Testing

NFR-PERF-1 (issue #37): `GET /api/v1/listings` must keep p95 < 500ms and p99 < 1000ms
under 50 concurrent users, with < 0.1% errors, against a database with >=1000 listings.

### 1. Seed data (disposable DB only)

```bash
docker exec -i <postgres-container> psql -U flatio -d flatio < scripts/load-test/seed-listings.sql
```

### 2. Obtain a JWT

`/api/v1/**` requires authentication. There is currently no `/auth/login` endpoint in the
codebase — `JwtService` exists but is not yet wired to an issuing endpoint. Until that exists,
mint a token offline with the same `JWT_SECRET_KEY` used by the running app (HS256/HS384/HS512
is chosen automatically based on key length, same rule as `Keys.hmacShaKeyFor`).

### 3. Run k6

```bash
docker run --rm -i \
  -e BASE_URL=http://host.docker.internal:8080 \
  -e AUTH_TOKEN=<jwt> \
  grafana/k6 run - < scripts/load-test/listings-search.js
```

Results are documented in `docs/qa-reports/milestone-1.7.md`.

---

## Production Deploy

### Architecture

```
Internet → nginx (80/443) → flatio-app (8080, internal) → postgres (5432, internal)
                ↕
           certbot (Let's Encrypt renewal)
```

### 1. Prepare VPS

Requirements: Ubuntu 22.04+, Docker, Docker Compose plugin, open ports 80 and 443.

```bash
sudo apt update && sudo apt install -y docker.io docker-compose-plugin
sudo usermod -aG docker $USER
```

### 2. Configure secrets

Create `/opt/flatio/.env.prod` on the VPS (never commit this file):

```env
DB_NAME=flatio
DB_USER=flatio
DB_PASSWORD=<strong-password>

DOCKER_USERNAME=<your-dockerhub-username>

TELEGRAM_BOT_TOKEN=<your-bot-token>
TELEGRAM_BOT_USERNAME=<your-bot-username>
TELEGRAM_WEBHOOK_URL=https://api.flatio.by

ONLINER_BASE_URL=https://r.onliner.by
```

`TELEGRAM_WEBHOOK_URL` is the public HTTPS base URL Telegram delivers updates to (the bot
token is appended automatically as the path). Required outside the `local` profile — the
application fails to start without it. Locally (`local` profile) the bot keeps using
long-polling instead, since no public URL is available.

### 3. Prepare compose file

Copy `docker/docker-compose.prod.example.yml` to the VPS and fill in `DOMAIN` in `docker/nginx/nginx.conf`:

```bash
scp docker/docker-compose.prod.example.yml user@vps:/opt/flatio/docker-compose.prod.yml
scp docker/nginx/nginx.conf user@vps:/opt/flatio/nginx/nginx.conf
```

Replace `${DOMAIN}` in `nginx/nginx.conf` with your actual domain (e.g. `api.flatio.by`).

### 4. Obtain SSL certificate (first time)

Start nginx without SSL first (comment out the HTTPS server block temporarily), then:

```bash
cd /opt/flatio
docker compose -f docker-compose.prod.yml run --rm certbot certonly \
  --webroot -w /var/www/certbot \
  -d api.flatio.by \
  --email admin@flatio.by \
  --agree-tos --no-eff-email
```

Restore the full nginx.conf and restart:

```bash
docker compose -f docker-compose.prod.yml restart nginx
```

### 5. First deploy

```bash
cd /opt/flatio
docker compose -f docker-compose.prod.yml --env-file .env.prod up -d
```

Verify:

```bash
docker compose -f docker-compose.prod.yml ps
curl -I https://api.flatio.by/actuator/health
```

### 6. Certificate renewal (cron)

Add to VPS crontab (`crontab -e`):

```
0 3 * * * cd /opt/flatio && docker compose -f docker-compose.prod.yml run --rm certbot renew --quiet && docker compose -f docker-compose.prod.yml exec nginx nginx -s reload
```

### 7. Subsequent deploys (CI/CD)

GitHub Actions pushes a new image to Docker Hub on every merge to `master`.
SSH into VPS and pull:

```bash
cd /opt/flatio
docker compose -f docker-compose.prod.yml --env-file .env.prod pull flatio-app
docker compose -f docker-compose.prod.yml --env-file .env.prod up -d --no-deps flatio-app
```

Or configure GitHub Actions with SSH deploy step (see `.github/workflows/ci.yml`).
