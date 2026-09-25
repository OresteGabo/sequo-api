# Authentication Architecture

This document is the production authentication blueprint for Sequo API. It is aligned with the current Kotlin/Spring Boot 4.1 codebase, Flyway schema, and security package under `dev.orestegabo.sequo_api.domain.auth`, while also defining the path from day-one social sign-in to a multi-channel enterprise identity platform.

## Current Repository Baseline

| Area | Current implementation |
| --- | --- |
| Framework | Spring Boot 4.1, Kotlin, JVM 21 |
| Security | Spring Security, stateless filter chain, custom `JwtAuthenticationFilter` |
| Persistence | Spring Data JPA, Flyway, H2 local/test, PostgreSQL target |
| Token library | JJWT 0.12.6 |
| Auth endpoints | `/api/auth/signup`, `/login`, `/login/social`, `/refresh`, `/logout`, `/logout-all`, `/sessions`, `/me`, password reset |
| Identity tables | `users`, `social_identities`, `user_roles`, `merchant_memberships`, `refresh_sessions` |
| Current social support | Google strongest path; Facebook and Apple configuration exists, but controller currently accepts Google only |
| Current password hashing | BCrypt via `BCryptPasswordEncoder`; production target is Argon2id |
| Current refresh token model | Opaque raw refresh token, SHA-256 hash in `refresh_sessions`, rotation and replay handling |
| Rate limiting | In-memory auth endpoint limiter plus generic API request limiter |
| Production guardrails | Production-like profiles reject dev secrets, placeholder providers, unsafe CORS, H2, H2 console, unsafe DDL |

## High-Level Strategy And Rationale

Sequo should launch authentication with Google, Apple, and Meta as the preferred day-one channels. For a solo or lean engineering team operating from Lome, Togo, this is the highest-leverage security choice because it removes a large amount of password risk from the first release.

The rationale is practical:

- Credential stuffing moves to mature identity providers that already handle anomaly detection, device signals, breach intelligence, and phishing-resistant options.
- Sequo avoids storing most customer passwords during the period when product, logistics, payments, and settlement correctness matter more than building identity infrastructure from scratch.
- Database blast radius is lower because most user accounts can start without password hashes.
- Support burden is lower because users can recover Google, Apple, or Meta accounts through familiar provider flows.
- The product can still support enterprise growth later through email/password, phone/OTP, merchant staff invites, admin MFA, and account linking.

The backend remains the final authority. Provider tokens are never trusted directly by domain APIs. Sequo verifies provider tokens, resolves or creates a local user, then issues Sequo access tokens and refresh sessions.

```text
External Identity Provider  ->  Sequo Auth API  ->  Sequo JWT + Refresh Session
Google / Apple / Meta token      verifies token      used by Sequo APIs only
```

## Identity Model

The core design principle is to separate the user profile from authentication methods.

- `users` is the canonical human/account record.
- `auth_identities` is the target provider identity table. The current implementation uses `social_identities`; a future migration should generalize it to `auth_identities`.
- `refresh_tokens` is the target server-side refresh token table. The current implementation uses `refresh_sessions`.
- `sessions` is the target device/session metadata table. The current implementation folds session lifecycle into `refresh_sessions`.
- `user_roles` and `merchant_memberships` hold global and merchant-scoped authority.

This separation allows one user to sign in with Google today, add Apple later, add phone OTP for wallet operations, and add email/password only if needed.

## Target Entity Relationship Model

```text
users 1---n auth_identities
users 1---n sessions
sessions 1---n refresh_tokens
users 1---n user_roles
users 1---n merchant_memberships
```

### `users`

Purpose: stable Sequo account profile and lifecycle state.

```sql
create table users (
    id varchar(255) primary key,
    email varchar(255),
    email_verified boolean not null default false,
    phone_e164 varchar(32),
    phone_verified boolean not null default false,
    name varchar(255),
    preferred_language varchar(16) not null default 'fr',
    status varchar(32) not null default 'ACTIVE',
    created_at timestamp with time zone not null default current_timestamp,
    updated_at timestamp with time zone not null default current_timestamp,
    version bigint not null default 0,
    constraint chk_users_status check (status in ('PENDING', 'ACTIVE', 'LOCKED', 'SUSPENDED', 'DELETED'))
);

create unique index uk_users_email_present on users (lower(email)) where email is not null;
create unique index uk_users_phone_present on users (phone_e164) where phone_e164 is not null;
create index idx_users_status on users (status);
```

Current migration note: `V1__create_auth_users.sql` already creates `users` with `email`, `password_hash`, `name`, `provider`, `status`, legacy `provider_id`, and reset-token fields. The target model should move provider-specific state out of `users`; keep legacy fields during migration until all accounts are backfilled into `auth_identities`.

### `auth_identities`

Purpose: all login methods linked to a user.

```sql
create table auth_identities (
    id varchar(255) primary key,
    user_id varchar(255) not null,
    provider varchar(32) not null,
    provider_subject varchar(255) not null,
    email_at_provider varchar(255),
    email_verified_at_provider boolean not null default false,
    phone_at_provider varchar(32),
    password_hash varchar(512),
    password_algorithm varchar(32),
    last_login_at timestamp with time zone,
    created_at timestamp with time zone not null default current_timestamp,
    updated_at timestamp with time zone not null default current_timestamp,
    constraint fk_auth_identities_user foreign key (user_id) references users (id),
    constraint chk_auth_identities_provider check (provider in ('GOOGLE', 'APPLE', 'META', 'EMAIL', 'PHONE'))
);

create unique index uk_auth_identity_provider_subject
    on auth_identities (provider, provider_subject);

create unique index uk_auth_identity_email_login
    on auth_identities (lower(email_at_provider))
    where provider = 'EMAIL' and email_at_provider is not null;

create unique index uk_auth_identity_phone_login
    on auth_identities (phone_at_provider)
    where provider = 'PHONE' and phone_at_provider is not null;

create index idx_auth_identities_user on auth_identities (user_id);
```

Current migration note: `V4__create_social_identity_table.sql` creates `social_identities` with the essential `(provider, provider_subject)` uniqueness rule. The target table generalizes this to email/password and phone/OTP.

### `sessions`

Purpose: device-aware session family tracking.

```sql
create table sessions (
    id varchar(255) primary key,
    user_id varchar(255) not null,
    device_id varchar(255),
    device_label varchar(255),
    user_agent_hash varchar(128),
    ip_hint varchar(64),
    created_at timestamp with time zone not null default current_timestamp,
    last_seen_at timestamp with time zone,
    revoked_at timestamp with time zone,
    revoke_reason varchar(64),
    constraint fk_sessions_user foreign key (user_id) references users (id)
);

create index idx_sessions_user_active on sessions (user_id, revoked_at);
```

Current implementation note: `refresh_sessions` already acts as a server-side session store. Introduce `sessions` when device management, session families, and richer security events become necessary.

### `refresh_tokens`

Purpose: opaque refresh token rotation and replay detection.

```sql
create table refresh_tokens (
    id varchar(255) primary key,
    session_id varchar(255) not null,
    user_id varchar(255) not null,
    token_hash varchar(64) not null,
    family_id varchar(255) not null,
    issued_at timestamp with time zone not null,
    expires_at timestamp with time zone not null,
    used_at timestamp with time zone,
    revoked_at timestamp with time zone,
    replaced_by_token_id varchar(255),
    constraint fk_refresh_tokens_session foreign key (session_id) references sessions (id),
    constraint fk_refresh_tokens_user foreign key (user_id) references users (id),
    constraint uk_refresh_tokens_hash unique (token_hash)
);

create index idx_refresh_tokens_session_active on refresh_tokens (session_id, revoked_at, expires_at);
create index idx_refresh_tokens_family on refresh_tokens (family_id, revoked_at);
```

Current implementation note: `V22__create_refresh_sessions.sql` already stores `token_hash`, `expires_at`, `last_used_at`, and `revoked_at`. This is MVP-safe. Add `family_id`, `device_id`, and `replaced_by_session_id` or split into the target two-table model when multi-device management matures.

## Account Linking And Merging

Account linking must be explicit and authenticated. A user who controls one identity can add another identity to the same `users.id`.

Rules:

- Never silently merge two accounts only because emails match.
- If Google returns `email_verified=true` and the email exists under an email/password account, return `409 account_link_required`.
- The user must authenticate with the existing method first, then confirm linking the new provider.
- Provider subject uniqueness wins over email claims because provider email can change or be masked.
- Admin account merging requires audit logs, reason codes, and no loss of order/payment history.

Linking flow:

```text
Client authenticates existing account
Client starts "link provider"
Client obtains provider token
Backend verifies provider token
Backend checks provider subject uniqueness
Backend inserts auth_identities row for same user_id
Backend emits account_linked audit event
```

Apple private relay handling:

- Store Apple `sub` as the durable identifier.
- Treat relay emails like `...@privaterelay.appleid.com` as contact aliases, not canonical identity proof.
- Do not overwrite a real verified email with an Apple relay email.
- If Apple only supplies name/email on first authorization, persist it immediately and never depend on future payloads containing it.

## Authentication Flows

### Social Login: Google, Apple, Meta

```text
Mobile/Web Client
  -> obtains native provider token
  -> POST /api/auth/login/social { provider, token }

Sequo API
  -> verifies provider signature and claims
  -> validates issuer, audience, expiry, subject
  -> requires verified email where provider supports it
  -> finds auth identity by provider + subject
  -> otherwise checks linking policy by verified email
  -> creates or updates user + identity
  -> creates refresh session
  -> returns Sequo access token + opaque refresh token
```

Verification requirements:

- Google: verify ID token signature through Google's certs/JWKS, `iss`, `aud`, `exp`, `sub`, and `email_verified`.
- Apple: verify ID token against Apple JWKS, `iss=https://appleid.apple.com`, configured bundle/service `aud`, `exp`, `sub`, and nonce when used by native clients.
- Meta: prefer official token debugging or signed request verification. Validate app ID, subject, expiry, and email verification where available.

### Future Email/Password Flow

Email/password should be available for merchants, staff, admins, and users who cannot use social identity, but it should be hardened before broad use.

Production requirements:

- Replace BCrypt with Argon2id using Spring Security's `Argon2PasswordEncoder`.
- Use per-password salt from the encoder output.
- Cap password length at 128 characters to avoid hash DoS.
- Keep password policy validation already present in `PasswordPolicy`.
- Add distributed rate limits through Redis or gateway controls.
- Add persistent failed-attempt counters and progressive delay.
- Revoke all refresh sessions after password reset or suspicious password change.
- Add email verification before enabling full account privileges.

Spring bean target:

```kotlin
@Bean
fun passwordEncoder(): PasswordEncoder =
    Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8()
```

### Future Phone Number / OTP Flow

Phone OTP matters in West African commerce because phone numbers and mobile money wallets are often the most practical identity handle.

Design:

- Store E.164 numbers in `users.phone_e164`.
- Store phone login as `auth_identities(provider='PHONE', provider_subject=phone_e164)`.
- Generate OTP with `SecureRandom`, six decimal digits, short TTL of 3 to 5 minutes.
- Store only OTP hash, not plaintext.
- Bind OTP to phone number, purpose, IP/device fingerprint, and attempt counter.
- Allow a small number of failed attempts, then lock that OTP and progressively delay new sends.
- Integrate SMS provider through an adapter and outbox table, not directly inside the auth transaction.

OTP table:

```sql
create table otp_challenges (
    id varchar(255) primary key,
    phone_e164 varchar(32) not null,
    purpose varchar(32) not null,
    otp_hash varchar(128) not null,
    expires_at timestamp with time zone not null,
    consumed_at timestamp with time zone,
    failed_attempts integer not null default 0,
    locked_at timestamp with time zone,
    created_at timestamp with time zone not null default current_timestamp
);

create index idx_otp_phone_purpose_active
    on otp_challenges (phone_e164, purpose, expires_at, consumed_at);
```

## Spring Security Blueprint

### Filter Chain

The current `SecurityConfig` is correctly stateless:

- CORS configured from `sequo.security.cors.allowed-origins`.
- CSRF disabled for token APIs.
- `SessionCreationPolicy.STATELESS`.
- `/api/auth/**` mostly public, except authenticated session management endpoints.
- `JwtAuthenticationFilter` before `UsernamePasswordAuthenticationFilter`.

Target route policy:

```kotlin
authorizeHttpRequests {
    it.requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
    it.requestMatchers("/api/auth/login", "/api/auth/login/social", "/api/auth/refresh").permitAll()
    it.requestMatchers("/api/auth/forgot-password", "/api/auth/reset-password").permitAll()
    it.requestMatchers("/api/auth/me", "/api/auth/logout-all", "/api/auth/sessions/**").authenticated()
    it.requestMatchers("/api/admin/**").hasAnyRole("ADMIN", "SUPER_ADMIN")
    it.requestMatchers("/api/merchant/**").hasAnyRole("MERCHANT_OWNER", "MERCHANT_STAFF", "ADMIN", "SUPER_ADMIN")
    it.requestMatchers("/api/driver/**").hasAnyRole("COURIER", "ADMIN", "SUPER_ADMIN")
    it.anyRequest().authenticated()
}
```

### JWT Access Tokens

Access token claims:

| Claim | Required | Meaning |
| --- | --- | --- |
| `iss` | yes | Sequo API issuer |
| `aud` | yes | Mobile/web API audience |
| `sub` | yes | `users.id` |
| `jti` | yes | Unique token ID |
| `iat`, `nbf`, `exp` | yes | Token timing |
| `token_use` | yes | Must be `ACCESS` |
| `roles` | yes | Backend-issued roles |
| `merchant_scope_ids` | yes | Merchant IDs user may operate |
| `sid` | yes | Session/refresh-session ID |

`JwtAuthenticationFilter` must continue accepting only `token_use=ACCESS`. Refresh tokens must never authorize API calls.

### RBAC

The repository currently defines `RoleCode` values: `CUSTOMER`, `MERCHANT_OWNER`, `MERCHANT_STAFF`, `COURIER`, `RELAY_PARTNER`, `SUPPORT_AGENT`, `ADMIN`, `SUPER_ADMIN`.

For the requested product language, map:

| Product role | Current code role | Purpose |
| --- | --- | --- |
| `CUSTOMER` | `CUSTOMER` | Browse, checkout, orders, returns, profile |
| `STORE_OWNER` | `MERCHANT_OWNER` | Merchant catalog, fulfillment, payouts, staff |
| `DRIVER` | `COURIER` | Delivery missions, proof, availability |
| `ADMIN` | `ADMIN` / `SUPER_ADMIN` | Operations, support, finance, configuration |

RBAC must always be paired with ownership checks:

- Customer can access only their own orders and returns.
- Merchant owner/staff can access only scoped merchant IDs in `merchant_scope_ids`.
- Driver can access only assigned missions or public offers they are eligible to accept.
- Admin actions require audit events and, for finance/security operations, step-up MFA.

## Token Storage Guidelines

Web:

- Prefer HTTP-only, Secure, SameSite cookies for refresh tokens.
- Store access token in memory where possible.
- Use CSRF protection if refresh cookie is sent automatically.
- Keep access token TTL short, for example 15 minutes in production.

Native mobile:

- Store refresh token in platform secure storage: Android Keystore-backed storage and iOS Keychain.
- Keep access token in memory.
- Bind refresh sessions to a device ID generated by the app and rotated on reinstall.
- Revoke all sessions on lost-device report.

Never store raw tokens in logs, analytics, crash reports, or database rows.

## Security Best Practices And Edge Cases

- Use HTTPS only outside local development.
- Configure CORS with explicit HTTPS origins.
- Add key rotation with `kid` once asymmetric JWT signing is introduced.
- Prefer asymmetric JWT signing for multi-service verification.
- Persist auth audit events for login success/failure, refresh reuse, account lock, password reset, role grant, and provider link/unlink.
- Introduce Redis-backed rate limits before scaling beyond one API instance.
- Lock or progressively delay accounts after repeated password or OTP failures.
- Reject social tokens with missing subject, wrong audience, expired claims, or unverified email.
- Store provider payload hashes for diagnostics if needed, never raw long-lived provider tokens.
- Preserve Apple relay email privacy and avoid using relay email as proof that two accounts are the same person.

## Future Flyway Migration Sketch

```sql
-- Vxx__generalize_auth_identity_model.sql
alter table users add column if not exists email_verified boolean not null default false;
alter table users add column if not exists phone_e164 varchar(32);
alter table users add column if not exists phone_verified boolean not null default false;
alter table users add column if not exists created_at timestamp with time zone not null default current_timestamp;
alter table users add column if not exists updated_at timestamp with time zone not null default current_timestamp;
alter table users add column if not exists version bigint not null default 0;

create unique index if not exists uk_users_phone_present
    on users (phone_e164)
    where phone_e164 is not null;

create table auth_identities (
    id varchar(255) primary key,
    user_id varchar(255) not null,
    provider varchar(32) not null,
    provider_subject varchar(255) not null,
    email_at_provider varchar(255),
    email_verified_at_provider boolean not null default false,
    phone_at_provider varchar(32),
    password_hash varchar(512),
    password_algorithm varchar(32),
    last_login_at timestamp with time zone,
    created_at timestamp with time zone not null default current_timestamp,
    updated_at timestamp with time zone not null default current_timestamp,
    constraint fk_auth_identities_user foreign key (user_id) references users (id),
    constraint uk_auth_identity_provider_subject unique (provider, provider_subject)
);

insert into auth_identities (
    id, user_id, provider, provider_subject, email_at_provider, email_verified_at_provider,
    last_login_at, created_at, updated_at
)
select id, user_id, provider, provider_subject, verified_email, verified_email is not null,
       last_login_at, created_at, current_timestamp
from social_identities;
```

## Implementation Checklist

- Keep Google login as the first production social path.
- Complete Apple JWKS verification before enabling Apple login.
- Complete Meta app ownership verification before enabling Meta login.
- Move password hashes from `users` to `auth_identities` when generalizing identity.
- Replace BCrypt with Argon2id before making email/password a primary consumer channel.
- Add device/session metadata to refresh sessions.
- Add auth audit table and events.
- Add distributed rate limiting.
- Add method-level RBAC and ownership tests for customer, merchant, driver, relay, support, and admin APIs.
