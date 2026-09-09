# Sequo Authentication Security Audit

This file is the dedicated authentication and authorization security checklist for Sequo API. It documents what is already treated in the current Spring Boot code, what is only documented as a target, and what must still be fixed before production.

Review date: 2026-08-04

## Current Auth Implementation

The current implementation includes:

- Email/password signup and login.
- BCrypt password hashing.
- JWT access and refresh token issuance.
- A custom `OncePerRequestFilter` for `Authorization: Bearer` tokens.
- Google ID token verification with Google's verifier.
- Facebook token lookup through Graph API.
- Apple login placeholder.
- Forgot-password and reset-password endpoints.
- Basic Spring Security route protection.
- In-memory auth endpoint rate limiting with safe `429` responses and `Retry-After` headers.

Main files:

| File | Responsibility |
| --- | --- |
| `src/main/kotlin/dev/orestegabo/sequo_api/domain/auth/AuthController.kt` | Auth HTTP endpoints |
| `src/main/kotlin/dev/orestegabo/sequo_api/domain/auth/AuthService.kt` | Signup, login, social login, refresh, password reset |
| `src/main/kotlin/dev/orestegabo/sequo_api/domain/auth/JwtService.kt` | JWT creation and parsing |
| `src/main/kotlin/dev/orestegabo/sequo_api/domain/auth/JwtAuthenticationFilter.kt` | Bearer-token authentication filter |
| `src/main/kotlin/dev/orestegabo/sequo_api/domain/auth/SecurityConfig.kt` | Spring Security configuration |
| `src/main/kotlin/dev/orestegabo/sequo_api/domain/auth/User.kt` | User persistence entity |
| `src/main/kotlin/dev/orestegabo/sequo_api/domain/auth/SocialTokenVerifier.kt` | Google/Facebook/Apple verifier adapters |
| `src/main/resources/application.properties` | Local auth/security configuration |

## Status Summary

| Area | Status | Notes |
| --- | --- | --- |
| Password hashing | Treated | BCrypt is used. |
| Basic route authentication | Treated | `/api/auth/**` is public; other routes require authentication. |
| Stateless server sessions | Treated | Spring session creation is stateless. |
| JWT signing | Partially treated | Tokens are signed and now include stronger claims; production-like startup rejects unsafe secrets, but key rotation is still missing. |
| Access/refresh token separation | Treated | Bearer authentication accepts only access tokens; refresh tokens are checked against a server-side session. |
| Refresh revocation/rotation | Treated | Opaque refresh tokens are stored as hashes, rotated, replayed tokens revoke account sessions, and expired rows can be cleaned by the opt-in scheduler. |
| Logout/logout-all | Treated | `POST /api/auth/logout` revokes one refresh session; authenticated `POST /api/auth/logout-all` revokes all sessions. |
| RBAC and roles | Not treated | Authentication principal has no authorities. |
| User account status | Partially treated | Status model exists and auth checks it; admin lifecycle and session revocation are pending. |
| Password reset security | Partially treated | Reset token is no longer returned and is stored hashed; forgot/reset endpoints are rate-limited; notification delivery, audit, and session revocation are still pending. |
| Rate limiting | Partially treated | Signup, login, social login, refresh, forgot-password, and reset-password have single-node in-memory limits. Distributed/gateway limits, audit, and lockout remain pending. |
| Social login hardening | Partially treated | Google is strongest; same-email social login no longer silently links accounts; Facebook and Apple are incomplete. |
| Audit logging | Not treated | No auth/security audit events are persisted. |
| Production config hardening | Partially treated | Production-like profiles now fail on dev JWT/notification secrets, placeholder OAuth IDs, missing Yas/Moov wallet secrets, unsafe CORS origins, H2, H2 console, and unsafe Hibernate DDL modes. Key rotation remains pending. |
| CI/CD security gates | Partially treated | GitHub Actions runs build/tests and PR dependency review; SAST, secret scanning, and deployment smoke tests are pending. |

## Radio-Style Implementation Matrix

Use this table as the working implementation tracker. Each row has exactly one checked status box.

Legend:

- `[x] Implemented`: present in code at the time of review.
- `[x] Partial`: present but not production-safe or incomplete.
- `[x] Not implemented`: missing from code, even if documented as a target.

| # | Auth security feature | Implemented | Partial | Not implemented | Notes |
| ---: | --- | :---: | :---: | :---: | --- |
| 1 | BCrypt password hashing | [x] | [ ] | [ ] | `BCryptPasswordEncoder` is configured and used. |
| 2 | Email/password signup endpoint | [x] | [ ] | [ ] | Exists, but needs validation and email verification. |
| 3 | Email/password login endpoint | [x] | [ ] | [ ] | Exists with endpoint rate limiting; still needs more generic error handling. |
| 4 | Stateless Spring Security sessions | [x] | [ ] | [ ] | `SessionCreationPolicy.STATELESS` is configured. |
| 5 | Default route authentication | [x] | [ ] | [ ] | Non-auth routes require authentication. |
| 6 | Basic bearer-token filter | [x] | [ ] | [ ] | Custom filter extracts `Authorization: Bearer`. |
| 7 | Google ID token verification | [ ] | [x] | [ ] | Uses Google verifier, but needs prod config validation and tests. |
| 8 | Facebook login | [ ] | [x] | [ ] | Calls Graph API, but does not verify app ownership with `debug_token`. |
| 9 | Apple login | [ ] | [ ] | [x] | Placeholder returns `null`; must be disabled or fully verified. |
| 10 | JWT signing | [ ] | [x] | [ ] | Tokens are signed, but secret validation and key rotation are still missing. |
| 11 | JWT access token issuance | [ ] | [x] | [ ] | Includes issuer/audience/jti/type/nbf; access tokens remain short-lived and stateless. |
| 12 | JWT refresh token issuance | [ ] | [x] | [ ] | JWT refresh tokens are now bound to a persisted server-side refresh session. |
| 13 | Access-token-only validation in bearer filter | [x] | [ ] | [ ] | Refresh tokens are no longer accepted by bearer-token validation. |
| 14 | Token type or token-use claim | [x] | [ ] | [ ] | Tokens include `token_use=ACCESS` or `token_use=REFRESH`. |
| 15 | JWT issuer claim | [x] | [ ] | [ ] | Issued and validated. |
| 16 | JWT audience claim | [x] | [ ] | [ ] | Issued and validated. |
| 17 | JWT ID (`jti`) claim | [x] | [ ] | [ ] | Issued for access and refresh JWTs. |
| 18 | JWT not-before (`nbf`) claim | [x] | [ ] | [ ] | Issued for access and refresh JWTs. |
| 19 | JWT session ID claim | [ ] | [ ] | [x] | Missing; needed for session-aware auth. |
| 20 | JWT role/scope claims | [ ] | [x] | [ ] | Role enum and token claim exist; roles are not loaded from DB or enforced yet. |
| 21 | Issuer/audience validation | [x] | [ ] | [ ] | Access and refresh parsing now checks configured issuer/audience. |
| 22 | Refresh token stored as server-side hash | [x] | [ ] | [ ] | `refresh_sessions` stores SHA-256 hashes only; raw refresh tokens are never persisted. |
| 23 | Opaque refresh tokens | [x] | [ ] | [ ] | AuthService issues cryptographically random opaque refresh tokens; only their hashes are persisted. |
| 24 | Refresh token rotation | [x] | [ ] | [ ] | The current refresh session is revoked before a replacement is issued. |
| 25 | Refresh token replay detection | [x] | [ ] | [ ] | Reusing a known revoked or missing refresh session revokes the user’s active sessions. |
| 26 | Logout endpoint | [x] | [ ] | [ ] | `POST /api/auth/logout` revokes the supplied refresh session and is idempotent. |
| 27 | Logout-all endpoint | [x] | [ ] | [ ] | Authenticated `POST /api/auth/logout-all` revokes all active refresh sessions. |
| 28 | Password-reset token generation | [x] | [ ] | [ ] | Uses 32 secure-random bytes encoded as URL-safe Base64. |
| 29 | Reset token not returned in API response | [x] | [ ] | [ ] | Forgot-password response is generic and does not include the token. |
| 30 | Reset token stored hashed | [x] | [ ] | [ ] | `User` stores `resetTokenHash`, not the raw token. |
| 31 | Reset token single-use | [x] | [ ] | [ ] | Cleared after successful reset. |
| 32 | Reset token short TTL | [x] | [ ] | [ ] | Reset token expires after 30 minutes. |
| 33 | Existing session revocation after password reset | [x] | [ ] | [ ] | Successful password reset revokes all active refresh sessions for the account. |
| 34 | User account status model | [x] | [ ] | [ ] | `UserStatus` exists on the `User` entity. |
| 35 | User status checked on login | [x] | [ ] | [ ] | Non-authenticatable users are rejected. |
| 36 | User status checked on refresh | [x] | [ ] | [ ] | Status is checked before issuing replacement tokens. |
| 37 | User roles table/model | [ ] | [x] | [ ] | `RoleCode` enum exists; persistence model is not implemented. |
| 38 | Authorities loaded into Spring Security | [ ] | [ ] | [x] | Filter creates auth token with `emptyList()`. |
| 39 | Method-level role checks | [ ] | [ ] | [x] | No `@PreAuthorize` or equivalent policy yet. |
| 40 | Ownership checks for customer resources | [ ] | [x] | [ ] | Order process overwrites `customerId`, but broader object checks are missing. |
| 41 | Merchant-scoped authorization | [ ] | [ ] | [x] | Needed before merchant APIs. |
| 42 | Courier-scoped authorization | [ ] | [ ] | [x] | Needed before courier mission APIs. |
| 43 | Relay-scoped authorization | [ ] | [ ] | [x] | Needed before Point de Relai APIs. |
| 44 | Cooperative member data isolation | [ ] | [ ] | [x] | Documented target, not implemented. |
| 45 | Admin role protection | [ ] | [ ] | [x] | Needed before admin APIs. |
| 46 | Finance/admin step-up auth | [ ] | [ ] | [x] | Needed for refunds, payouts, and commission changes. |
| 47 | MFA for internal roles | [ ] | [ ] | [x] | Not implemented. |
| 48 | Email verification | [ ] | [ ] | [x] | Signup immediately returns tokens. |
| 49 | Social account linking confirmation | [ ] | [x] | [ ] | Same-email social login now returns `account_link_required`; full authenticated linking flow and linked-identities table are still missing. |
| 50 | Social email verification enforcement | [ ] | [x] | [ ] | Google requires `email_verified`; Facebook and Apple verification are still incomplete. |
| 51 | Auth endpoint rate limiting | [ ] | [x] | [ ] | Signup, login, social login, refresh, forgot-password, and reset-password use in-memory per-IP/per-subject limits with `429` and `Retry-After`; distributed limits remain. |
| 52 | Account lockout or progressive delay | [ ] | [ ] | [x] | No failed-attempt tracking. |
| 53 | Signup abuse protection | [ ] | [x] | [ ] | Signup is rate-limited, but email verification and bot protection are still missing. |
| 54 | Password reset abuse protection | [ ] | [x] | [ ] | Forgot-password response is generic and forgot/reset are rate-limited; audit and notification delivery remain missing. |
| 55 | Account enumeration resistance | [ ] | [x] | [ ] | Forgot-password is generic; signup/login provider hints intentionally reveal auth method for UX and still need lockout/audit controls. |
| 56 | Password strength validation | [x] | [ ] | [ ] | Internal password policy validates length, character groups, dates, calendar terms, names, email terms, leetspeak weak terms, sequences, repeated characters, repeated patterns, phone-like numeric runs, and common/local terms. |
| 57 | Password maximum length guard | [x] | [ ] | [ ] | Password policy caps passwords at 128 characters. |
| 58 | Breached/common password rejection | [ ] | [x] | [ ] | Extended local blocked list exists; real breached-password checks are pending. |
| 59 | Bean Validation on auth DTOs | [ ] | [ ] | [x] | No `@Valid`, `@Email`, `@NotBlank`, or size rules. |
| 60 | Generic auth error model | [ ] | [x] | [ ] | Provider conflicts now use `AuthErrorResponse`; global auth error handling is still missing. |
| 61 | Global exception handler | [ ] | [ ] | [x] | Not implemented. |
| 62 | Auth audit logging | [ ] | [ ] | [x] | No login/reset/refresh/security event audit trail. |
| 63 | Privacy-safe logging/redaction policy | [ ] | [ ] | [x] | No redaction filter or documented logger guard in code. |
| 64 | CORS policy | [ ] | [x] | [ ] | Spring Security now uses explicit configured origins, and production-like profiles reject missing, wildcard, or non-HTTPS origins. |
| 65 | HTTPS/HSTS enforcement | [ ] | [ ] | [x] | Not enforced in app config. |
| 66 | H2 console restricted to local/test | [ ] | [ ] | [x] | Enabled in default properties. |
| 67 | Production-safe schema migration policy | [ ] | [x] | [ ] | Flyway baseline exists and Docker profile defaults to Hibernate `validate`; default local properties still use `ddl-auto=update`. |
| 68 | Production startup rejects default JWT secret | [x] | [ ] | [ ] | `ProductionStartupGuardrails` blocks known dev/CI JWT defaults and short secrets in production-like profiles. |
| 69 | Production startup rejects placeholder OAuth IDs | [x] | [ ] | [ ] | `ProductionStartupGuardrails` blocks known Google, Facebook, and Apple placeholder IDs in production-like profiles. |
| 70 | JWT key rotation strategy | [ ] | [ ] | [x] | No `kid`, key versioning, or rotation procedure. |
| 71 | Device/session tracking | [ ] | [ ] | [x] | No device ID, IP hint, or user-agent hash persistence. |
| 72 | Provider HTTP client timeouts | [ ] | [ ] | [x] | Facebook `RestTemplate` has no explicit timeout. |
| 73 | Provider outage handling | [ ] | [ ] | [x] | Invalid token and upstream outage are not distinguished. |
| 74 | Actuator exposure policy | [ ] | [x] | [ ] | Actuator health is exposed for Docker healthchecks; broader production exposure policy is still needed. |
| 75 | Security integration tests | [ ] | [x] | [ ] | JWT and password-policy unit tests exist; route-level integration tests are pending. |
| 76 | Refresh token replay tests | [ ] | [ ] | [x] | Not possible until refresh sessions exist. |
| 77 | JWT claim validation tests | [ ] | [x] | [ ] | Token-use and issuer/audience tests exist; more negative cases are pending. |
| 78 | RBAC and ownership tests | [ ] | [ ] | [x] | Missing. |
| 79 | Wallet webhook auth checks | [ ] | [ ] | [x] | Future wallet feature; must verify signatures/idempotency. |
| 80 | Delivery/return PIN security checks | [ ] | [ ] | [x] | Future logistics feature; must hash PINs and limit attempts. |
| 81 | Mass-assignment protection | [ ] | [x] | [ ] | `OrderController` protects `customerId`; broader DTO hardening is missing. |
| 82 | Cross-tenant query protections | [ ] | [ ] | [x] | Needs scoped repository/service checks. |
| 83 | Append-only audit tamper resistance | [ ] | [ ] | [x] | No audit table/service yet. |
| 84 | Unsafe local config deployment guard | [ ] | [x] | [ ] | Production-like profiles reject H2, H2 console, `ddl-auto=update`, dev secrets, provider placeholders, missing wallet secrets, and unsafe CORS origins. |
| 85 | Optional on-device AI password coach | [ ] | [ ] | [x] | Future KMP/mobile-only UX helper; must be open-source, local-only, and never replace server validation. |
| 86 | CI executes auth and security tests | [x] | [ ] | [ ] | GitHub Actions runs `./gradlew clean build --no-daemon --stacktrace` on PRs and protected branch pushes. |
| 87 | PR dependency vulnerability review | [ ] | [x] | [ ] | Dependency Review fails high-severity vulnerable dependency changes; broader SAST and secret scanning are still pending. |

## Already Treated In Code

### 1. Password Hashing

Status: Treated

Evidence:

- `SecurityConfig.passwordEncoder()` returns `BCryptPasswordEncoder`.
- `AuthService.signUp()` stores `passwordEncoder.encode(request.password)`.
- `AuthService.login()` verifies passwords with `passwordEncoder.matches(...)`.

Remaining work:

- Add password strength validation.
- Add maximum password length to prevent hash DoS.
- Consider breached-password checks before production.

### 2. Basic Route Protection

Status: Treated, but incomplete for role security

Evidence:

- `SecurityConfig.securityFilterChain()` permits `/api/auth/**`.
- All other routes require authentication.
- `SessionCreationPolicy.STATELESS` is configured.

Remaining work:

- Add role and ownership rules.
- Add tests proving protected routes reject missing, expired, tampered, refresh-type, wrong-issuer, and wrong-audience tokens.

### 3. Basic Bearer Token Authentication

Status: Partially treated

Evidence:

- `JwtAuthenticationFilter` reads the `Authorization` header.
- It accepts `Bearer <token>`.
- It sets a Spring Security principal when `JwtService.validateToken()` returns a subject.

Remaining work:

- Reject refresh tokens as access tokens.
- Load authorities/roles.
- Validate issuer, audience, token type, user status, session status, and revocation state.

### 4. Google Token Verification

Status: Partially treated

Evidence:

- `GoogleTokenVerifier` uses `GoogleIdTokenVerifier`.
- Audience is configured from `sequo.auth.google.client-id`.

Remaining work:

- Reject dev placeholder client IDs outside local profile.
- Add token-level tests with valid, invalid, wrong-audience, expired, and missing-email Google tokens.
- Add authenticated account-linking flow if users should add Google after email/password login.

## Critical Vulnerabilities To Treat First

### 1. Refresh Token Can Be Used As Access Token

Severity: Critical

Observed problem:

- `JwtService.generateTokens()` creates access and refresh tokens with the same signing key and no token type claim.
- `JwtAuthenticationFilter` calls `jwtService.validateToken(token)` and accepts any valid signed token.
- Therefore a 30-day refresh token can be used directly as a bearer token for protected routes.

Expected secure behavior:

- Access tokens must include `token_use=access`.
- Refresh tokens must include `token_use=refresh` or, preferably, become opaque random tokens.
- The auth filter must accept only access tokens.
- Refresh endpoint must accept only refresh tokens.

Treatment:

- Add explicit token-use/type claim immediately.
- Update validation methods to distinguish access and refresh validation.
- Add tests proving refresh tokens cannot call protected APIs.

### 2. Refresh Tokens Are Stateless And Cannot Be Revoked

Severity: Critical

Observed problem:

- `AuthService.refreshTokens()` parses the refresh JWT and issues new tokens.
- Refresh sessions are now stored as token hashes in `refresh_sessions`; the remaining gap is opaque-token migration and richer session-family metadata.
- There is no token hash, token family, rotation, reuse detection, device ID, logout, or logout-all.

Expected secure behavior:

- Use opaque refresh tokens with at least 256 bits of randomness.
- Store only refresh token hashes server-side.
- Rotate refresh tokens on every refresh.
- Detect reuse of a previously rotated token and revoke the entire token family.
- Revoke refresh sessions on logout, password reset, account lock, and suspicious activity.

Treatment:

- Add `refresh_sessions` table.
- Replace refresh JWTs with opaque tokens.
- Add session family and `replaced_by_session_id`.
- Add `/api/auth/logout` and `/api/auth/logout-all`.
- Add replay detection tests.

### 3. Password Reset Token Delivery Is Not Integrated Yet

Severity: Medium/High

Current state:

- `AuthController.forgotPassword()` now returns a generic response.
- The raw reset token is not returned in the HTTP response.
- The service generates a raw token for the future notification sender, stores only its hash, and currently keeps a TODO at the delivery boundary.

Remaining problem:

- Email/SMS delivery is not integrated yet.

Expected secure behavior:

- Always return a generic response like "If the account exists, instructions were sent."
- Send reset links/tokens only through a verified email/SMS channel.
- Never expose reset token in API response, logs, or analytics.

Treatment:

- Add email/SMS notification integration.
- Keep generic responses for known and unknown email addresses.
- Ensure logs and metrics never include the raw reset token.

### 4. Reset Token Hashing Is Implemented

Severity: Treated, with remaining integration work

Current state:

- `User.resetTokenHash` stores the hash instead of the raw token.
- `PasswordResetTokenService` generates 32 secure-random bytes and stores a SHA-256 hash.
- Reset tokens expire after 30 minutes.
- Reset token is cleared after successful use.

Expected secure behavior:

- Store only a hash of the reset token.
- Use high-entropy random tokens.
- Use short expiration, preferably 10 to 30 minutes.
- Single-use only.
- Clear token after success.

Remaining work:

- Add audit events around forgot/reset attempts.
- Revoke existing refresh sessions after password reset once refresh sessions exist.
- Consider HMAC-SHA256 if the team wants a server-secret keyed reset-token hash.

### 5. JWT Claims Are Incomplete

Severity: Critical

Observed problem:

- Tokens include `sub`, `email`, `provider`, `iat`, and `exp`.
- Tokens do not include issuer, audience, JWT ID, not-before, token use, session ID, roles, or scopes.

Expected secure behavior:

- Access tokens should contain `iss`, `aud`, `sub`, `exp`, `iat`, `nbf`, `jti`, `token_use=access`, `session_id`, and safe role/scope claims.
- Refresh tokens should not be JWTs if possible; if they remain JWTs temporarily, they must include `token_use=refresh`.

Treatment:

- Add typed JWT configuration for issuer and audience.
- Validate issuer and audience on every request.
- Add unique `jti`.
- Add token-use validation.

### 6. Hardcoded Development JWT Secret Fallback

Severity: Critical for production

Observed problem:

- `application.properties` defines `sequo.auth.jwt.secret=${JWT_SECRET:sequo_auth_dev_secret_key_2026_v1}`.
- If production starts without `JWT_SECRET`, local defaults could be selected unless startup guardrails reject them.

Expected secure behavior:

- Local development can use a dev profile fallback.
- Staging and production must fail startup if a secure secret is missing.
- Secret must be at least 256 bits and generated outside source control.

Treatment:

- Production-like startup now rejects known dev/default secrets, short secrets, placeholder provider IDs, missing wallet secrets, unsafe CORS origins, H2, H2 console, and unsafe Hibernate DDL modes.
- Keep improving profile isolation by moving dev defaults to `application-local.properties` or test config later.

### 7. Missing User Status Enforcement

Severity: Critical

Observed problem:

- `User` has no `status`.
- Login and refresh do not check whether a user is active, locked, suspended, deleted, or pending verification.

Expected secure behavior:

- Add `UserStatus`: `PENDING`, `ACTIVE`, `LOCKED`, `SUSPENDED`, `DELETED`.
- Reject login and refresh for non-active users.
- Revoke sessions when user status changes.

Treatment:

- Add status column.
- Check status in login, social login, refresh, and token validation.
- Add admin lock/suspend flow later.

### 8. RBAC Is Missing

Severity: Critical for Sequo's multi-role platform

Observed problem:

- `JwtAuthenticationFilter` creates `UsernamePasswordAuthenticationToken(userId, null, emptyList())`.
- There are no authorities, roles, scopes, or merchant/relay ownership checks.

Expected secure behavior:

- Roles: customer, merchant owner, merchant staff, courier, relay partner, support, admin, super admin.
- Role checks must combine with object ownership checks.
- Merchant users can access only their own merchant data.
- Courier users can access only assigned missions.
- Relay users can access only assigned relay parcels.

Treatment:

- Add `user_roles` and scoped-role model.
- Load authorities from database or session claims.
- Add method security with `@PreAuthorize` where appropriate.
- Add centralized ownership-policy service.

### 9. Social Account Linking Can Cause Account Takeover

Severity: Critical

Observed problem:

- Older code linked to an existing account if provider email matched an existing email.
- That was dangerous unless the provider email was verified and the existing user explicitly approved linking.

Expected secure behavior:

- Link only when provider explicitly confirms verified email.
- Require user confirmation or existing session for linking.
- Store provider identity per account in a separate linked-identities table.
- Do not silently replace or merge auth providers.

Current treatment:

- Same-email social login no longer signs into or silently links the existing account.
- Email/password login or signup for a Google-only account returns `409` with `code=auth_provider_required` and `requiredProvider=GOOGLE`.
- Google social login requires Google's `email_verified` claim before creating or matching by email.
- Email/password accounts that later attempt Google login return `409` with `code=account_link_required`.

Remaining treatment:

- Add `social_identities` table.
- Require authenticated linking flow for existing accounts.
- Audit all account-link events.

### 10. Facebook Token Verification Is Incomplete

Severity: High/Critical

Observed problem:

- `FacebookTokenVerifier` calls Graph `/me` with the user token.
- It does not call `debug_token`.
- It does not verify the token was issued for the Sequo app ID.
- It places the token in the URL query string, which can leak through logs or proxies.

Expected secure behavior:

- Use Facebook `debug_token`.
- Verify `app_id`, `is_valid`, expiry, scopes, and user ID.
- Avoid putting access tokens in URLs when possible.
- Use HTTP client timeouts.

Treatment:

- Implement app-token based `debug_token`.
- Verify result before reading `/me`.
- Configure connect/read timeouts.
- Redact tokens from logs.

### 11. Apple Login Is Not Implemented

Severity: High

Observed problem:

- `AppleTokenVerifier.verify()` returns `null`.

Expected secure behavior:

- Either disable Apple login explicitly or implement Apple identity-token verification.
- Fetch Apple JWKS.
- Validate signature, issuer, audience/client ID, expiry, nonce if used, and subject.

Treatment:

- Disable `APPLE` provider until implemented, or add full JWKS validation.
- Add tests for wrong audience, expired token, invalid signature, and missing subject.

## High-Priority Vulnerabilities To Treat

### 12. Single-Node Rate Limiting Needs Production Hardening

Severity: High

Rate-limited endpoints:

- `/api/auth/signup`
- `/api/auth/login`
- `/api/auth/login/social`
- `/api/auth/refresh`
- `/api/auth/forgot-password`
- `/api/auth/reset-password`

Current treatment:

- `AuthRateLimiter` applies leaky-bucket limits per client IP fingerprint and per safe subject key such as normalized email or token hash. Capacity is consumed immediately and released at a steady rate instead of resetting in one burst.
- Limited requests return `429` with a safe `rate_limited` body and a `Retry-After` header.
- Raw emails, reset tokens, refresh tokens, and IP addresses are hashed before being used as limiter subjects.
- The limiter is in-memory and bounded; it is appropriate for the current single-node backend and tests.
- `ApiRequestRateLimitFilter` applies a broader 300-requests-per-minute leaky-bucket guard to `/api/**` routes outside `/api/auth/**`.

Expected secure behavior:

- Per-IP and per-account limits.
- Distributed limits when multiple API instances run.
- Progressive delay or lockout after repeated failures.
- Safe `429` responses.
- Audit repeated attempts.

Remaining treatment:

- Add Redis/Bucket4j, gateway-level rate limiting, or provider-level controls before horizontal scaling.
- Add persistent failed-attempt tracking, risk events, and account lockout/progressive delay.
- Add auth audit logging for blocked and repeated attempts.

### 13. Account Enumeration

Severity: High

Observed problem:

- Signup returns bad request if email exists.
- Forgot-password returns `404` when email does not exist.
- Login returns `401`, but timing and behavior may still differ.

Expected secure behavior:

- Use generic responses for auth flows.
- Avoid revealing whether an email exists.
- Normalize response timing where practical.

Treatment:

- Return generic response for forgot-password.
- Consider generic signup response or email-verification flow.
- Add tests to prevent enumeration regressions.

### 14. Password Policy Needs Breached-Password Integration

Severity: Medium

Current state:

- Signup and reset now use `PasswordPolicy`.
- The policy rejects blank, short, very long, missing character groups, common passwords, local/business terms, leetspeak weak words, obvious keyboard/numeric sequences, repeated-character runs, repeated patterns, phone-like numeric runs, calendar terms with numbers, date-like passwords, and passwords containing the user's name or email terms.

Remaining problem:

- The policy does not check a live breached-password corpus.

Expected secure behavior:

- Minimum length.
- Maximum length.
- Reject common/breached passwords if possible.
- Reject blank passwords.
- Normalize and validate request DTOs.

Treatment:

- Add breached-password checks when an approved provider or offline corpus is selected.
- Keep the local blocked list as a fast baseline.
- Add Bean Validation on auth DTOs.

### 15. Password Reset Does Not Revoke Existing Sessions

Severity: High

Observed problem:

- Password reset changes the password but existing access/refresh tokens remain valid.

Expected secure behavior:

- On password reset, revoke refresh session family or increment token version.
- Optionally invalidate all existing sessions.
- Audit password reset completion.

Treatment:

- Add refresh session store first.
- Revoke all sessions after successful reset.

### 16. No Email Verification

Severity: High

Observed problem:

- Email/password signup immediately issues tokens.
- No email verification state exists.

Expected secure behavior:

- New email accounts should start as pending or unverified.
- Sensitive actions can require verified email or phone.
- Email verification tokens should be hashed and single-use.

Treatment:

- Add email verification flow.
- Add `emailVerifiedAt`.
- Add restricted permissions for unverified users.

### 17. No MFA Or Step-Up Auth For Admin/Finance

Severity: High

Observed problem:

- No admin role exists yet, but future admin/finance routes are planned.

Expected secure behavior:

- Admin, support, settlement, commission, refund, payout, KYC, and role-change actions should require stronger security.
- MFA should be mandatory for internal roles.
- High-risk actions should require recent authentication or step-up.

Treatment:

- Plan MFA before admin/finance modules go live.
- Add `lastStrongAuthAt`.

### 18. No Audit Logs

Severity: High

Observed problem:

- Auth service does not emit audit events.

Expected secure behavior:

- Audit successful login.
- Audit failed login.
- Audit social login.
- Audit account link.
- Audit forgot-password request.
- Audit reset success/failure.
- Audit refresh success/failure.
- Audit refresh replay.
- Audit logout/logout-all.
- Audit account lock/suspend.

Treatment:

- Add `audit_logs` table and service.
- Never log raw tokens or passwords.

### 19. No CORS Policy

Severity: High

Observed problem:

- No explicit CORS policy is configured.

Expected secure behavior:

- Staging/production allowed origins must be explicit.
- Do not use wildcard origins with credentials.
- Mobile app flows should still be defined cleanly for web/admin clients.

Treatment:

- Add CORS configuration by profile.
- Add tests for disallowed origins if web clients are used.

### 20. No HTTPS Or HSTS Enforcement

Severity: High

Observed problem:

- App does not enforce secure channels or set HSTS headers.

Expected secure behavior:

- HTTPS enforced at ingress/load balancer.
- HSTS enabled for browser-based admin/web clients.
- Production docs and deployment templates must make TLS mandatory.

Treatment:

- Configure Spring Security headers.
- Ensure reverse proxy forwards scheme correctly.
- Add production deployment checklist.

### 21. H2 Console Enabled In Default Config

Severity: High for non-local deployments

Observed problem:

- `spring.h2.console.enabled=true` is set in default `application.properties`.

Expected secure behavior:

- H2 console only in local profile.
- Disabled in staging/production.

Treatment:

- Move H2 console config to local/test profile.
- Fail production startup if H2 console is enabled.

### 22. `ddl-auto=update` In Default Config

Severity: High

Observed problem:

- `spring.jpa.hibernate.ddl-auto=update` is active by default.

Expected secure behavior:

- Use Flyway or Liquibase migrations.
- Production should use `validate` or no automatic schema mutation.

Treatment:

- Move `ddl-auto=update` to local profile only.
- Add migration tool before production persistence.

## Medium-Priority Vulnerabilities To Treat

### 23. Access Token Lifetime Is Too Long For High-Risk API

Severity: Medium/High

Observed problem:

- Access token lifetime is 1 hour.

Expected secure behavior:

- Prefer 5 to 15 minutes for production.
- Use refresh rotation for longer sessions.

Treatment:

- Reduce access token TTL after refresh sessions are implemented.

### 24. No JWT Key Rotation Strategy

Severity: Medium/High

Expected secure behavior:

- Support key IDs (`kid`) or versioned signing keys.
- Document rotation procedure.
- Allow emergency token invalidation.

Treatment:

- Add key metadata.
- Consider asymmetric signing or managed JWKS later.

### 25. JWT Secret Length And Entropy Are Not Validated

Severity: Medium/High

Expected secure behavior:

- Validate secret length and entropy at startup.
- Reject known dev defaults outside local/test.

Treatment:

- Add config validator.

### 26. Auth DTOs Lack Bean Validation

Severity: Medium

Observed problem:

- `@RequestBody` DTOs have no `@Valid`.
- Email, password, provider, token, and reset token formats are unchecked.

Treatment:

- Add `spring-boot-starter-validation`.
- Add `@field:Email`, `@field:NotBlank`, `@field:Size`, and custom password validation.

### 27. API Returns Entities/Token DTOs Without Response Envelope

Severity: Medium

Expected secure behavior:

- Consistent response model.
- No accidental sensitive fields.
- Safe auth error messages.

Treatment:

- Add auth response DTOs.
- Add global exception handler.

### 28. No Consistent Error Handling

Severity: Medium

Observed problem:

- Controllers directly return bare `400`, `401`, `404`, or message maps.

Expected secure behavior:

- Consistent error codes.
- No stack traces or implementation details.
- Generic auth messages.

Treatment:

- Add `@ControllerAdvice`.
- Use stable `AUTH_*` error codes.

### 29. Social HTTP Client Has No Explicit Timeout

Severity: Medium

Observed problem:

- Facebook verifier uses default `RestTemplate`.

Expected secure behavior:

- Configure connect/read timeouts.
- Distinguish invalid token from provider outage.
- Avoid blocking request threads indefinitely.

Treatment:

- Use configured `RestTemplateBuilder` or `WebClient`.
- Add timeout and resilience policy.

### 30. Provider Placeholder Values Can Accidentally Enable Broken Auth

Severity: Medium

Observed problem:

- Google/Facebook/Apple client IDs default to dev placeholder values.

Expected secure behavior:

- Production startup must fail when placeholders are present.
- Disabled providers should be explicitly disabled.

Treatment:

- Add provider-enabled config flags.
- Startup validation now rejects placeholder provider IDs in production-like profiles; add provider-enabled config flags next.

### 31. No Device/Session Awareness

Severity: Medium

Expected secure behavior:

- Track device ID, user agent hash, IP hint, last used time.
- Let users revoke sessions.
- Detect suspicious refresh reuse.

Treatment:

- Add refresh session persistence.

### 32. No Account Lockout Or Risk State

Severity: Medium

Expected secure behavior:

- Lock or temporarily challenge accounts after high-risk activity.
- Store failed login counters with safe reset policy.

Treatment:

- Add risk events and lockout policy.

### 33. No Protection Against Reset Token Brute Force

Severity: Medium

Expected secure behavior:

- Rate-limit reset attempts.
- Use high-entropy tokens.
- Store hashed tokens.
- Expire quickly.

Treatment:

- Reset attempts are now rate-limited; add audit events and persistent risk tracking.

### 34. No Protection Against Signup Abuse

Severity: Medium

Expected secure behavior:

- Rate-limit signup.
- Optional CAPTCHA or device checks for public web signup.
- Email/phone verification.

Treatment:

- Signup now has per-IP and per-email rate limits; add email/phone verification and optional bot protection.

### 35. No Privacy-Safe Logging Policy In Code

Severity: Medium

Expected secure behavior:

- Do not log tokens, passwords, reset tokens, Authorization headers, social tokens, provider payloads, phone numbers, or addresses.

Treatment:

- Add logging guidelines and request logging filters with redaction.

### 36. Optional On-Device AI Password Coach

Severity: Optional later enhancement

Placement decision:

- This belongs primarily in the Kotlin Multiplatform mobile app because it helps the user before the password reaches the server.
- The API should keep only the authoritative deterministic password policy.
- The API may later expose non-sensitive password policy metadata or message keys, but it must never depend on the client-side AI for enforcement.
- Shared KMP code can reuse deterministic rules, but the server remains the source of truth because mobile clients can be bypassed.

Expected secure behavior:

- The AI must run on-device only.
- The model must be open-source and reviewed before use.
- The raw password must never be sent to a remote AI service.
- The model should produce user-facing warnings only, not final allow/deny decisions.
- The mobile warning text must be predefined/localized by Sequo, not free-form model output.
- The server must revalidate the password with `PasswordPolicy` even when the mobile app says the password looks strong.

Example predefined warnings:

- "Your password looks like someone's name."
- "Your password looks like a birthday or date."
- "Your password contains a common word."
- "Your password contains a keyboard pattern."
- "Your password contains your email or username."
- "Your password uses repeated characters."
- "Try a longer phrase with unrelated words, numbers, and symbols."

Treatment:

- Add this later in the KMP app password field as an optional local strength coach.
- Keep the server-side `PasswordPolicy` as the source of truth.
- Add mobile tests that verify passwords are not sent to external services for analysis.

### 37. No Actuator Exposure Policy

Severity: Medium

Expected secure behavior:

- Health/info can be public if needed.
- Metrics/env/configprops/loggers must be restricted.

Treatment:

- Add Actuator only with strict exposure config when needed.

### 38. No Security Tests

Severity: Medium/High

Missing tests:

- Public auth endpoints are public.
- Business endpoints reject unauthenticated requests.
- Expired access token rejected.
- Tampered token rejected.
- Refresh token rejected as bearer token.
- Wrong issuer rejected.
- Wrong audience rejected.
- Missing subject rejected.
- Password reset token not returned.
- Refresh rotation and replay detection.
- User status lock blocks login/refresh.
- Roles/ownership enforced.

Treatment:

- Add Spring Security integration tests.
- Add JWT unit tests.

## Future Sequo-Specific Auth Risks

These are not all implemented yet, but must be planned before the related modules go live.

### 39. Merchant Staff Privilege Escalation

Risk:

- Merchant staff may attempt to grant themselves owner/admin-like permissions.

Treatment:

- Scoped merchant permissions.
- Owner-only staff management.
- Audit staff changes.

### 40. Cooperative Member Data Leakage

Risk:

- One cooperative member may see another member's private sales/payout data.

Treatment:

- Cooperative storefront aggregation must not bypass merchant ownership checks.

### 41. Courier Mission ID Guessing

Risk:

- Courier accesses missions not assigned to them by guessing IDs.

Treatment:

- Object-level authorization on delivery missions.

### 42. Relay Parcel ID Guessing

Risk:

- Relay partner accesses parcels at another Point de Relai.

Treatment:

- Relay-scoped authorization.

### 43. Admin Endpoint Exposure

Risk:

- Admin APIs are accidentally available to authenticated customers.

Treatment:

- Role checks and method security.
- MFA for internal roles.
- Separate admin route policy.

### 44. Payout/Refund Abuse

Risk:

- Support/admin user triggers unauthorized refund or payout.

Treatment:

- Finance-admin role.
- Two-person approval for high-value actions.
- Audit and ledger immutability.

### 45. Wallet Webhook Spoofing

Risk:

- Fake Yas/Moov callbacks mark payments paid.

Treatment:

- Provider signature verification.
- Idempotency.
- Amount/currency/reference validation.
- Replay protection.

### 46. Delivery PIN Brute Force

Risk:

- Courier or relay brute-forces customer PIN.

Treatment:

- Hashed PINs.
- Attempt limits.
- Short TTL.
- Audit failed attempts.

### 47. PII Overexposure

Risk:

- Merchants/couriers/support users see more customer data than needed.

Treatment:

- Role-based PII masking.
- Masked contact channels.
- Audit PII access.

### 48. Mass Assignment

Risk:

- Client request bodies set sensitive fields such as `role`, `status`, `ownerId`, `commissionRate`, `balance`, or `customerId`.

Treatment:

- Use narrow request DTOs.
- Server derives owner/customer IDs from principal.
- Reject sensitive fields in client payloads.

### 49. Cross-Tenant Query Bugs

Risk:

- Repository queries fetch by ID without merchant/customer scope.

Treatment:

- Query by `(id, owner_id)` where possible.
- Central ownership checks before mutation.

### 50. Audit Log Tampering

Risk:

- Sensitive actions are changed without traceability.

Treatment:

- Append-only audit logs.
- Restrict audit access.
- Consider write-once export for critical events.

### 51. Dev/Test Config Accidentally Deployed

Risk:

- H2, dev JWT secret, placeholder OAuth IDs, debug logging, or local CORS reaches production.

Treatment:

- Strict Spring profiles.
- Startup config validator.
- CI/CD production config checks.
- GitHub Actions build/test gate and dependency review are now present; add SAST, secret scanning, and deployment smoke tests later.

## Target Auth Architecture

The production auth system should use:

- Short-lived access JWTs, preferably 5 to 15 minutes.
- Opaque refresh tokens stored hashed server-side.
- Refresh rotation on every use.
- Refresh token family reuse detection.
- Logout and logout-all.
- User status checks.
- Role and ownership checks.
- MFA for admin/finance/support.
- Generic auth errors.
- Rate limiting.
- Audit logging.
- Strong provider verification for social login.
- Secure password reset through email/SMS.
- Production startup validation for secrets and unsafe local settings.
- Optional on-device password coaching in the KMP app, with server-side validation remaining authoritative.

## Remediation Checklist

Priority 0, production blockers:

- [x] Add token type claim and reject refresh tokens in bearer filter.
- [x] Replace refresh JWTs with opaque hashed refresh sessions.
- [x] Add refresh rotation and replay detection for the current JWT refresh contract.
- [x] Add logout and logout-all.
- [x] Remove reset token from forgot-password response.
- [x] Hash reset tokens.
- [x] Add JWT issuer, audience, JWT ID, token-use, and not-before claims.
- [ ] Add JWT session ID after refresh sessions exist.
- [x] Reject production-like startup with fallback JWT secrets.
- [x] Add user status and block locked/suspended users.
- [ ] Add user roles and authorities.
- [x] Add first-pass in-memory rate limiting on auth endpoints.
- [ ] Upgrade rate limiting to distributed/gateway-level protection before horizontal scaling.

Priority 1, high security:

- [ ] Add email verification.
- [x] Add password policy.
- [x] Revoke sessions after password reset.
- [ ] Harden Facebook token verification.
- [ ] Disable or implement Apple login.
- [ ] Add generic auth responses to prevent enumeration.
- [ ] Add audit logging.
- [ ] Add CORS policy.
- [ ] Move H2 console and `ddl-auto=update` to local/test profile only.
- [ ] Add security integration tests.

Priority 2, production maturity:

- [ ] Add key rotation strategy.
- [ ] Add MFA/step-up auth for admin and finance.
- [ ] Add device/session management UI/API.
- [ ] Add risk scoring and account lockout.
- [ ] Add privacy-safe logging/redaction.
- [ ] Add Actuator exposure policy.
- [ ] Add provider/client HTTP timeouts and resilience.
- [ ] Add ownership policy service for customers, merchants, couriers, relays, cooperatives, support, and admins.

Optional later, mobile/KMP UX:

- [ ] Add an open-source, on-device-only AI password coach in the KMP app.
- [ ] Use predefined/localized warning messages rather than raw free-form model text.
- [ ] Verify the mobile password coach never sends raw passwords to remote services.
- [ ] Keep API-side `PasswordPolicy` as the final source of truth.

## Minimum Required Tests

Auth tests:

- Signup hashes password.
- Login rejects wrong password.
- Login uses generic failure response.
- Forgot-password does not return token.
- Reset token is hashed and single-use.
- Password reset revokes sessions.
- Suspended user cannot login.
- Locked user cannot refresh.

JWT tests:

- Valid access token accepted.
- Expired access token rejected.
- Tampered token rejected.
- Refresh token rejected as bearer token.
- Wrong issuer rejected.
- Wrong audience rejected.
- Missing subject rejected.
- Missing token use rejected.
- Missing `jti` rejected.

Refresh tests:

- Refresh token stored hashed.
- Refresh rotates token.
- Reusing old refresh token revokes family.
- Logout revokes current refresh session.
- Logout-all revokes all sessions.

Authorization tests:

- Customer cannot access another customer's order.
- Merchant cannot access another merchant's orders.
- Courier cannot access unassigned mission.
- Relay cannot access another relay's parcel.
- Admin endpoints reject customer role.

Social login tests:

- Google wrong audience rejected.
- Google expired token rejected.
- Facebook wrong app ID rejected.
- Apple disabled or fully verified.
- Social email linking requires verified email or explicit account-link flow.

## Notes On Current Documentation

`SECURITY.md`, `DATABASE_SCHEMA.md`, and `API_SPEC.md` already describe the desired secure target: refresh sessions, RBAC, audit logs, wallet signature verification, idempotency, and production-safe secrets. This `AUTH.md` is stricter because it compares that target against the current code and lists the gaps that must be treated.
