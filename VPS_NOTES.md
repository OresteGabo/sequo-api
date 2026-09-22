# Sequo VPS Notes

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

