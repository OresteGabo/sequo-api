# Docker Runtime

Docker is useful for Sequo because it gives the backend a repeatable runtime with PostgreSQL, without introducing Kubernetes complexity too early. The default Compose file is production-oriented for a single VPS; local development can still use the `docker` Spring profile explicitly.

## Files

| File | Purpose |
| --- | --- |
| `Dockerfile` | Multi-stage Java 21 image build for the Spring Boot API. |
| `docker-compose.yml` | API, PostgreSQL runtime, healthchecks, resource limits, log rotation, and optional database UI. |
| `.dockerignore` | Keeps local build output, IDE files, secrets, and Git metadata out of the Docker build context. |
| `.env.example` | Production environment template for Compose variables. |
| `src/main/resources/application-prod.yml` | Production Spring profile for Docker/VPS deployment. |
| `src/main/resources/application-docker.properties` | Local Docker development profile. |
| `DEPLOYMENT.md` | Step-by-step VPS deployment guide. |

## Production Compose Runtime

Create a private environment file and replace all placeholder values:

```bash
cp .env.example .env
chmod 600 .env
```

Start the API and PostgreSQL:

```bash
docker compose up -d --build
```

Check the stack:

```bash
docker compose ps
docker compose logs -f api
```

Default production bindings expose the API publicly while keeping database/admin tools local:

| Service | URL |
| --- | --- |
| API | `http://0.0.0.0:8080` / `http://vps-d4bc6ae7.vps.ovh.net:8080` |
| API readiness | `http://vps-d4bc6ae7.vps.ovh.net:8080/actuator/health/readiness` |
| PostgreSQL from host | `127.0.0.1:5432` |
| PostgreSQL from API container | `postgres:5432` |
| Adminer, optional | `http://127.0.0.1:8081` |

Put a TLS reverse proxy in front of the API for public traffic. See `DEPLOYMENT.md` for the VPS runbook.

## Local Compose Runtime

For local development, set these values in `.env` if you want to keep using known development placeholders:

```dotenv
SPRING_PROFILES_ACTIVE=docker
SEQUO_ALLOW_DEV_DEFAULTS=true
SEQUO_POSTGRES_PORT=5433
POSTGRES_PASSWORD=sequo_dev_password
JWT_SECRET=sequo_compose_dev_secret_key_2026_change_before_prod
NOTIFICATION_TOKEN_ENCRYPTION_SECRET=sequo_compose_notification_token_secret_2026_change_before_prod
CORS_ALLOWED_ORIGINS=https://app.sequo.dev
GOOGLE_CLIENT_ID=google_docker_dev_client_id
FACEBOOK_APP_ID=facebook_docker_dev_app_id
APPLE_CLIENT_ID=apple_docker_dev_client_id
YAS_TOGO_API_KEY=yas_togo_dev_api_key_2026_change_before_prod
YAS_TOGO_WEBHOOK_SECRET=yas_togo_dev_webhook_secret_2026_change_before_prod
MOOV_AFRICA_API_KEY=moov_africa_dev_api_key_2026_change_before_prod
MOOV_AFRICA_WEBHOOK_SECRET=moov_africa_dev_webhook_secret_2026_change_before_prod
```

Start the API and PostgreSQL:

```bash
docker compose up --build
```

Run in the background:

```bash
docker compose up --build -d
```

Stop containers:

```bash
docker compose down
```

Stop containers and remove the local PostgreSQL volume:

```bash
docker compose down -v
```

Default local endpoints after setting `SEQUO_POSTGRES_PORT=5433`:

| Service | URL |
| --- | --- |
| API | `http://127.0.0.1:8080` |
| API readiness | `http://127.0.0.1:8080/actuator/health/readiness` |
| PostgreSQL from host | `localhost:5433` |
| PostgreSQL from API container | `postgres:5432` |
| Adminer, optional | `http://127.0.0.1:8081` |

Start the optional database UI:

```bash
docker compose --profile tools up --build
```

Adminer login values with default local settings:

| Field | Value |
| --- | --- |
| System | `PostgreSQL` |
| Server | `postgres` |
| Username | `sequo` |
| Password | `sequo_dev_password` |
| Database | `sequo` |

## Environment

Compose now requires an `.env` file for production. The `.env` file is intentionally ignored by Git.

Important variables:

| Variable | Purpose |
| --- | --- |
| `SEQUO_API_PORT` | Host port mapped to the API container. |
| `SEQUO_API_BIND` | Host interface for the API. Defaults to `0.0.0.0` for direct mobile testing. |
| `SEQUO_POSTGRES_PORT` | Host port mapped to PostgreSQL. Defaults to `5432`; use `5433` locally to avoid colliding with host PostgreSQL. |
| `SEQUO_ADMINER_PORT` | Host port mapped to optional Adminer database UI. |
| `POSTGRES_DB` | Local database name. |
| `POSTGRES_USER` | Local database user. |
| `POSTGRES_PASSWORD` | Local database password. |
| `JWT_SECRET` | Local JWT signing secret. Must be replaced outside local development. |
| `NOTIFICATION_TOKEN_ENCRYPTION_SECRET` | Secret used to encrypt FCM tokens at rest. Must be replaced outside local development. |
| `SPRING_JPA_HIBERNATE_DDL_AUTO` | Local schema strategy. Defaults to `validate` because Flyway owns schema creation. |
| `API_MEMORY_LIMIT` | API container memory limit. Defaults to `1536m`. |
| `POSTGRES_MEMORY_LIMIT` | PostgreSQL container memory limit. Defaults to `1536m`. |
| `JAVA_TOOL_OPTIONS` | JVM memory and runtime tuning. Defaults to `-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom`. |
| `JAVA_OPTS` | Optional extra JVM flags appended by the image entrypoint. |

## Image Build

Build the API image only:

```bash
docker build -t sequo-api:local .
```

Run the built image manually against an existing PostgreSQL instance:

```bash
docker run --rm -p 8080:8080 \
  -e SPRING_PROFILES_ACTIVE=prod \
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://host.docker.internal:5432/sequo \
  -e SPRING_DATASOURCE_USERNAME=sequo \
  -e SPRING_DATASOURCE_PASSWORD=sequo_dev_password \
  -e JWT_SECRET=replace_this_with_a_strong_local_secret \
  -e NOTIFICATION_TOKEN_ENCRYPTION_SECRET=replace_this_with_a_strong_local_secret \
  sequo-api:local
```

## Production Position

For now, Docker plus Compose is the right level:

| Tool | Use now? | Reason |
| --- | --- | --- |
| Docker | Yes | Gives the API a reproducible runtime image. |
| Docker Compose | Yes | Runs the API and PostgreSQL together for local development and integration testing. |
| Kubernetes | Not yet | Useful later when Sequo has multiple independently scaled services, workers, ingress rules, secrets, and production operations needs. |

Before using this image in production, add:

| Requirement | Status |
| --- | --- |
| Production startup validation for unsafe dev defaults. | Implemented |
| Database migrations with Flyway or Liquibase. | Implemented with Flyway migrations for current auth, delivery, and notification schemas |
| Image vulnerability scanning. | Not implemented |
| Published images in GHCR or cloud registry. | Not implemented |
| Runtime health endpoint. | Implemented |
| Deployment smoke tests. | Documented in `DEPLOYMENT.md` |
