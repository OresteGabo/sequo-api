# Production Deployment

This project can run on a single Linux VPS with Docker Compose. The default Compose setup is sized for a small 4GB RAM server and runs the Spring Boot API with PostgreSQL.

## VPS Prerequisites

Install Docker Engine and the Compose plugin on the VPS, then make sure your user can run Docker:

```bash
docker --version
docker compose version
```

Recommended VPS baseline:

| Resource | Recommendation |
| --- | --- |
| RAM | 4GB minimum |
| Disk | 25GB+ SSD |
| Swap | 1-2GB, useful on small VPS instances |
| Firewall | Open only SSH plus HTTP/HTTPS through your reverse proxy |

## Files To Copy

From your local machine, copy the repository to the server. Either use Git on the VPS:

```bash
ssh deploy@your-vps
git clone https://github.com/YOUR_ACCOUNT/YOUR_REPO.git sequo-api
cd sequo-api
```

Or copy the checked-out project directly:

```bash
rsync -av --exclude .git --exclude build --exclude .gradle ./ deploy@your-vps:/opt/sequo-api/
ssh deploy@your-vps
cd /opt/sequo-api
```

## Environment File

Create the production `.env` from the template:

```bash
cp .env.example .env
chmod 600 .env
```

Edit `.env` and replace every `replace_with...` value. Production startup guardrails intentionally reject placeholders, local URLs, weak/default secrets, wildcard CORS, and H2.

Important values:

| Variable | Notes |
| --- | --- |
| `SPRING_PROFILES_ACTIVE` | Keep as `prod` for VPS deployment. |
| `SEQUO_API_BIND` | Defaults to `127.0.0.1` for reverse-proxy deployments. Use `0.0.0.0` only if exposing the API directly. |
| `SEQUO_API_PORT` | Host port for the API, default `8080`. |
| `POSTGRES_PASSWORD` | Use a unique random password. |
| `JWT_SECRET` | At least 32 random characters. |
| `NOTIFICATION_TOKEN_ENCRYPTION_SECRET` | At least 32 random characters. |
| `CORS_ALLOWED_ORIGINS` | Comma-separated explicit HTTPS origins, no wildcards. |
| `SEQUO_ALLOW_DEV_DEFAULTS` | Set to `true` only for temporary staging/dev VPS boots with placeholder values. Keep `false` for real production. |
| `API_MEMORY_LIMIT` | Defaults to `1536m`. |
| `POSTGRES_MEMORY_LIMIT` | Defaults to `1536m`. |
| `JAVA_TOOL_OPTIONS` | Defaults to `-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom`. |
| `JAVA_OPTS` | Optional extra JVM flags appended by the image entrypoint. |

Generate strong secrets on the VPS:

```bash
openssl rand -base64 48
```

## Reverse Proxy

The Compose file binds the API to `127.0.0.1:8080` by default. Put Nginx, Caddy, or another reverse proxy in front of it for TLS:

```text
https://api.your-domain.com -> http://127.0.0.1:8080
```

Set `CORS_ALLOWED_ORIGINS` to the public HTTPS origins used by your frontend/admin apps.

## Build And Start

Build and start the stack:

```bash
docker compose up -d --build
```

Check status:

```bash
docker compose ps
docker compose logs -f api
```

Health endpoint:

```bash
curl http://127.0.0.1:8080/actuator/health/readiness
```

## Updates

Pull or copy the new code, rebuild, and restart:

```bash
git pull
docker compose up -d --build
docker compose ps
```

Compose keeps PostgreSQL data in the named volume `sequo-api_postgres-data` unless you explicitly remove volumes.

## Backup And Restore

Create a database backup:

```bash
docker compose exec -T postgres sh -c 'pg_dump -U "$POSTGRES_USER" "$POSTGRES_DB"' > sequo-backup.sql
```

Restore into an empty database:

```bash
cat sequo-backup.sql | docker compose exec -T postgres sh -c 'psql -U "$POSTGRES_USER" "$POSTGRES_DB"'
```

## Useful Commands

Stop the stack:

```bash
docker compose down
```

Restart only the API:

```bash
docker compose up -d --build api
```

View recent logs:

```bash
docker compose logs --tail=200 api
docker compose logs --tail=200 postgres
```

Open Adminer only when needed:

```bash
docker compose --profile tools up -d adminer
```

Adminer binds to `127.0.0.1:8081` by default. Use an SSH tunnel instead of exposing it publicly.

## Memory Notes For 4GB VPS

The default production budget leaves room for the OS and reverse proxy:

| Component | Limit |
| --- | --- |
| API container | `1536m` |
| PostgreSQL container | `1536m` |
| API Java heap | Up to 75% of the API container limit |
| Remaining memory | OS, Docker, reverse proxy, filesystem cache |

If the VPS is under memory pressure, lower `API_MEMORY_LIMIT` or `POSTGRES_MEMORY_LIMIT` in `.env`, then run:

```bash
docker compose up -d
```
