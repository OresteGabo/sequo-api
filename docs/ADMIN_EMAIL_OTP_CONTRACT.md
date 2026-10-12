# SEQUO Admin Email One-Time Code Contract

This contract defines the fallback admin web login flow where staff receive a short one-time code by email when phone approval is unavailable.

Phone-approved login remains the preferred flow for shared or work computers because it uses an already-authenticated SEQUO mobile app. Email one-time code is a fallback for operational continuity, not the primary staff sign-in experience.

## Security Goals

- Provide a safe fallback when phone approval cannot be used.
- Avoid revealing whether an email exists or belongs to an admin account.
- Require active admin/staff eligibility before any token is issued.
- Keep one-time codes short enough for humans but protected by hashing, expiry, one-time use, rate limits, and audit events.
- Never log codes, token values, token hashes, OAuth tokens, device tokens, payment tokens, or backend secrets.
- Never expose raw backend errors or internal debug details in admin UI.

## Eligible Roles

The initial allowed roles are:

- `ADMIN`
- `SUPER_ADMIN`

Other staff roles must not receive admin web tokens through this flow unless product policy explicitly adds them later.

## Code Requirements

- Generate a secure numeric or alphanumeric code.
- MVP recommendation: 6 characters or 6 digits.
- Expire in 5-10 minutes. MVP recommendation: 5 minutes.
- Allow exactly one successful verification.
- Store only a hash/server-side verifier.
- The email delivery layer may receive the plaintext code only at send time.
- Do not log the plaintext code.

## Endpoint: Request Email Code

`POST /api/admin-auth/email-codes`

Purpose: the admin website asks the backend to send an email one-time code if the account is eligible.

Authentication: none.

Request body:

```json
{
  "email": "admin@sequo.example"
}
```

Success response:

```json
{
  "status": "SENT_IF_ELIGIBLE",
  "expiresInSeconds": 300,
  "retryAfterSeconds": 60
}
```

Rules:

- Normalize email using the same policy as normal auth users.
- Return the same generic success shape whether the email is unknown, inactive, not eligible, or eligible.
- Send an email only when the account exists, is active, and has an allowed admin role.
- Apply rate limiting by normalized email and request origin/IP.
- A rate-limited response may return `429` with `Retry-After`.
- Audit the request without storing the plaintext code.
- Do not expose whether a code was actually sent.

## Endpoint: Verify Email Code

`POST /api/admin-auth/email-codes/verify`

Purpose: the admin website exchanges a valid email one-time code for normal auth tokens.

Authentication: none for the endpoint itself. Possession of the normalized email plus correct unexpired code is required.

Request body:

```json
{
  "email": "admin@sequo.example",
  "code": "ZXH-NVB",
  "deviceId": "admin-browser-01"
}
```

`deviceId` is optional. When provided, it should be stored as the admin web session device identifier with app source `SEQUO_ADMIN`. When omitted, the backend may create a safe request-scoped admin browser identifier.

Success response:

```json
{
  "accessToken": "opaque-to-ui",
  "refreshToken": "opaque-to-ui",
  "expiresIn": 900,
  "user": {
    "id": "user-id",
    "email": "admin@sequo.example",
    "displayName": "SEQUO Admin",
    "roles": ["ADMIN"]
  }
}
```

Failure rules:

- Unknown email, non-admin email, wrong code, expired code, reused code, and inactive account must return safe generic failure messages.
- Do not reveal which condition failed.
- Do not return token material unless verification succeeds.
- Do not display raw exception messages in admin UI.

Verification rules:

- The code must match the stored hash/server-side verifier.
- The code must not be expired.
- The code must not already be consumed.
- The user must still be active and eligible for admin web access at verification time.
- On success, mark the code consumed.
- On repeated failures, apply rate limiting by normalized email and request origin/IP.
- Audit success and failure without secrets.

## Rate Limiting

Protect at minimum:

- `POST /api/admin-auth/email-codes` by normalized email and request origin/IP.
- `POST /api/admin-auth/email-codes/verify` by normalized email and request origin/IP.

Rate-limited responses should use `429` and `Retry-After` where possible, with safe messages.

## Audit Events

Audit these events without secrets:

- email code requested
- email code sent to an eligible account
- request skipped because account is unknown or ineligible
- email code verified
- failed verification attempt
- expired code attempt
- reused code attempt
- rate limit hit where safe

Safe metadata:

- normalized email hash or safe account id
- event timestamp
- high-level client label
- status

Never audit:

- access token
- refresh token
- plaintext one-time code
- code hash if avoidable
- OAuth tokens
- device push tokens
- payment tokens

## Admin Web UX Requirements

The admin website should show:

- email input
- request email code action
- code input
- countdown or expiry text
- resend action only after the safe retry window
- safe status messages for sent, expired, invalid, rate-limited, and failed

The UI must not show endpoint names, API base URLs, token wording, debug errors, or stack traces.

## Relationship To Phone Approval

- Phone-approved login should be shown first where available.
- Email one-time code should be offered as a fallback path.
- Email fallback should not weaken role checks: only eligible admin/staff accounts may receive usable admin web tokens.
