# CI/CD Pipeline

This repository uses GitHub Actions for the first production-ready automation layer. The pipeline provides continuous integration, deployable build artifacts, Docker validation, PostgreSQL migration validation, and an optional production-host deployment job for pushes to `main`.

## Active Automation

| File | Purpose |
| --- | --- |
| `.github/workflows/ci-cd.yml` | Runs the Gradle build, executes tests, validates Flyway migrations against PostgreSQL, builds the Docker image, reviews dependency changes, publishes the boot JAR artifact from `main`, and can deploy `main` to a configured production host when enabled. |
| `.github/dependabot.yml` | Opens weekly dependency update PRs for Gradle and GitHub Actions dependencies. |

## Workflow Triggers

| Trigger | Behavior |
| --- | --- |
| Pull request to `main` or `develop` | Runs build, tests, and dependency review. |
| Push to `main` or `develop` | Runs build and tests. |
| Push to `main` | Uploads the Spring Boot JAR artifact after the build succeeds. |
| Push to `main` with `AUTO_DEPLOY_ENABLED=true` | Deploys the latest `main` branch to the configured production host after build, Docker image validation, and PostgreSQL migration validation succeed. |
| Manual `workflow_dispatch` | Allows maintainers to run the pipeline on demand. |

## CI Gates

The `Build and Test` job runs on Ubuntu with Java 21 and the Gradle wrapper:

```bash
./gradlew clean build --no-daemon --stacktrace
```

The job sets `SPRING_PROFILES_ACTIVE=test` and CI-only placeholder auth provider values so that tests do not depend on production secrets. Real production secrets must not be committed to this repository.

Required checks before merge:

| Check | Required now | Notes |
| --- | --- | --- |
| Gradle build | Yes | Compiles Kotlin, assembles the Spring Boot artifact, and runs test tasks included in `build`. |
| Unit and context tests | Yes | Covers current auth, pricing, payment, order, and application startup behavior. |
| PostgreSQL migration validation | Yes | Runs a Spring context test against PostgreSQL with Flyway migrations and Hibernate `validate`. |
| Docker image build | Yes | Validates that the Spring Boot API can be packaged into the runtime container. |
| Dependency review | Yes for PRs | Fails PRs that introduce high-severity or critical vulnerable dependency changes. |
| Test reports artifact | Yes | Uploaded on every run for debugging failed CI results. |
| Boot JAR artifact | Yes on `main` | Uploaded after successful pushes to `main`. |
| Production-host deployment | Optional on `main` | Runs only when the repository variable `AUTO_DEPLOY_ENABLED` is set to `true`. |

## Continuous Delivery

The pipeline produces a deployable boot JAR artifact on successful pushes to `main`:

```text
build/libs/*.jar, excluding build/libs/*-plain.jar
```

The optional deployment job connects to a configured production host over SSH, updates the checked-out repository to `origin/main`, rebuilds the Docker Compose API service, and checks both the local readiness endpoint and the public health endpoint.

Remote deployment command:

```bash
cd "${DEPLOY_APP_DIR:-/root/sequo-api}"
git fetch origin main
git checkout main
git reset --hard origin/main
docker compose up -d --build --remove-orphans
curl --fail http://127.0.0.1:8080/actuator/health/readiness
```

Public smoke test:

```bash
curl --fail https://api.sequoservice.com/actuator/health
```

The deploy job is intentionally disabled by default so regular pushes do not fail while production SSH access is unavailable or unset.

## Automatic Deployment Setup

Add these repository secrets in GitHub under `Settings > Secrets and variables > Actions > Repository secrets`:

| Secret | Required | Example | Purpose |
| --- | --- | --- | --- |
| `DEPLOY_HOST` | Yes | `54.37.12.31` | Production host or IP used by GitHub Actions SSH. |
| `DEPLOY_USER` | Yes | `root` | Remote user that can run `git` and `docker compose` in the app folder. |
| `DEPLOY_SSH_KEY` | Yes | Private OpenSSH key | Private key matching a public key in the remote user's `~/.ssh/authorized_keys`. |
| `DEPLOY_SSH_PORT` | No | `22` | SSH port. Defaults to `22` when omitted. |
| `DEPLOY_APP_DIR` | No | `/root/sequo-api` | Remote folder containing this repository and the production `.env`. |

Add this repository variable in `Settings > Secrets and variables > Actions > Variables`:

| Variable | Value | Purpose |
| --- | --- | --- |
| `AUTO_DEPLOY_ENABLED` | `true` | Enables automatic production deployment after successful pushes to `main`. Leave unset or set to `false` while SSH is broken. |

The VPS must already have:

| Requirement | Notes |
| --- | --- |
| Working SSH access from GitHub Actions | Port `22` or the configured `DEPLOY_SSH_PORT` must accept connections. |
| Git repository checkout | Default path is `/root/sequo-api`. |
| Docker and Docker Compose plugin | The workflow runs `docker compose up -d --build --remove-orphans`. |
| Production `.env` on the VPS | Secrets such as database password and JWT keys stay on the server and are not committed. |
| Caddy or reverse proxy already configured | Public health check expects `https://api.sequoservice.com/actuator/health`. |

## Docker

The repository includes a multi-stage `Dockerfile` and `docker-compose.yml`. Compose is the recommended local runtime because it starts the API with PostgreSQL without requiring Kubernetes.

Local run:

```bash
docker compose up --build
```

The CI workflow builds the Docker image on every PR and protected branch push. It does not publish images yet.

## Required Future Production Secrets

CI tests use placeholders, but deployment will need real secrets from the selected hosting platform or a secret manager.

| Secret | Purpose |
| --- | --- |
| `JWT_SECRET` | Strong JWT signing key. Must be unique per environment. |
| `SPRING_DATASOURCE_URL` | Production PostgreSQL JDBC URL. |
| `SPRING_DATASOURCE_USERNAME` | Production database username. |
| `SPRING_DATASOURCE_PASSWORD` | Production database password. |
| `GOOGLE_CLIENT_ID` | Google social auth client ID, if enabled. |
| `FACEBOOK_APP_ID` | Facebook social auth app ID, if enabled. |
| `APPLE_CLIENT_ID` | Apple sign-in client ID, if enabled. |
| `YAS_TOGO_*` | Yas Togo wallet integration credentials and webhook secrets. |
| `MOOV_AFRICA_*` | Moov Africa wallet integration credentials and webhook secrets. |

## Branch Protection Recommendation

Protect `main` before production deployment:

| Rule | Recommendation |
| --- | --- |
| Require pull requests | Enabled. |
| Require status checks | Require `Build and Test` and `Dependency Review`. |
| Require branches up to date | Enabled for `main`. |
| Restrict direct pushes | Enabled. |
| Require signed commits | Recommended before production. |
| Require deployment approval | Enabled for production environment. |

## Next Hardening Steps

Add these once the project moves closer to production:

| Step | Status |
| --- | --- |
| Add a Dockerfile and container image build. | Implemented |
| Publish container images to GHCR or the selected cloud registry. | Not implemented |
| Add Flyway or Liquibase migration validation in CI. | Implemented with Flyway baseline plus PostgreSQL validation job. |
| Add OWASP dependency scanning or Snyk after the dependency policy is chosen. | Not implemented |
| Add CodeQL/SAST if GitHub code scanning is available for the repository plan. | Not implemented |
| Add deployment smoke tests against the selected environment. | Not implemented |
| Add rollback and release tagging workflow. | Not implemented |
