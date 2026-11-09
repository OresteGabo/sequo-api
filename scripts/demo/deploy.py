import pathlib,subprocess,secrets,json,hashlib,time,urllib.request
root=pathlib.Path('/home/ubuntu/sequo-demo-v1')
assert root.is_dir()
jar=root/'app.jar'
expected=(root/'sha256.txt').read_text().strip()
assert hashlib.sha256(jar.read_bytes()).hexdigest()==expected
# This standalone project never mounts or connects to the production database/network.
envfile=root/'.env'
if not envfile.exists():
    settings={'POSTGRES_PASSWORD':secrets.token_urlsafe(36),'JWT_SECRET':secrets.token_urlsafe(48),'NOTIFICATION_TOKEN_ENCRYPTION_SECRET':secrets.token_urlsafe(48),'ADMIN_CODE_ENCRYPTION_SECRET':secrets.token_urlsafe(48),'DEMO_ACCESS_CODE':secrets.token_urlsafe(18)}
    envfile.write_text(''.join(k+'='+v+'\n' for k,v in settings.items()));envfile.chmod(0o600)
(root/'Dockerfile').write_text('FROM sequo-api:product-options-20261109\nUSER root\nCOPY --chown=sequo:sequo app.jar /app/app.jar\nUSER sequo\n')
(root/'compose.yml').write_text('''name: sequo-demo-v1
services:
  demo-postgres:
    image: postgres:16-alpine
    environment:
      POSTGRES_DB: sequo_demov1
      POSTGRES_USER: demo_app
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD:?required}
    volumes:
      - demo-postgres:/var/lib/postgresql/data
    healthcheck:
      test: [CMD-SHELL, "pg_isready -U demo_app -d sequo_demov1"]
      interval: 5s
      timeout: 3s
      retries: 20
    mem_limit: 256m
    restart: unless-stopped
  api:
    image: sequo-api:demo-v1-20261109
    env_file: .env
    environment:
      SPRING_PROFILES_ACTIVE: demo
      SPRING_DATASOURCE_URL: jdbc:postgresql://demo-postgres:5432/sequo_demov1
      SPRING_DATASOURCE_USERNAME: demo_app
      SPRING_DATASOURCE_PASSWORD: ${POSTGRES_PASSWORD:?required}
      SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE: 5
      JAVA_TOOL_OPTIONS: -Xmx512m
      DEMO_PUBLIC_URL: https://api.sequoservice.com:8443
      CORS_ALLOWED_ORIGINS: https://api.sequoservice.com:8443
      SEQUO_ALLOW_DEV_DEFAULTS: "false"
      NOTIFICATION_OUTBOX_WORKER_ENABLED: "false"
      NOTIFICATION_PROVIDER_DELIVERY_WORKER_ENABLED: "false"
      ADMIN_EMAIL_ENABLED: "false"
      YAS_TOGO_API_BASE_URL: ""
      YAS_TOGO_API_KEY: ""
      YAS_TOGO_MERCHANT_ACCOUNT_ID: ""
      YAS_TOGO_WEBHOOK_SECRET: ""
      MOOV_AFRICA_API_BASE_URL: ""
      MOOV_AFRICA_API_KEY: ""
      MOOV_AFRICA_MERCHANT_ACCOUNT_ID: ""
      MOOV_AFRICA_WEBHOOK_SECRET: ""
      APPLE_CLIENT_ID: demo-disabled
      GOOGLE_CLIENT_ID: demo-disabled
      FACEBOOK_APP_ID: demo-disabled
      REFRESH_SESSION_CLEANUP_ENABLED: "true"
      MERCHANT_RESPONSE_DEADLINE_ENABLED: "false"
      MERCHANT_RESPONSE_DEADLINE_SCHEDULER_ENABLED: "false"
    depends_on:
      demo-postgres:
        condition: service_healthy
    ports:
      - "127.0.0.1:8081:8080"
    mem_limit: 850m
    read_only: true
    tmpfs:
      - /tmp:size=128m,mode=1777
    security_opt:
      - no-new-privileges:true
    restart: unless-stopped
volumes:
  demo-postgres:
''')
subprocess.run(['docker','build','-t','sequo-api:demo-v1-20261109',str(root)],check=True)
subprocess.run(['docker','compose','--project-directory',str(root),'-f',str(root/'compose.yml'),'config','--quiet'],check=True)
subprocess.run(['docker','compose','--project-directory',str(root),'-f',str(root/'compose.yml'),'up','-d','--no-build'],check=True)
print('Started private isolated demo API and fresh database. Live API unchanged; HTTPS demo not enabled yet.')
