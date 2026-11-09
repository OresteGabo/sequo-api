# SEQUO — Resend email guide

Last checked: 9 November 2026. Provider limits and pricing can change; official sources are linked below. No subscription or billing settings were changed while writing this guide.

## Costs and limits

Receiving a SEQUO login email in your normal mailbox has no per-message charge from SEQUO or Resend. SEQUO uses Resend to send it.

| Free transactional plan | Allowance |
| --- | --- |
| Subscription | $0 |
| Daily quota | 100 emails |
| Monthly quota | 3,000 emails |
| Daily reset | Midnight UTC: 01:00 in Paris in winter, 02:00 in summer |

Both limits apply. Quotas are shared across the Resend account; each recipient counts separately. Resends also use the allowance. Resend inbound messages count too, but inbound receiving was not enabled for SEQUO's sending subdomain.

On Free, excess sending is blocked rather than automatically billed. After the daily reset, sending can resume if monthly capacity remains. A monthly-limit block requires the monthly reset or an explicit upgrade.

Paid plans have a subscription. Additional usage charges require enabling transactional pay-as-you-go; it is off by default. Without it, sending stops at the monthly quota. No paid Resend subscription or overages were activated during SEQUO setup.

Sources: [official account quotas and limits](https://resend.com/docs/knowledge-base/account-quotas-and-limits), [official pricing and billing FAQ](https://resend.com/pricing).

## What a quota block means for SEQUO

A blocked request cannot deliver a fresh admin code. SEQUO saves a new usable code hash only after Resend accepts the message. Provider failures produce a sanitized `EMAIL_OTP_DELIVERY_FAILED` audit event; provider response bodies, keys and codes are not written to application logs.

The login page keeps its generic confirmation on a provider failure to avoid revealing which addresses are administrators. Therefore, that message alone does not prove delivery. This flow does not queue failed emails for automatic delivery after a reset: request a fresh code when sending is available again.

Already signed-in sessions are not signed out merely because Resend reaches a quota. The separate mobile-approval login path still requires a valid eligible mobile session.

## Current SEQUO configuration

| Item | Configuration |
| --- | --- |
| Admin website | https://admin.sequoservice.com |
| Backend | https://api.sequoservice.com |
| Provider | Resend, HTTPS email API |
| Verified sending domain | `sequoservice.com` |
| Region | Ireland, `eu-west-1` |
| Sender | `SEQUO Admin <no-reply@sequoservice.com>` |
| Key name | `SEQUO Admin` |
| Permission | Sending access; the creation form offered All domains |
| Production configuration | `/home/ubuntu/sequo-api/.env`, permissions 0600 |

The key can send across domains in this Resend account; it is not restricted to a single domain and does not have Full access. Its value must stay out of Git, Markdown files, chat and frontend/VITE configuration. The temporary local key-transfer file was removed after installation.

Only the server stores `RESEND_API_KEY`. It also uses `ADMIN_EMAIL_ENABLED=true` and `ADMIN_EMAIL_FROM=no-reply@sequoservice.com`. No actual secret belongs in this guide.

The current root-domain records are documented in [RESEND_ROOT_DOMAIN_DNS.md](RESEND_ROOT_DOMAIN_DNS.md). The earlier `auth.sequoservice.com` verification remains documented in [ADMIN_EMAIL_DNS.md](ADMIN_EMAIL_DNS.md). Existing website and Zoho mailbox records were preserved.

## Branded email design

Admin codes are delivered as both styled HTML and a plain-text fallback. The HTML uses the approved green SEQUO brand, a prominent code panel, expiry, and French/English instructions. The approved PNG logo is attached inline using a content ID; no external image host, font or tracking pixel is required. Clients that hide images can still display the SEQUO alt text and the actual code.

Desktop and 375-pixel phone previews were visually checked, with no horizontal overflow. Browser previews do not guarantee identical rendering in every email client. Resend's own HTML preview does not display inline attachments; this is a dashboard limitation, not evidence that the logo is missing from the sent message. See [Resend's inline-image documentation](https://resend.com/docs/dashboard/emails/embed-inline-images).

Synthetic previews (sample code only): [desktop](branding/email/desktop-preview.png), [phone](branding/email/mobile-preview.png), [standalone HTML](branding/email/preview.html).

## Who receives login codes

Only existing, active accounts with ADMIN or SUPER_ADMIN access receive them. An unknown email does not create an account or grant access. The same generic confirmation is shown for ineligible addresses.

Codes expire after five minutes and are single-use. Requesting and verifying codes are rate limited. Application rate limits are separate from Resend's shared daily/monthly quotas.

## How to sign in

1. Open https://admin.sequoservice.com in the browser you want to use.
2. Enter your authorized staff email.
3. Choose **I cannot access my phone** to use email verification.
4. Click **Send email code**.
5. Copy the newest code from the email into **Email code** on that same page.
6. Click **Verify and open workspace**.

Browser sessions do not transfer automatically between Safari and Codex's in-app browser. A code already consumed in one browser cannot be reused to sign in elsewhere.

On 9 November 2026, provider delivery, owner inbox receipt, code consumption and hosted dashboard access were verified. The owner also completed a separate successful Safari sign-in.

## Where to check usage or troubleshoot

- In the Resend dashboard, open the team's **Usage** page to check remaining allowance and **Billing** to confirm the plan. A later account-owner change can alter the setup described here.
- Open **Emails → Sending** to inspect delivery status. Provider acceptance and Delivered status are useful evidence; actual inbox receipt was separately confirmed in the live test.
- If sending is blocked, wait for the relevant reset or deliberately choose a suitable paid plan. Do not enable billing options just to troubleshoot login.
- If a message is accepted but missing, check spam/junk and the recipient's mail filters before requesting another code.

Implementation, deployment and test evidence: [ADMIN_EMAIL_LOGIN.md](ADMIN_EMAIL_LOGIN.md).
