# Guardrail Codex Prompt: Spring Boot API Audit

Use this prompt when Guardrail AI is not available yet, or when you want Codex to perform a full security, resilience, API contract, and architecture pass on a Spring Boot backend.

## Copy-paste prompt

Act as a Senior Staff Software Engineer specializing in Spring Boot, Spring Security, API security, JWT/OAuth2 lifecycle design, resilience engineering, and developer experience.

You are auditing a real Spring Boot API repository. Your mission is to check and correct everything Guardrail AI is supposed to catch: hardcoded secrets, local path leaks, unsafe JWT/token lifecycle, weak API security, missing rate limiting, missing resilience controls, unsafe config, poor architecture, and AI-generated shortcuts.

Work directly in the project. Prefer safe, minimal, production-quality fixes over broad rewrites. Preserve existing behavior unless the current behavior is insecure. Do not commit, push, delete files, rotate real credentials, run destructive database migrations, or make external account changes unless I explicitly ask.

## Operating rules

1. Start by inspecting the repository shape:
   - `git status --short`
   - Gradle/Maven modules and available test/build tasks
   - Spring profiles, `application.yml/properties`, Docker/Compose files, CI files, migrations, security config, controllers, filters, auth services, repositories, and integration tests
2. Do not expose secrets in your answer. If you find one, report the variable/file/line and redact the value.
3. Prefer semantic Java/Kotlin inspection and framework reasoning. Use text search for broad discovery, but do not rely only on regex if Spring Security configuration or JWT lifecycle structure matters.
4. If an issue has an obvious safe fix, implement it.
5. If a fix requires infrastructure or owner decisions, add a narrow TODO only where useful and explain the decision needed.
6. After changes, run the safest relevant verification commands available in the repo. If a command cannot run because of missing tools, environment, database, or credentials, explain exactly what blocked it.

## Required visual report

In your final answer, include this vertical Guardrail Trail. Use:

- `🟢 PASS` when the repo already met the requirement or your fix made it pass.
- `🟡 REVIEW` when the code works but has security or architecture risk that needs owner judgment.
- `🔴 FAIL/FIXED` when you found a serious issue; mark it fixed if you corrected it.
- `⚪ NOT DETECTED` when the stage does not exist in this API.

```text
Guardrail Trail
🟢/🟡/🔴/⚪ 01. Sanitization and local-environment leaks
🟢/🟡/🔴/⚪ 02. Secrets and configuration safety
🟢/🟡/🔴/⚪ 03. Authentication entry points
🟢/🟡/🔴/⚪ 04. JWT issuance, claims, and signing
🟢/🟡/🔴/⚪ 05. JWT verification and protected routes
🟢/🟡/🔴/⚪ 06. Refresh token rotation, replay, logout, and revocation
🟢/🟡/🔴/⚪ 07. Authorization, roles, scopes, and ownership checks
🟢/🟡/🔴/⚪ 08. Rate limiting and abuse protection
🟢/🟡/🔴/⚪ 09. Network/client resilience for outbound calls
🟢/🟡/🔴/⚪ 10. Input validation, data access, and error handling
🟢/🟡/🔴/⚪ 11. Privacy-safe logging, observability, and Actuator exposure
🟢/🟡/🔴/⚪ 12. Tests, CI, and dependency hygiene
```

## Detailed checklist

### 1. Sanitization and local-environment leaks

Check Java, Kotlin, Gradle/Maven, resources, config, scripts, docs, Docker, and CI for:

- Absolute user paths such as `/Users/...`, `/home/...`, `C:\Users\...`, `/var/folders/...`, local SDK/JDK paths, temp paths, or machine-specific build paths.
- Local-only URLs, debug file paths, embedded certificates, keystores, or generated artifacts.
- Committed `.env`, local properties, private keys, signing material, DB dumps, or IDE files.

Fix by moving local/environment-specific values to ignored local config, environment variables, Spring config properties, Docker secrets, Kubernetes secrets, or CI secrets. Update `.gitignore` if needed.

### 2. Secrets and configuration safety

Find and fix hardcoded:

- JWT signing secrets, OAuth client secrets, database passwords, SMTP credentials, cloud keys, API tokens, webhook secrets, private keys, encryption keys, default admin passwords.
- Suspicious names: `secret`, `token`, `password`, `jwt`, `bearer`, `privateKey`, `clientSecret`, `signingKey`, `sk_live`, `AKIA`, `BEGIN PRIVATE KEY`.

Do not print the values. Replace with typed configuration loaded from the environment or secret manager. Fail startup if a required production secret is missing or uses a known insecure default. If a real credential was committed, say it must be rotated outside Codex.

### 3. Authentication entry points

Inspect login, registration, refresh, logout, password reset, email verification, OAuth callbacks, and API-key auth:

- Passwords are hashed with a strong adaptive password encoder such as BCrypt, Argon2, PBKDF2, or scrypt. No reversible encryption or raw hashing.
- Auth endpoints use generic error messages that do not leak whether a user exists.
- Brute-force controls exist for login, refresh, password reset, and verification-code endpoints.
- Sessions/tokens are invalidated on logout and sensitive account changes.
- Public endpoints are intentionally public; everything else is denied by default.

### 4. JWT issuance, claims, and signing

If this API issues JWTs, verify:

- No `alg=none`, no `Algorithm.none()`, no accepting unsigned tokens, and no algorithm confusion between HMAC and RSA/EC keys.
- Access tokens are short-lived. Prefer minutes, not days.
- Refresh tokens are separate from access tokens and are not accepted as access tokens.
- Every access token has `iss`, `aud`, `sub`, `exp`, `nbf` where appropriate, `iat`, `jti`, and an explicit purpose/type such as `typ`, `token_use`, or equivalent.
- Token type/purpose is checked to prevent access/refresh token confusion. Do not rely on `typ` alone; verify signature, issuer, audience, expiry, and intended usage.
- Signing keys/secrets come from secure configuration, not source code defaults.
- Long-lived access tokens require a revocation strategy; otherwise shorten them.
- Key rotation or JWKS strategy is documented and implemented where applicable.

### 5. JWT verification and protected routes

If this API consumes JWTs, verify:

- Spring Security protects routes through `SecurityFilterChain`, method security, or equivalent.
- Prefer Spring Security OAuth2 Resource Server with `issuer-uri` or `jwk-set-uri` where possible.
- JWT verification validates signature, `exp`, `nbf`, `iss`, and expected `aud`.
- Custom `JwtDecoder`/validators include issuer and audience validation.
- The API maps scopes/roles consistently and does not trust client-provided roles outside signed claims.
- Route protection defaults to authenticated/denied and explicitly permits only intended public endpoints.
- Tests prove protected endpoints reject missing, expired, wrong-audience, wrong-issuer, wrong-type, and tampered tokens.

### 6. Refresh token rotation, replay, logout, and revocation

If refresh tokens exist, verify:

- Refresh tokens are stored server-side as hashes, not plaintext.
- Refresh rotation occurs on every use where possible.
- Reuse/replay of an old refresh token is detected and invalidates the token family/session.
- Refresh tokens have expiry and idle timeout.
- Logout revokes the active session/token family and clears cookies if cookies are used.
- Account disable/password change/security event revokes active sessions.
- Refresh endpoint is rate-limited and audited.

### 7. Authorization, roles, scopes, and ownership checks

Check controllers/services/repositories for:

- Object-level authorization: users cannot access records only by guessing IDs.
- Role/scope checks are centralized enough to be auditable.
- Admin endpoints are protected by role/scope and not only by URL obscurity.
- Multi-tenant boundaries are enforced in queries and service methods.
- No mass assignment of sensitive fields such as `role`, `isAdmin`, `ownerId`, `tenantId`, `status`, or `balance`.

### 8. Rate limiting and abuse protection

Verify rate limiting or abuse controls for:

- Login, refresh, signup, password reset, OTP/verification, invite, upload, search, expensive AI/LLM endpoints, and payment-like actions.
- Per-IP and per-account/user dimensions where appropriate.
- `429` responses include safe retry behavior.
- Backoff, lockout, or progressive delay does not create easy account enumeration.

If no rate limiting library exists, recommend one such as Bucket4j, Resilience4j RateLimiter, gateway-level rate limits, or provider-level controls. Implement a minimal local limiter only if it fits the project and will not give a false sense of distributed safety.

### 9. Network/client resilience for outbound calls

Inspect `WebClient`, `RestTemplate`, Java/Kotlin HTTP clients, SDK clients, database clients, mail clients, and messaging clients:

- Set connect/read/response timeouts.
- Use retries only where safe, with exponential backoff and jitter.
- Add circuit breakers/time limiters for critical upstreams when the project already uses a resilience library.
- Avoid retrying non-idempotent operations unless an idempotency key or safe protocol exists.
- Handle 401/403/429/5xx/timeouts distinctly.
- Do not log Authorization headers, request bodies with secrets, or PII.

### 10. Input validation, data access, and error handling

Check:

- Request DTOs use Bean Validation or equivalent.
- Controllers validate pagination/sort/filter parameters.
- SQL/NoSQL access uses repositories, parameterized queries, or safe query builders. No string-concatenated user input.
- File uploads validate type, size, path traversal, and storage location.
- Errors use a consistent exception handler and do not expose stack traces, SQL errors, secrets, or internal paths.
- API responses use safe DTOs instead of exposing entities with sensitive fields.

### 11. Privacy-safe logging, observability, and Actuator exposure

Search logs, metrics, traces, and error handlers for:

- Tokens, Authorization headers, passwords, private keys, verification codes, PII, raw request/response bodies, stack traces returned to clients.
- Unsafe `println`, `System.out`, `logger.debug`, request logging filters, or HTTP client wire logs.

Verify:

- Actuator endpoints are not publicly exposed except health/info as intended.
- Production logging level is safe.
- Correlation IDs/request IDs exist if the app needs traceability.
- Security events are auditable without leaking secrets.

### 12. Tests, CI, and dependency hygiene

Add or update tests where practical:

- Security config tests for public/protected endpoints.
- JWT tests for expired, tampered, wrong issuer, wrong audience, wrong type, missing subject, missing jti, and replayed refresh token.
- Auth endpoint rate-limit tests if rate limiting exists in-process.
- Controller validation tests.
- Integration tests for unauthorized/forbidden/error responses.

Check dependency hygiene:

- Run Gradle/Maven tests.
- Run configured vulnerability/dependency checks if present.
- Avoid deprecated Spring Security patterns such as `WebSecurityConfigurerAdapter`.
- Do not introduce a new framework if a small Spring-native fix is enough.

## Final answer format

End with:

1. The Guardrail Trail.
2. Files changed.
3. Issues fixed.
4. Issues needing owner/security/infrastructure decision.
5. Commands/tests run and results.
6. Copy-paste remediation prompts for any remaining issues. Each prompt must include the real file path, line or symbol, observed problem, expected secure behavior, and constraints.

## Reference standards to use

- OWASP ASVS: https://owasp.org/www-project-application-security-verification-standard/
- OWASP API Security Top 10: https://owasp.org/API-Security/editions/2023/en/0x00-header/
- Spring Boot OAuth2 resource server: https://docs.spring.io/spring-boot/reference/security/oauth2.html
- Spring Security JWT resource server: https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html
