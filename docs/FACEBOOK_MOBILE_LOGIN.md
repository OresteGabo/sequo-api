# Facebook Mobile Login Backend Notes

This backend supports Facebook Login for the native KMP app through:

```http
POST /api/auth/social/FACEBOOK
Content-Type: application/json

{
  "token": "<facebook-user-access-token-from-native-sdk>",
  "device": {
    "deviceId": "<stable-per-install-device-id>",
    "appSource": "SEQUO_APP",
    "fcmToken": "<optional-fcm-token>"
  }
}
```

The mobile app must send a Facebook user access token obtained from the native Facebook SDK. Do not send profile fields from the app as trusted identity data.

## Server Verification

`FacebookTokenVerifier` validates the token in two steps:

1. If `FACEBOOK_APP_ACCESS_TOKEN` is configured, call Graph API `debug_token` and require:
   - `is_valid == true`
   - `app_id == FACEBOOK_APP_ID`
   - `user_id` is present
2. Call Graph API `/me?fields=id,name,email,picture` and use the returned Facebook user id as the immutable provider subject.

Local development can omit `FACEBOOK_APP_ACCESS_TOKEN`; in that case the server falls back to `/me` profile lookup only and logs a warning. Production should configure the app access token so the backend rejects tokens minted for another Facebook app.

## Configuration

Set these environment variables outside source control:

```properties
FACEBOOK_APP_ID=<facebook-app-id>
FACEBOOK_APP_ACCESS_TOKEN=<app-id>|<app-secret-or-generated-app-token>
```

The app access token is a backend secret. Never place it in Android, iOS, KMP shared code, or public docs.

## Permissions

For Sequo login, request the smallest useful permission set from Facebook:

- `public_profile` for the stable Facebook user id and public name/profile fields.
- `email` only if Sequo needs the account email. Facebook may still omit email, so the backend and app must tolerate `email = null`.

Requesting permissions beyond `public_profile` and `email` normally requires Facebook Login Review. Do not request publish permissions during sign-in.

## Account Behavior

The backend links identities by `(provider = FACEBOOK, providerSubject = facebook id)`.

If Facebook returns an email and a Sequo user already exists with that normalized email, the backend attaches the Facebook identity to that existing user. If Facebook does not return email, the backend can still create or find the account by the Facebook provider subject.

## Future AI Guardrails

- This is a native mobile flow, not the Facebook JavaScript SDK flow.
- Do not implement web Login Button/XFBML code in this backend or in the KMP app.
- Do not trust client-sent `name`, `email`, or `picture`; always fetch them from Facebook after token verification.
- Keep `FACEBOOK_APP_ACCESS_TOKEN` server-only.
- Treat email as optional for Facebook accounts.
