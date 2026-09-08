# Placeholder Replacement Tracker

This file tracks hard-coded placeholder values that must be reviewed before staging, production, or real provider integration.

Do not replace every test fixture blindly. Values under "Test-only fixtures" are acceptable in tests, but they must not be copied into runtime configuration, seed data, demos, screenshots, or production docs.

## Must Replace Before Staging Or Production

| Area | Placeholder/reference | Current location | Required replacement |
| --- | --- | --- | --- |
| Database | `sequo_dev_password` | `docker-compose.yml`, `src/main/resources/application-docker.properties`, `DOCKER.md` | Environment-specific database password from local `.env` or secret manager. |
| JWT | `sequo_auth_dev_secret_key_2026_v1` | `src/main/resources/application.properties`, `ProductionStartupGuardrails`, tests, `AUTH.md` | Strong per-environment JWT signing secret. |
| JWT | `sequo_compose_dev_secret_key_2026_change_before_prod` | `docker-compose.yml`, `ProductionStartupGuardrails`, tests | Local Compose only. Replace with secret manager value outside local/CI. |
| JWT | `ci_only_sequo_auth_secret_2026_change_me` | `ProductionStartupGuardrails` | CI-only unsafe marker. Never use as runtime secret. |
| Notifications | `sequo_notifications_dev_encryption_key_2026_change_before_prod` | `application.properties`, `FcmTokenProtector`, `ProductionStartupGuardrails`, tests | Strong per-environment FCM token encryption secret. |
| Notifications | `sequo_compose_notification_token_secret_2026_change_before_prod` | `docker-compose.yml`, `ProductionStartupGuardrails`, tests | Local Compose only. Replace outside local/CI. |
| Notifications | `ci_only_sequo_notification_secret_2026_change_me` | `ProductionStartupGuardrails` | CI-only unsafe marker. Never use as runtime secret. |
| OAuth | `google_dev_client_id`, `google_docker_dev_client_id` | `application.properties`, `application-docker.properties`, `docker-compose.yml`, tests | Real Google OAuth client ID or disable Google login by config. |
| OAuth | `facebook_dev_app_id`, `facebook_docker_dev_app_id` | `application.properties`, `application-docker.properties`, `docker-compose.yml`, tests | Real Facebook app ID or disable Facebook login by config. |
| OAuth | `apple_dev_client_id`, `apple_docker_dev_client_id` | `application.properties`, `application-docker.properties`, `docker-compose.yml`, tests | Real Apple Services ID/client ID or disable Apple login by config. |
| Wallets | `yas_togo_dev_api_key_2026_change_before_prod` | `application.properties`, `ProductionStartupGuardrails`, tests | Real Yas Togo API credential from secret manager. |
| Wallets | `yas_togo_dev_webhook_secret_2026_change_before_prod` | `application.properties`, `ProductionStartupGuardrails`, tests | Real Yas Togo webhook signing secret from secret manager. |
| Wallets | `moov_africa_dev_api_key_2026_change_before_prod` | `application.properties`, `ProductionStartupGuardrails`, tests | Real Moov Africa API credential from secret manager. |
| Wallets | `moov_africa_dev_webhook_secret_2026_change_before_prod` | `application.properties`, `ProductionStartupGuardrails`, tests | Real Moov Africa webhook signing secret from secret manager. |
| Wallets | `ci_only_yas_togo_*`, `ci_only_moov_africa_*` | `ProductionStartupGuardrails` | CI-only unsafe markers. Never use as runtime secrets. |
| CORS | `https://app.sequo.example`, `https://admin.sequo.example` | security config tests | Real web/app origins for staging and production. |
| Media/CDN | `https://cdn.sequo.example/...` | `ProductMediaPolicyServiceTest` | Real media CDN or object storage public delivery domain when storage is implemented. |
| Local URLs | `http://localhost:8080`, `http://localhost:8081`, `localhost:5433` | `DOCKER.md`, `docker-compose.yml`, tests | Local-only examples. Do not use in deployed configuration. |
| Routing | `straight-line-estimator` | `RoutingDistance.kt` | Replace with configured provider name when a real routing provider is selected. |
| Password docs | `replace_this_with_a_strong_local_secret` | `DOCKER.md` | Example only. Use generated local secret values. |

## Test-Only Fixtures To Keep Out Of Runtime Data

| Area | Fixture | Current location | Notes |
| --- | --- | --- | --- |
| OAuth tests | `1234567890-sequo.apps.googleusercontent.com` | guardrail tests | Looks realistic but is not a real production client ID. |
| OAuth tests | `123456789012345` | guardrail tests | Fake Facebook app ID. |
| JWT tests | `12345678901234567890123456789012` | JWT/security tests | Deterministic 32-byte test secret only. |
| Emails | `*@sequo.test` | auth, notification, migration tests | RFC-style test domain. Good for tests; do not seed into production. |
| Synthetic social email | `*@provider.sequo.local` | `AuthService.providerScopedEmail` | Internal fallback identity format. Revisit before real social identity table. |
| Routing tests | `recording-provider` | routing tests | Test double only. |
| Media tests | `catalog-rice-photo`, `attieke-1`, `sealed-rice-1` | catalog tests | Test fixtures only. |
| Password denylist | `123456`, `987654`, `123456789012`, `qwerty123456`, `azerty123456`, `sequo123456` | `PasswordPolicy.kt` | Intentional unsafe password examples; do not remove as placeholders. |

## Follow-Up Task

- Move local-only defaults out of shared runtime config where possible.
- Add explicit provider enable/disable flags for Google, Facebook, Apple, Yas Togo, and Moov Africa.
- Add a production readiness check that fails if any value from this tracker appears in a deployed environment.
- Update this tracker whenever a new `.example`, fake numeric ID, local URL, test domain, or `change_before_prod` value is introduced.
