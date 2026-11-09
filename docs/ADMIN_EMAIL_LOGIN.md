# Admin email-code sign-in deployment

## Implemented behavior

The existing admin code flow now has an opt-in Resend HTTPS transport. No email SDK/dependency or database migration was added. The sender uses a fixed HTTPS endpoint, disallows redirects, sets 5-second connect and 10-second request timeouts, checks provider acknowledgement, and supplies an opaque stable per-code idempotency key. Codes, credentials and provider response bodies are not logged or returned to clients. A bilingual plain-text email includes the code, expiry and unsolicited-request warning.

When delivery is disabled, all addresses receive the same HTTP 503 `admin_email_unavailable`. With delivery enabled, unknown, inactive and non-admin accounts keep the generic request response and receive no email. Provider rejection records a sanitized audit event, saves no new usable code and keeps the same generic response to avoid disclosing account eligibility. Only hashes are stored. Verification checks the current active account and admin role, expiry and code, and locks the code row so concurrent requests cannot consume it twice. Existing device-bound sessions, rate limits and role checks remain.

The public admin frontend now defaults to https://api.sequoservice.com in production instead of the caller’s localhost:8080. Explicit VITE_SEQUO_API_BASE_URL still overrides the default; local development retains localhost. The frontend displays a safe unavailable message for HTTP 503. No provider key belongs in any VITE variable or browser bundle.

## Required external setup

1. Create the Resend account on https://resend.com/signup. The owner must enter their password and accept the account terms themselves. Start with the free plan; no paid subscription is needed for initial testing.
2. Verify a sending subdomain that SEQUO controls, such as auth.sequoservice.com. Add only the exact DNS records shown in the Resend dashboard. Preserve existing website records and mailbox MX records. Domain ownership and DNS access must be confirmed before edits.
3. Create a sending-only key restricted to that domain. Save it directly in the API host’s protected environment/secret store. Do not paste it into chat, commit it, or expose it through Vercel frontend variables.
4. Configure the API host:

```dotenv
ADMIN_EMAIL_ENABLED=true
RESEND_API_KEY=<secure sending-only secret>
ADMIN_EMAIL_FROM=no-reply@auth.sequoservice.com
```

The example sender is usable only after that sending domain is verified. Docker Compose forwards these values. Enabled delivery with a missing/malformed key or invalid sender fails startup rather than falling back to a fake sender. Disabled delivery makes no outbound request. Existing local-fixture launchers stay disabled and retain their separate passwordless test entry.

5. Restore/verify the actual OVH API host, TLS, readiness and CORS for https://sequo-admin.vercel.app (and any separately confirmed admin custom domain). Do not point the hosted admin at the local fixture API or expose that fixture through a tunnel. Keep existing production deployment/payment approval gates unchanged.
6. Inspect the authorized production account for `orestegabo@icloud.com`. The user explicitly requested testing with that address. It must be an active account with ADMIN; no SUPER_ADMIN grant is required. Use existing authorized account-management tooling or an operator-reviewed transaction on the confirmed database. Do not ship an automatic migration, public signup rule or frontend allowlist that grants privileges for an email address. Preserve unrelated roles/accounts and do not reactivate a deliberately disabled account without review.
7. Deploy the verified API revision through the existing release process. Request exactly one login email from the hosted admin and have the owner verify receipt and enter the code. Provider acceptance alone does not establish inbox delivery. Confirm /api/auth/me shows the intended admin identity and correct scope, then test sign-out/replay rejection. No live email has been sent during automated tests.

## Evidence and current limits

2026-11-09: 45 focused tests passed, including actual disabled sender, provider payload/acknowledgement, no redirects, failed-send non-persistence, expired/reused codes, current admin authorization, concurrency (one success and one rejection), mobile approval and local-entry boundaries. `bootJar` passed. Admin production build passed; its bundle contains the canonical HTTPS API and no localhost:8080 fallback.

At inspection time the public admin frontend loaded, but its old production bundle targeted localhost:8080. https://api.sequoservice.com/actuator/health timed out. The owner supplied ubuntu@vps-d4bc6ae7.vps.ovh.net, IP 54.37.12.31 and the existing ~/.ssh/sequo_api_deploy connection. Both the API domain and VPS hostname resolve to that IP. Strict-host-key, batch SSH attempts through the hostname and explicit IPv4 failed (no route / timeout); no server changes were made. The user was asked to check VPS power/firewall status in OVH. Initially no Resend account/key or verified sending domain was available. The user subsequently completed signup; auth.sequoservice.com was added in Ireland and is awaiting DNS verification. Exact displayed records and pending DNS authorization are in ADMIN_EMAIL_DNS.md. No provider key is configured yet. The prior GitHub API build never started due to an account billing lock; hosting gates were not bypassed. These are separate blockers to live delivery and account provisioning, not passing test results.

Primary provider references (checked 2026-11-09):

- https://resend.com/pricing
- https://github.com/resend/resend-openapi/blob/main/resend.yaml
- https://github.com/resend/resend-skills/blob/main/skills/resend/references/domains.md

Do not mark hosted sign-in complete until inbox receipt and authenticated hosted access are verified.
