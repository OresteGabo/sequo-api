# CI/CD Pipeline

This repository uses GitHub Actions for the first production-ready automation layer. The pipeline currently provides continuous integration plus a continuous-delivery artifact. Production deployment should be added after the hosting target is chosen.

## Active Automation

| File | Purpose |
| --- | --- |
| `.github/workflows/ci-cd.yml` | Runs the Gradle build, executes tests, reviews dependency changes, and publishes the boot JAR artifact from `main`. |
| `.github/dependabot.yml` | Opens weekly dependency update PRs for Gradle and GitHub Actions dependencies. |

## Workflow Triggers

| Trigger | Behavior |
| --- | --- |
| Pull request to `main` or `develop` | Runs build, tests, and dependency review. |
| Push to `main` or `develop` | Runs build and tests. |
| Push to `main` | Uploads the Spring Boot JAR artifact after the build succeeds. |
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
| Dependency review | Yes for PRs | Fails PRs that introduce high-severity or critical vulnerable dependency changes. |
| Test reports artifact | Yes | Uploaded on every run for debugging failed CI results. |
| Boot JAR artifact | Yes on `main` | Uploaded after successful pushes to `main`. |

## Continuous Delivery

The current CD stage produces a deployable boot JAR artifact on successful pushes to `main`:

```text
build/libs/*.jar, excluding build/libs/*-plain.jar
```

This is intentionally provider-neutral. Once the production target is selected, add a deployment job after the build passes. Recommended options for this backend are:

| Target | Fit |
| --- | --- |
| Render, Fly.io, Railway | Good for early managed deployments with simple secret configuration. |
| AWS ECS or App Runner | Good when Sequo needs stronger production isolation and cloud-native scaling. |
| Google Cloud Run | Good for containerized deployment with simple traffic splitting and rollback. |
| Kubernetes | Good later, when multiple services and workers exist. |

Production deployment should use GitHub Environments with required reviewers, environment-scoped secrets, and rollback instructions.

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
| Add a Dockerfile and container image build. | Not implemented |
| Publish container images to GHCR or the selected cloud registry. | Not implemented |
| Add Flyway or Liquibase migration validation in CI. | Not implemented |
| Add OWASP dependency scanning or Snyk after the dependency policy is chosen. | Not implemented |
| Add CodeQL/SAST if GitHub code scanning is available for the repository plan. | Not implemented |
| Add deployment smoke tests against the selected environment. | Not implemented |
| Add rollback and release tagging workflow. | Not implemented |
