# Local development administrator entry

The optional passwordless entry issues the existing JWT/refresh session for an explicitly configured, pre-existing fixture administrator. It never creates a user, changes roles, changes signing keys, disables security, or approves a payment. Normal email-code/mobile-approved admin login remains available.

It is disabled by default. Enabling it requires every boundary below; invalid enabled configuration rejects startup independently of `sequo.security.allow-dev-defaults`.

| Setting | Required local value |
| --- | --- |
| Active profiles | At least one of `local`, `dev`, `development`, `postgres-local`, with no other profile |
| `sequo.admin.local-access.enabled` | `true` (environment alias `SEQUO_LOCAL_ADMIN_ENABLED`) |
| `sequo.admin.local-access.environment` | `LOCAL_FIXTURE` (`SEQUO_LOCAL_ADMIN_ENVIRONMENT`) |
| `sequo.admin.local-access.user-id` | Existing synthetic fixture `users.id` (`SEQUO_LOCAL_ADMIN_USER_ID`) |
| `server.address` | Literal `127.0.0.1` or IPv6 loopback |
| `server.forward-headers-strategy` | `NONE` |
| `sequo.security.cors.allowed-origins` | Explicit HTTP(S) loopback origins, e.g. `http://127.0.0.1:5173`; no wildcard or remote origin |
| `spring.datasource.url` | In-memory H2 or loopback PostgreSQL with database name beginning `sequo_admin_dev` |
| `sequo.auth.jwt.issuer` | `sequo-api-local-admin-fixture` |
| `sequo.auth.jwt.audience` | `sequo-admin-local-fixture` |

Production, staging, Docker, mixed profiles and unrecognized profiles cannot enable this entry. Isolated JWT issuer/audience prevent ordinary deployment parsers accepting fixture access tokens even when the signing key happens to match. Keep existing production credentials separate; no real key rotation is needed for this feature.

Use a dedicated disposable fixture database, not an ordinary local database or a tunnel to production. A development database name is an additional guard, not permission to copy private production data. The operator-owned local bootstrap prepares the fixture identity. Its relevant schema is existing `users(id, email, display_name, is_active, provider, status, created_at, updated_at)` plus `user_roles(user_id, role_code)`. The fixture must be active, have `status=ACTIVE`, and already have `ADMIN` or `SUPER_ADMIN`. `provider=EMAIL` is sufficient; no password/identity record is required by this local-only path. No migration seeds privileged accounts.

## Browser contract

The browser must use the configured loopback API and exact approved Origin. Requests require a direct loopback peer and loopback Host; forwarded/proxy headers are rejected. A browser build flag cannot enable server access.

`GET /api/admin-auth/development-access` returns:

```json
{"enabled":true,"mode":"LOCAL_DEVELOPMENT"}
```

Disabled or untrusted requests return `{"enabled":false,"mode":null}` (ordinary CORS rejection may occur first). This capability indicates configured local entry; the POST still verifies the current fixture account's eligibility.

`POST /api/admin-auth/development-session`, with `Content-Type: application/json`:

```json
{"deviceId":"admin-local-browser-uuid","deviceLabel":"Local admin browser","browserInfo":"Optional browser label"}
```

`deviceId` is required and follows the normal identifier policy; the optional labels have the existing 255-character limit. The browser cannot choose an identity or roles. The response is the existing admin exchange DTO: `accessToken`, `refreshToken`, `expiresIn`, and `user` with the normal profile/roles. Both routes use `Cache-Control: no-store`. No token or account values belong in screenshots, reports or console logs.

The POST returns 404 when disabled, outside the permitted request context, or when the configured fixture is absent/ineligible; 400 for invalid input; 409 for the normal existing-device/account conflict; 429 with `Retry-After` when rate limited. Normal API authorization still requires the bearer token and its actual roles. Session issuance records `LOCAL_DEVELOPMENT_SESSION_CREATED` in the existing admin auth audit.

Keep the same browser `deviceId` for normal `POST /api/auth/refresh`. Refresh rotates the stored refresh token; the old refresh token cannot replay. `POST /api/auth/logout` revokes the refresh session and is safely repeatable. Existing JWT access-token expiry behavior is unchanged: signing out does not retroactively erase an already-issued stateless access token before its normal expiry. Disabling passwordless entry prevents new entry; use normal sign-out or remove the disposable fixture database when finished.

## Verified boundaries and remaining checks

The targeted tests cover disabled production responses, startup refusal for production/staging/Docker profiles despite the development-default bypass, unsafe configuration variants, direct-peer/Host/Origin/proxy boundaries, fixture eligibility without account promotion, normal admin vs customer authorization, ordinary-deployment token rejection, refresh/device mismatch/replay, logout replay and entry rate limiting. Tests use an isolated in-memory fixture only. They do not verify remote hosting, real provider/hardware operations or browser behavior.
