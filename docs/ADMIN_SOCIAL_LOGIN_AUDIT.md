# Admin Social Login Audit

Purpose: decide whether SEQUO Admin Web can safely offer social login as a secondary option.

## Current Backend Surface

| Area | Status |
| --- | --- |
| Social login route | Implemented as `POST /api/auth/social/{provider}`. There is no current `POST /api/auth/login/social` route. |
| Supported providers in controller | `GOOGLE`, `FACEBOOK`, `APPLE` are accepted by `AuthService.loginWithSocialToken`. |
| Google verification | Implemented with `GoogleIdTokenVerifierAdapter`; validates audience, issuer, expiry, subject, email presence, and verified email. |
| Facebook verification | Implemented through Facebook Graph API. Audience validation is only enforced when app access token and app id are configured. |
| Apple verification | Not mobile-ready. `AppleTokenVerifier` currently returns `null`; Apple login cannot succeed until identity-token validation against Apple public keys is implemented. |
| Current user route | `GET /api/auth/me` returns `roles`, `active`, `status`, provider, email, name, and avatar fields needed by Admin Web. |
| Session source | `DeviceRequest.appSource` supports `SEQUO_ADMIN`, so Admin Web can identify sessions separately from mobile apps. |

## Can Admin Web Use Social Login Directly?

Not as the primary path.

Admin Web may use existing social login only as a secondary, trusted-computer option, and only through the actual current route:

`POST /api/auth/social/{provider}`

After receiving backend tokens, Admin Web must immediately call `GET /api/auth/me` and require `ADMIN` or `SUPER_ADMIN`. If the user is not an active admin, the web app must clear tokens, revoke/forget the local session where possible, and show a safe generic access error.

The backend social login flow does not by itself restrict issuance to admin users. If a social identity is new, the backend can provision a customer account by default. Therefore Admin Web must not treat a successful social token exchange as admin authorization.

## Google Client ID Verification

Google is the only social provider currently ready enough for optional Admin Web use.

The backend verifies Google ID tokens against `sequo.auth.google.allowed-client-ids`. The current configuration supports separate environment variables for Android, iOS, and web client IDs across the SEQUO app family. Admin Web needs a web OAuth client ID included in that allow-list.

Required configuration shape:

- `GOOGLE_WEB_CLIENT_ID` for customer web/admin-compatible Google web audience if reused intentionally.
- A dedicated Admin Web Google client is recommended before production, for example `GOOGLE_WEB_ADMIN_CLIENT_ID`, then include it in `sequo.auth.google.allowed-client-ids`.
- Continue keeping all mobile app web client IDs that are legitimately used by native Google sign-in SDKs.

The web client ID is not a secret. OAuth client secrets must not be shipped to frontend code.

## Apple Client ID Verification

Apple is not ready for Admin Web login yet.

The backend has `sequo.auth.apple.client-id`, but `AppleTokenVerifier` is still a placeholder and always fails. Before Admin Web enables Apple login, the backend must:

- Validate Apple identity-token JWT signature with Apple's public keys.
- Verify issuer, audience, expiry, subject, and email claims where available.
- Support the correct Apple Services ID/client ID for web login.
- Add tests for valid, invalid, expired, wrong-audience, and missing-subject tokens.

## Facebook Notes

Facebook is technically wired, but it is not recommended for Admin Web MVP unless the deployment has a configured app id and app access token. Without both, audience validation can be skipped by the current verifier, which is not appropriate for staff admin login on shared computers.

## Roles And Admin Authorization

`GET /api/auth/me` returns `roles`, so Admin Web can enforce:

- `ADMIN`
- `SUPER_ADMIN`

`SUPPORT_AGENT` may be useful for read-only support operations, but current Admin Web login should continue requiring `ADMIN` or `SUPER_ADMIN` unless a specific support-only workspace is designed and audited.

## App Family And Session Separation

Admin Web should use `appSource: "SEQUO_ADMIN"` when exchanging social tokens. This keeps refresh-device records separate from customer, merchant, rider, and hub sessions and makes logout/session review clearer.

Admin Web should have its own OAuth web client before production. Shared web clients make configuration easier during development, but separate clients give cleaner audit trails and safer revocation if one app needs to be disabled.

## Shared Computer Risks

- The browser may already be signed into a personal Google/Apple account.
- A staff member may accidentally choose the wrong account.
- OAuth browser sessions can persist after the user leaves a shared computer.
- Social login can create or link a non-admin account unless role checks are enforced after login.
- If the admin web page stores refresh tokens in browser storage, staff must have a clear sign-out path.

## Recommendation

Keep phone approval as the primary Admin Web login because it proves access to an already-authenticated SEQUO mobile app and avoids requiring personal Google or Apple accounts on work computers.

Add social login only as a secondary option:

- Google: allowed after Admin Web verifies `ADMIN` or `SUPER_ADMIN` via `GET /api/auth/me`.
- Apple: hidden or disabled until backend verification is implemented.
- Facebook: hidden or disabled for MVP unless app audience validation is fully configured.

