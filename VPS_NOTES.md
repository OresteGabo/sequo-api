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
ssh ubuntu@vps-d4bc6ae7.vps.ovh.net
```

Password hint:

```text
Muhirehonore
```

Become root:

```bash
sudo -i
```

Go to the backend folder:

```bash
cd /root/sequo-api
```

Alternative direct root SSH:

```bash
ssh -4 root@vps-d4bc6ae7.vps.ovh.net
```

## Redeploy API

```bash
docker compose down
docker compose up -d --build
docker compose logs -f api
```

## Automatic GitHub Deployment

GitHub Actions can deploy automatically to this VPS after a successful push to `main`.

The deploy job is disabled until the repository variable is enabled:

```text
AUTO_DEPLOY_ENABLED=true
```

Required GitHub Actions secrets:

```text
DEPLOY_HOST=54.37.12.31
DEPLOY_USER=root
DEPLOY_SSH_KEY=<private deploy key>
DEPLOY_SSH_PORT=22
DEPLOY_APP_DIR=/root/sequo-api
```

These names are intentionally host-provider neutral. If the production owner later moves the backend away from OVH, only the secret values should need to change.

The remote deploy command updates the repository to `origin/main`, rebuilds the Docker Compose stack, and checks the API readiness endpoint.
