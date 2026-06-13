# Local Setup & Production Deploy

## Prerequisites

- Java 21 (Temurin recommended)
- Docker + Docker Compose
- Gradle 8.x (or use the included wrapper `./gradlew`)

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

ONLINER_BASE_URL=https://r.onliner.by
```

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
