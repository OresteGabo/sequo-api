# Admin email-code sign-in deployment

Owner guide to costs, quotas, billing behavior and sign-in: [RESEND_EMAIL_GUIDE.md](RESEND_EMAIL_GUIDE.md).

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

### Recovery after OVH renewal — 2026-11-09

The owner renewed the expired VPS. SSH and public HTTPS recovered. The existing service was at revision `587dc718084b629a492affb3ecc0fa77defcaf36` and Flyway V38. A private server-side `pg_dump` and protected environment/Compose backups were saved under `/home/ubuntu/sequo-backups/20261109-email/`; the previous image is `sequo-api:rollback-20261109`.

The tested bootJar from `6a06e3e2f3725955af7e026068ed5ba576ee0748` was built with release ID `sequo-admin-email-20261109-01`. Local and uploaded SHA-256 both matched `48a3a14f4fa62fbf25452551ad0cd798b072859a7329e6b35738e1a064266623`. A runtime image retained the previous image's Temurin runtime and unprivileged user, replacing only the application artifact. All 62 Flyway checksums validated; V39–V62 and Hibernate schema validation passed first on a private restored copy, then on production. No applied migration was edited or repaired.

The clone exposed placeholder values in the previous JWT, notification-encryption and database secrets. Strong replacements were generated privately on the VPS, with production guardrails retained. There were no encrypted device tokens or unexpired mobile admin-login requests requiring re-encryption. JWT rotation requires the eight previously active device sessions to sign in again. The new admin-code encryption secret is distinct. `.env` remains mode 0600. Existing database volumes, business records and disabled paid/provider integrations were preserved.

The live API runs `sequo-api:email-20261109`, with the checkout pinned to the exact tested source revision. Public health/readiness return HTTP 200/UP. CORS accepts `https://sequo-admin.vercel.app` and the existing verified `https://admin.sequoservice.com` site, rejects an unapproved origin with HTTP 403, and protected routes remain unavailable anonymously. The requested address did not previously exist; an explicit operator transaction created an ACTIVE account with ADMIN only. Private intent/completion records are retained in `/home/ubuntu/sequo-release-20261109/` rather than a reusable email-to-role rule.

The owner switched to the correct Cloudflare account. The three previously approved Resend DNS records were added and independently resolved publicly; website and Zoho mailbox records were preserved. Resend confirmed the domain as Verified. After production readiness passed, the temporary validation container, database, role and duplicate secret files were removed; the protected production backup remains.

The owner created the `SEQUO Admin` key with Sending access. The dashboard offered only All domains, so the key has account-wide sending scope, not a single-domain restriction and not Full access. It was saved through a protected local file and transferred directly into the protected VPS `.env` without printing its value. Email delivery is enabled with `no-reply@auth.sequoservice.com`. Only the API container was restarted; readiness returned UP.

One code was requested through the actual `https://admin.sequoservice.com` frontend for the explicitly authorized address. Resend reported Delivered for message `01a1c0c4-c41d-7d22-b244-78494d0554ac`. The backend persisted the accepted code hash with a five-minute expiry (request time 2026-11-09 14:44:42 UTC). The owner confirmed inbox receipt and supplied the received code, which was entered through the normal hosted email-verification form before expiry. The authenticated dashboard opened at `/#dashboard`, showing the Administrator workspace and successfully loaded server records. Database metadata confirms the code is CONSUMED and one active SEQUO_ADMIN device session exists for the requested account. No raw code or credential was committed or added to operational evidence. The temporary local key-transfer file was removed; the provider key remains in the protected production environment. The browser is left signed in for the owner to test.

Primary provider references (checked 2026-11-09):

- https://resend.com/pricing
- https://github.com/resend/resend-openapi/blob/main/resend.yaml
- https://github.com/resend/resend-skills/blob/main/skills/resend/references/domains.md

Do not mark hosted sign-in complete until inbox receipt and authenticated hosted access are verified.
