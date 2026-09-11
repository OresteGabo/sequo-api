# External Launch Setup

This file tracks launch work that happens outside this codebase: provider accounts, dashboards, credentials, callback URLs, domains, billing, and deployment secrets.

Last audited: 2026-09-11.

## How To Use This Checklist

- Put real values only in the deployment secret manager or local `.env` files that are ignored by git.
- Do not paste secrets into this file.
- Create separate dev/staging/production credentials where the provider allows it.
- Record the non-secret identifiers here after setup, such as project names, account names, callback paths, and whether approval is complete.

## P0 - Needed Before A Paid MVP Launch

### Production Hosting And Database

- [ ] Choose the production hosting target for the Spring Boot API.
- [ ] Create a production PostgreSQL database.
- [ ] Create separate database credentials for production.
- [ ] Configure a production secret manager.
- [ ] Configure production logs/metrics/alerts.
- [ ] Decide the public API domain, for example `api.sequoservice.com`.
- [ ] Configure TLS/HTTPS for the API domain.
- [ ] Configure these deployment variables:

| Variable | Needed for | Notes |
| --- | --- | --- |
| `SPRING_PROFILES_ACTIVE` | Runtime profile | Use `docker` or another production-like profile. |
| `PORT` | API HTTP port | Usually provided by the hosting platform. |
| `SPRING_DATASOURCE_URL` | PostgreSQL | Must not point to H2 in production. |
| `SPRING_DATASOURCE_USERNAME` | PostgreSQL | Use a least-privilege DB user. |
| `SPRING_DATASOURCE_PASSWORD` | PostgreSQL | Store only in secret manager. |
| `SPRING_JPA_HIBERNATE_DDL_AUTO` | Schema safety | Keep as `validate`; Flyway owns migrations. |
| `JWT_SECRET` | API auth | Generate a strong random secret per environment. |
| `NOTIFICATION_TOKEN_ENCRYPTION_SECRET` | FCM token encryption at rest | Generate a strong random secret per environment. |
| `CORS_ALLOWED_ORIGINS` | Browser/mobile app API access | Must be explicit HTTPS origins in production. |

### Yas Togo Payments

- [ ] Contact Yas Togo Business / Mixx by Yas for merchant payment API access.
- [ ] Create or activate the Sequo merchant account.
- [ ] Request sandbox/test credentials.
- [ ] Request production credentials.
- [ ] Ask for API documentation, callback format, signature rules, IP allowlisting rules, and reconciliation/status-check endpoint.
- [ ] Provide the production callback URL after the API domain is known.
- [ ] Configure these variables:

| Variable | Needed for | Notes |
| --- | --- | --- |
| `YAS_TOGO_API_KEY` | Payment/refund adapter | Current code has the env key but still needs the real adapter. |
| `YAS_TOGO_WEBHOOK_SECRET` | Signed webhook intake | Current webhook persistence expects this. |

Useful links:

- Yas Togo Business help: https://yas.tg/business/aide/

### Moov Africa Togo / Flooz Payments

- [ ] Contact Moov Africa Togo to become a Moov Money Flooz merchant.
- [ ] Prepare company documents likely required by Moov: CFE/NIF or economic operator card, responsible person's ID, and merchant contract details.
- [ ] Request API documentation, sandbox/test credentials, production credentials, callback rules, static-IP rules, and reconciliation/status-check endpoint.
- [ ] Provide the production callback URL after the API domain is known.
- [ ] Configure these variables:

| Variable | Needed for | Notes |
| --- | --- | --- |
| `MOOV_AFRICA_API_KEY` | Payment/refund adapter | Current code has the env key but still needs the real adapter. |
| `MOOV_AFRICA_WEBHOOK_SECRET` | Signed webhook intake | Current webhook persistence expects this. |

Useful links:

- Moov Africa Togo merchant info: https://moov-africa.tg/moov-money/marchand-moov-money-flooz/

### Payment Callback URLs To Reserve

Use the final production API domain when creating provider callbacks.

| Provider | Callback URL | Current backend state |
| --- | --- | --- |
| Yas Togo | `https://<api-domain>/api/payments/webhooks/yas-togo` | Signed webhook intake exists; order reconciliation still needs code. |
| Moov Africa | `https://<api-domain>/api/payments/webhooks/moov-africa` | Signed webhook intake exists; order reconciliation still needs code. |

### Firebase Cloud Messaging

- [ ] Create or select the Firebase project for Sequo.
- [ ] Add the Android customer app to Firebase.
- [ ] Add the Android merchant/seller app to Firebase if it is a separate package.
- [ ] Add the Android courier app to Firebase if it is a separate package.
- [ ] Add iOS apps later if/when iOS launch is in scope.
- [ ] Download each client `google-services.json` or equivalent mobile config for the mobile apps, not the backend repo.
- [ ] Create/choose a service account for server-side FCM sending.
- [ ] Decide whether the backend will use Firebase Admin SDK credentials or FCM HTTP v1 access tokens.
- [ ] Configure production credentials only after the FCM sender adapter is implemented.

Likely future backend variables:

| Variable | Needed for | Notes |
| --- | --- | --- |
| `FIREBASE_PROJECT_ID` | FCM HTTP v1/Admin SDK | Not wired yet in code. |
| `FIREBASE_SERVICE_ACCOUNT_JSON` or secret-file mount | FCM Admin SDK | Not wired yet in code. Prefer secret manager/file mount, not plain text env if the host supports files. |
| `NOTIFICATION_PROVIDER_DELIVERY_WORKER_ENABLED` | Provider send retries | Worker exists; enable after real FCM/SMS senders exist. |

Useful links:

- Firebase console: https://console.firebase.google.com/
- FCM server environment docs: https://firebase.google.com/docs/cloud-messaging/server-environment
- Firebase service accounts docs: https://firebase.google.com/support/guides/service-accounts

### Google Sign-In

- [ ] Create/select a Google Cloud project.
- [ ] Configure the Google Auth Platform consent screen.
- [ ] Create OAuth client IDs for every app/platform that will request Google ID tokens.
- [ ] For Android clients, register the package name and SHA-1/SHA-256 signing certificate fingerprints.
- [ ] For web/admin clients, register authorized JavaScript origins and redirect URIs if a web OAuth flow is used.
- [ ] Configure this backend variable:

| Variable | Needed for | Notes |
| --- | --- | --- |
| `GOOGLE_CLIENT_ID` | Backend Google ID token audience check | Current backend verifies Google ID tokens against this client ID. If mobile apps have multiple client IDs, the backend may need a list instead of one value. |

Useful links:

- Google Cloud console: https://console.cloud.google.com/
- Google OAuth client setup: https://support.google.com/cloud/answer/15549257

## P1 - Needed If The MVP Promises These Features

### SMS Provider

- [ ] Choose the SMS provider for Togo.
- [ ] Confirm sender ID, pricing, delivery receipts, rate limits, and allowed message templates.
- [ ] Get sandbox/test credentials if available.
- [ ] Get production credentials.
- [ ] Confirm whether callback URLs are needed for delivery receipts.
- [ ] Configure credentials only after the SMS sender adapter is implemented.

Possible providers to evaluate:

- Twilio
- Infobip
- Africa's Talking
- Orange/telecom local aggregator
- A Togo-focused payment/SMS aggregator if Yas/Moov recommend one

Likely future backend variables:

| Variable | Needed for | Notes |
| --- | --- | --- |
| `SMS_PROVIDER` | Adapter selection | Not wired yet. |
| `SMS_API_KEY` | SMS sending | Not wired yet. |
| `SMS_SENDER_ID` | Branded sender | Depends on provider/country approval. |
| `SMS_WEBHOOK_SECRET` | Delivery receipt callbacks | Only if delivery receipts are implemented. |

### Apple Developer And Sign In With Apple

- [ ] Enroll in the Apple Developer Program if iOS or Sign in with Apple is in MVP scope.
- [ ] Create/register the iOS app identifier.
- [ ] Enable Sign in with Apple on the App ID.
- [ ] Create a Services ID for web/backend authentication if needed.
- [ ] Register domains and return URLs.
- [ ] Create a Sign in with Apple private key.
- [ ] Record Team ID and Key ID in the secret manager.
- [ ] Keep Apple auth disabled until backend JWT/JWKS validation is implemented.

Current backend status: `APPLE_CLIENT_ID` exists, but `AppleTokenVerifier` returns `null`, so Apple login is not usable yet.

Current/future variables:

| Variable | Needed for | Notes |
| --- | --- | --- |
| `APPLE_CLIENT_ID` | Apple token audience | Existing env key, but backend verifier is not implemented. |
| `APPLE_TEAM_ID` | Apple client secret generation | Not wired yet. |
| `APPLE_KEY_ID` | Apple client secret generation | Not wired yet. |
| `APPLE_PRIVATE_KEY` | Apple client secret generation | Not wired yet; store as a secret/file. |

Useful links:

- Apple Developer account: https://developer.apple.com/account/
- Configure Sign in with Apple: https://developer.apple.com/documentation/signinwithapple/configuring-your-environment-for-sign-in-with-apple

### Facebook Login

- [ ] Create a Meta developer account.
- [ ] Create a Meta app.
- [ ] Add Facebook Login if Facebook login is in MVP scope.
- [ ] Configure valid OAuth redirect URIs if using a web flow.
- [ ] Configure Android/iOS package identifiers and key hashes if using mobile SDKs.
- [ ] Complete any required app review for production permissions.
- [ ] Configure this backend variable:

| Variable | Needed for | Notes |
| --- | --- | --- |
| `FACEBOOK_APP_ID` | Facebook token/app validation | Current backend has only a basic Graph API lookup and should be hardened before launch. |
| `FACEBOOK_APP_SECRET` | App access token / debug-token validation | Not wired yet but likely needed for safer production validation. |

Useful links:

- Meta for Developers: https://developers.facebook.com/
- Facebook Login docs: https://developers.facebook.com/docs/facebook-login/

### Google Maps / Routing Provider

- [ ] Decide whether MVP uses Google Maps, another routing provider, or manual distance estimates.
- [ ] Create/enable a Google Maps Platform billing account if Google is selected.
- [ ] Enable only the APIs needed for routing/distance.
- [ ] Create restricted API keys per environment and per app/server use.
- [ ] Set usage quotas/budgets/alerts before production.
- [ ] Configure variables only after a real routing provider adapter is implemented.

Likely future backend variables:

| Variable | Needed for | Notes |
| --- | --- | --- |
| `ROUTING_PROVIDER` | Provider selection | Not wired yet. |
| `GOOGLE_MAPS_API_KEY` | Google routing/distance adapter | Not wired yet. Restrict by API and environment. |
| `GOOGLE_MAPS_DAILY_QUOTA` | Cost control | Code has quota concepts; env key not wired yet. |

Useful links:

- Google Maps Platform: https://mapsplatform.google.com/
- Google Maps API key security: https://developers.google.com/maps/api-security-best-practices

### Email Provider For Password Reset And Verification

- [ ] Choose email provider.
- [ ] Verify sending domain, for example `sequoservice.com`.
- [ ] Configure SPF, DKIM, and DMARC.
- [ ] Create transactional templates for password reset, email verification, receipts, and support alerts.
- [ ] Configure credentials only after email sender implementation exists.

Possible providers:

- Resend
- SendGrid
- Mailgun
- Amazon SES
- Brevo

Likely future backend variables:

| Variable | Needed for | Notes |
| --- | --- | --- |
| `EMAIL_PROVIDER` | Adapter selection | Not wired yet. |
| `EMAIL_API_KEY` or SMTP credentials | Email sending | Not wired yet. |
| `EMAIL_FROM_ADDRESS` | Transactional sender | Not wired yet. |
| `EMAIL_REPLY_TO` | Support routing | Not wired yet. |

Current backend status: password reset tokens are generated and hashed, but `NoopPasswordResetTokenNotifier` does not send them.

### Object Storage / CDN For Media And Proofs

- [ ] Choose object storage provider.
- [ ] Create private buckets for sensitive proof/KYC media.
- [ ] Create public or CDN-backed bucket/path for catalog images if needed.
- [ ] Define retention policy for proof photos and return evidence.
- [ ] Configure signed URL or upload policy.
- [ ] Configure credentials only after storage adapter implementation exists.

Possible providers:

- Google Cloud Storage
- AWS S3
- Cloudflare R2
- Firebase Storage

Likely future backend variables:

| Variable | Needed for | Notes |
| --- | --- | --- |
| `OBJECT_STORAGE_PROVIDER` | Adapter selection | Not wired yet. |
| `OBJECT_STORAGE_BUCKET` | Media storage | Not wired yet. |
| `OBJECT_STORAGE_REGION` | Provider config | Depends on provider. |
| `OBJECT_STORAGE_ACCESS_KEY_ID` | Provider auth | Not wired yet. |
| `OBJECT_STORAGE_SECRET_ACCESS_KEY` | Provider auth | Not wired yet. |
| `MEDIA_CDN_BASE_URL` | Public catalog media | Not wired yet. |

## P2 - Operational Setup Before Public Launch

### Production Scheduler Switches

These jobs are disabled by default. Enable the ones needed in the deployed environment after secrets/providers are ready.

| Variable | Purpose |
| --- | --- |
| `NOTIFICATION_OUTBOX_WORKER_ENABLED` | Process committed notification events into inbox/delivery plans. |
| `NOTIFICATION_OUTBOX_WORKER_FIXED_DELAY_MS` | Outbox worker interval. |
| `NOTIFICATION_OUTBOX_WORKER_LIMIT` | Outbox batch size. |
| `RELAY_DELAYED_PARCEL_SCHEDULER_ENABLED` | Scan relay parcels for delay/storage-fee workflow. |
| `RELAY_DELAYED_PARCEL_SCHEDULER_FIXED_DELAY_MS` | Relay scan interval. |
| `SETTLEMENT_ELIGIBILITY_SCHEDULER_ENABLED` | Promote eligible merchant payouts. |
| `SETTLEMENT_ELIGIBILITY_SCHEDULER_FIXED_DELAY_MS` | Settlement scan interval. |
| `REFRESH_SESSION_CLEANUP_ENABLED` | Clean expired refresh sessions. |
| `REFRESH_SESSION_CLEANUP_FIXED_DELAY_MS` | Refresh cleanup interval. |

Also verify any additional scheduler env variables added later for provider-delivery retry, mission expiry, and dispatch readiness scans.

### Domains And App Store Assets

- [ ] Own/control `sequoservice.com` or final production domain.
- [ ] Create DNS records for API, admin web, website, email, and provider callbacks.
- [ ] Prepare privacy policy URL.
- [ ] Prepare terms of service URL.
- [ ] Prepare support email and phone number.
- [ ] Prepare app icons, screenshots, package names, and store metadata for each mobile app.
- [ ] Decide separate package names/bundle IDs for customer, merchant, courier, and relay apps.

### Secret Rotation And Access Control

- [ ] Name a single owner for production secrets.
- [ ] Give least-privilege access to each collaborator.
- [ ] Document how to rotate `JWT_SECRET`.
- [ ] Document how to rotate wallet webhook secrets.
- [ ] Document how to revoke Firebase service account credentials.
- [ ] Document how to rotate Google Maps API keys.
- [ ] Store emergency provider contacts.

## Current Code-Defined Environment Variables

These names are already read by the backend configuration today.

| Variable | Current code status |
| --- | --- |
| `JWT_SECRET` | Required in production-like profiles. |
| `NOTIFICATION_TOKEN_ENCRYPTION_SECRET` | Required in production-like profiles. |
| `CORS_ALLOWED_ORIGINS` | Required in production-like profiles. |
| `GOOGLE_CLIENT_ID` | Used by Google ID token verifier. |
| `FACEBOOK_APP_ID` | Present, but Facebook verification is still basic. |
| `APPLE_CLIENT_ID` | Present, but Apple verifier is not implemented. |
| `YAS_TOGO_API_KEY` | Required by guardrails, adapter still pending. |
| `YAS_TOGO_WEBHOOK_SECRET` | Used by wallet webhook signature checks. |
| `MOOV_AFRICA_API_KEY` | Required by guardrails, adapter still pending. |
| `MOOV_AFRICA_WEBHOOK_SECRET` | Used by wallet webhook signature checks. |
| `SPRING_DATASOURCE_URL` | Used by Docker profile. |
| `SPRING_DATASOURCE_USERNAME` | Used by Docker profile. |
| `SPRING_DATASOURCE_PASSWORD` | Used by Docker profile. |
| `POSTGRES_DB` | Used by Docker Compose. |
| `POSTGRES_USER` | Used by Docker Compose. |
| `POSTGRES_PASSWORD` | Used by Docker Compose. |
| `SEQUO_POSTGRES_PORT` | Used by Docker Compose local port mapping. |
| `SEQUO_ALLOW_DEV_DEFAULTS` | Allows known dev placeholders only when intentionally set. |
| `H2_CONSOLE_ENABLED` | Local-only H2 console switch. |
| `NOTIFICATION_OUTBOX_WORKER_ENABLED` | Existing notification outbox worker switch. |
| `NOTIFICATION_OUTBOX_WORKER_FIXED_DELAY_MS` | Existing notification outbox worker interval. |
| `NOTIFICATION_OUTBOX_WORKER_LIMIT` | Existing notification outbox worker batch size. |
| `RELAY_DELAYED_PARCEL_SCHEDULER_ENABLED` | Existing relay delay scheduler switch. |
| `RELAY_DELAYED_PARCEL_SCHEDULER_FIXED_DELAY_MS` | Existing relay delay scheduler interval. |
| `SETTLEMENT_ELIGIBILITY_SCHEDULER_ENABLED` | Existing settlement scheduler switch. |
| `SETTLEMENT_ELIGIBILITY_SCHEDULER_FIXED_DELAY_MS` | Existing settlement scheduler interval. |
| `REFRESH_SESSION_CLEANUP_ENABLED` | Existing refresh cleanup scheduler switch. |
| `REFRESH_SESSION_CLEANUP_FIXED_DELAY_MS` | Existing refresh cleanup scheduler interval. |

## Values To Fill In Later

Keep this section secret-free. Use names/statuses, not raw credentials.

| Item | Dev | Staging | Production |
| --- | --- | --- | --- |
| Hosting provider/project |  |  |  |
| API domain |  |  |  |
| Database host/name |  |  |  |
| Secret manager location |  |  |  |
| Firebase project ID |  |  |  |
| Google OAuth client ID owner/project |  |  |  |
| Apple Developer Team ID |  |  |  |
| Facebook app ID owner/project |  |  |  |
| Yas merchant account status |  |  |  |
| Moov/Flooz merchant account status |  |  |  |
| SMS provider/account |  |  |  |
| Email provider/domain |  |  |  |
| Object storage bucket/project |  |  |  |
| Routing provider/API key project |  |  |  |
