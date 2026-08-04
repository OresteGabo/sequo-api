# Docker Runtime

Docker is useful for Sequo now because it gives the backend a repeatable local runtime with PostgreSQL, without introducing Kubernetes complexity too early.

## Files

| File | Purpose |
| --- | --- |
| `Dockerfile` | Multi-stage Java 21 image build for the Spring Boot API. |
| `docker-compose.yml` | Local API, PostgreSQL runtime, healthchecks, and optional database UI. |
| `.dockerignore` | Keeps local build output, IDE files, secrets, and Git metadata out of the Docker build context. |
| `.env.example` | Optional local environment template for Compose variables. |
| `src/main/resources/application-docker.properties` | Spring profile used inside Docker containers. |

## Local Compose Runtime

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

Default local endpoints:

| Service | URL |
| --- | --- |
| API | `http://localhost:8080` |
| API readiness | `http://localhost:8080/actuator/health/readiness` |
| PostgreSQL from host | `localhost:5433` |
| PostgreSQL from API container | `postgres:5432` |
| Adminer, optional | `http://localhost:8081` |

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

Compose has safe local defaults for development, but production must use real secrets from the deployment platform or a secret manager.

To customize local values:

```bash
cp .env.example .env
```

Then edit `.env`. The `.env` file is intentionally ignored by Git.

Important variables:

| Variable | Purpose |
| --- | --- |
| `SEQUO_API_PORT` | Host port mapped to the API container. |
| `SEQUO_POSTGRES_PORT` | Host port mapped to PostgreSQL. Defaults to `5433` to avoid colliding with a local PostgreSQL on `5432`. |
| `SEQUO_ADMINER_PORT` | Host port mapped to optional Adminer database UI. |
| `POSTGRES_DB` | Local database name. |
| `POSTGRES_USER` | Local database user. |
| `POSTGRES_PASSWORD` | Local database password. |
| `JWT_SECRET` | Local JWT signing secret. Must be replaced outside local development. |
| `NOTIFICATION_TOKEN_ENCRYPTION_SECRET` | Secret used to encrypt FCM tokens at rest. Must be replaced outside local development. |
| `SPRING_JPA_HIBERNATE_DDL_AUTO` | Local schema strategy. Defaults to `validate` because Flyway owns schema creation. |
| `JAVA_OPTS` | Optional JVM tuning. |

## Image Build

Build the API image only:

```bash
docker build -t sequo-api:local .
```

Run the built image manually against an existing PostgreSQL instance:

```bash
docker run --rm -p 8080:8080 \
  -e SPRING_PROFILES_ACTIVE=docker \
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
| Production startup validation for unsafe dev defaults. | Not implemented |
| Database migrations with Flyway or Liquibase. | Implemented with Flyway migrations for current auth, delivery, and notification schemas |
| Image vulnerability scanning. | Not implemented |
| Published images in GHCR or cloud registry. | Not implemented |
| Runtime health endpoint. | Implemented |
| Deployment smoke tests. | Not implemented |
