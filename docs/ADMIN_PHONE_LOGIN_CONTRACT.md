# SEQUO Admin Phone-Approved Login Contract

This contract defines the admin web login flow where a staff member starts login on the SEQUO Admin website and approves it from an already-authenticated SEQUO mobile app.

The flow is designed for shared or work computers where staff may not want to sign in with personal Google or Apple accounts.

## Security Goals

- Use the already-authenticated phone as the trusted approval device.
- Avoid passwords for the admin web login flow.
- Avoid requiring personal Google or Apple login on office computers.
- Require admin/staff authorization before admin web tokens are issued.
- Keep codes short enough for humans but protected by expiry, one-time use, hashing, and rate limiting.
- Never expose tokens, token hashes, raw debug errors, or internal endpoint names in staff-facing UI.

## Roles Allowed To Approve

The initial allowed roles are:

- `ADMIN`
- `SUPER_ADMIN`

`SUPPORT_AGENT` may be added later only if product policy explicitly allows support staff to access SEQUO Admin web. Until then, support access should remain read-only where already implemented by existing backend authorization policies.

## Status Values

Admin login requests use this lifecycle:

| Status | Meaning |
| --- | --- |
| `PENDING` | Request was created and can still be approved or rejected. |
| `APPROVED` | Mobile user approved the request; web may exchange with the matching code. |
| `REJECTED` | Mobile user rejected the request. |
| `EXPIRED` | Request expired before approval/exchange. |
| `CONSUMED` | Request was exchanged successfully and cannot be reused. |

## Code Format

The display code should be human-friendly:

- 6 characters from a safe uppercase alphabet.
- Suggested display format: `ABC-DEF`.
- Exclude confusing characters where practical: `O`, `0`, `I`, `1`, `L`.
- Store a server-side verifier or hash, not plaintext.
- Expire in 2-5 minutes. MVP recommendation: 3 minutes.
- Allow exactly one successful exchange.

## Endpoint: Start Login Request

`POST /api/admin-auth/login-requests`

Purpose: the admin website starts a phone-approved login request.

Authentication: none.

Request body:

```json
{
  "email": "admin@sequo.example",
  "deviceLabel": "Chrome on MacBook Pro",
  "browserInfo": "Chrome 141, macOS"
}
```

Fields:

| Field | Required | Notes |
| --- | --- | --- |
| `email` | yes | Normalize with the same email policy as auth users. |
| `deviceLabel` | no | Short staff-readable label. Do not trust it for security. |
| `browserInfo` | no | Optional safe context for mobile approval display. |

Success response:

```json
{
  "requestId": "018f4b2a-6c55-7e2d-9f40-7a9f82c17411",
  "code": "ZXH-NVB",
  "expiresAt": "2026-10-12T09:15:00Z",
  "pollingIntervalSeconds": 2
}
```

Safe failure behavior:

- Invalid email returns a generic invalid request message.
- Repeated requests are rate limited.
- The response must not reveal whether the email belongs to an admin account unless product policy explicitly allows that for internal-only deployments.
- Do not log the display code.

## Endpoint: Poll Login Request

`GET /api/admin-auth/login-requests/{requestId}`

Purpose: the admin website polls safe request status.

Authentication: none.

Success response:

```json
{
  "status": "PENDING",
  "expiresAt": "2026-10-12T09:15:00Z",
  "approvedAt": null,
  "rejectedAt": null
}
```

Rules:

- Return status only, not tokens or user details.
- If the request is past expiry and not approved/consumed, return or mark `EXPIRED`.
- Unknown request id should return a safe 404.
- No raw backend errors.

## Endpoint: List Pending Requests On Mobile

`GET /api/admin-auth/mobile/login-requests`

Purpose: an authenticated mobile app lists pending admin web login requests for the current account.

Authentication: `Authorization: Bearer <accessToken>`.

Success response:

```json
[
  {
    "requestId": "018f4b2a-6c55-7e2d-9f40-7a9f82c17411",
    "code": "ZXH-NVB",
    "deviceLabel": "Chrome on MacBook Pro",
    "browserInfo": "Chrome 141, macOS",
    "requestedAt": "2026-10-12T09:12:00Z",
    "expiresAt": "2026-10-12T09:15:00Z"
  }
]
```

Rules:

- Only return requests for the authenticated user's normalized email/account.
- Only return requests if the user is eligible to approve admin web login.
- Expired requests should not appear as actionable.
- Do not return token material, code hashes, IP addresses, or internal metadata.

## Endpoint: Approve Request From Mobile

`POST /api/admin-auth/mobile/login-requests/{requestId}/approve`

Purpose: authenticated mobile user approves the matching admin website request.

Authentication: `Authorization: Bearer <accessToken>`.

Request body:

```json
{}
```

Optional future body:

```json
{
  "localBiometricVerified": true
}
```

Success response:

```json
{
  "status": "APPROVED",
  "approvedAt": "2026-10-12T09:13:10Z"
}
```

Rules:

- Request must be `PENDING` and not expired.
- Approving user must be active and have an allowed admin role.
- Approving user's email/identity must match the requested email unless `SUPER_ADMIN` override is explicitly implemented and audited.
- No token is returned by this endpoint.
- Approval must be audited without tokens or raw code hashes.

## Endpoint: Reject Request From Mobile

`POST /api/admin-auth/mobile/login-requests/{requestId}/reject`

Purpose: authenticated mobile user rejects the request.

Authentication: `Authorization: Bearer <accessToken>`.

Request body:

```json
{
  "reason": "not_me"
}
```

`reason` is optional and must be sanitized if accepted.

Success response:

```json
{
  "status": "REJECTED",
  "rejectedAt": "2026-10-12T09:13:10Z"
}
```

Rules:

- Request must be `PENDING` and not expired.
- Rejection prevents exchange.
- No token is returned.
- Do not expose internal details.

## Endpoint: Exchange Approved Request For Tokens

`POST /api/admin-auth/login-requests/{requestId}/exchange`

Purpose: after mobile approval, the admin web exchanges the approved request and display code for normal auth tokens.

Authentication: none for the endpoint itself; possession of `requestId` plus the correct code is required, and the request must already be approved by an eligible authenticated mobile user.

Request body:

```json
{
  "code": "ZXH-NVB",
  "deviceId": "admin-browser-01"
}
```

`deviceId` is optional. When it is provided, it is stored as the admin web session device identifier with app source `SEQUO_ADMIN`. When it is omitted, the backend creates a safe request-scoped admin browser identifier.

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

Rules:

- Request must be `APPROVED`.
- Request must not be expired.
- Request must not be consumed.
- Code must match the stored verifier/hash.
- The display code is stored encrypted for mobile display and verified through the server-side hash; plaintext code is not logged.
- The approved user must still be active and eligible for admin web access at exchange time.
- On success, mark request `CONSUMED`.
- The exchange is one-time only.
- Tokens use the existing backend token/session infrastructure.
- The web UI must never display token values.

## Rate Limiting

Protect at minimum:

- `POST /api/admin-auth/login-requests` by normalized email and request origin.
- `POST /api/admin-auth/login-requests/{requestId}/exchange` by request id and request origin.
- mobile approve/reject endpoints by authenticated user id.

Rate-limited responses should use `429` and `Retry-After` where possible, with safe messages.

## Audit Events

Audit these events without secrets:

- login request created
- login request approved
- login request rejected
- login request expired
- login request exchanged
- failed exchange attempt
- rate limit hit where safe

Safe metadata:

- request id
- normalized email hash or safe account id
- approved user id where known
- event timestamp
- high-level client label
- status

Never audit:

- access token
- refresh token
- raw code
- code hash if avoidable
- OAuth tokens
- device push tokens

## Admin Web UX Requirements

The admin website should show:

- email input
- “Request login code” action
- large display code
- countdown
- instruction to approve from the SEQUO app
- safe status messages for pending, approved, rejected, expired, and failed
- retry/new email action after expiry or rejection

The UI must not show endpoint names, API base URLs, token wording, debug errors, or stack traces.

## Mobile UX Requirements

Eligible mobile apps should show:

- pending admin login requests
- code to compare with the admin website
- device/browser label if provided
- expiry time
- approve/reject actions
- warning: approve only if the code matches the admin website

Normal users should not be distracted by this flow.
