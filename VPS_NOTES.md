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
