# Sequo VPS Notes

## Public API

Primary public API domain:

```text
https://api.sequoservice.com
```

Public health check:

```bash
curl -i https://api.sequoservice.com/actuator/health
```

Expected result: HTTP `200` with Spring Boot health status `UP`.

Raw VPS host remains useful for SSH and low-level diagnostics:

```text
vps-d4bc6ae7.vps.ovh.net
```

## Connect

```bash
ssh -i ~/.ssh/sequo_api_deploy ubuntu@vps-d4bc6ae7.vps.ovh.net
```

Go to the backend folder:

```bash
cd /home/ubuntu/sequo-api
```

## Normal Deploy Flow

```bash
git add ...
git commit -m "..."
git push origin main
```

For normal code changes, do not SSH into the VPS to run `docker compose down` or `docker compose up`.
GitHub Actions does that automatically after the `main` branch build, Docker build, and PostgreSQL migration validation pass.

## Automatic GitHub Deployment

GitHub Actions deploys automatically to this VPS after a successful push to `main` when this repository variable is enabled:

```text
AUTO_DEPLOY_ENABLED=true
```

Required GitHub Actions secrets:

```text
DEPLOY_HOST=54.37.12.31
DEPLOY_USER=ubuntu
DEPLOY_SSH_KEY=<private deploy key>
DEPLOY_SSH_PORT=22
DEPLOY_APP_DIR=/home/ubuntu/sequo-api
```

These names are intentionally host-provider neutral. If the production owner later moves the backend away from OVH, only the secret values should need to change.

The remote deploy command updates the repository to `origin/main`, rebuilds the Docker Compose stack, and checks the API readiness endpoint.

Keep production secrets in `/home/ubuntu/sequo-api/.env`. This file stays on the VPS and must not be committed.

## Manual Debug Commands

Use SSH only when GitHub Actions fails, when `.env` must be edited, or when server logs are needed.

```bash
cd /home/ubuntu/sequo-api
docker compose ps
docker compose logs --tail=200 api
docker compose logs --tail=200 postgres
curl http://127.0.0.1:8080/actuator/health/readiness
```

Restart only the API manually if needed:

```bash
docker compose up -d --build api
```

## Flyway Migration Rule

After a database has applied Flyway migrations, do not edit, delete, or squash those existing migration files.
Always add a new migration with the next version number, for example `V33__...sql`.

Changing an already-applied migration causes Flyway checksum mismatch errors and prevents the API from starting.
