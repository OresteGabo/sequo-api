# Resend root-domain sender — 9 November 2026

Requested sender: `SEQUO Admin <no-reply@sequoservice.com>`.

Resend domain ID: `9edd3bfb-084f-49e0-a05f-4771fb176b9b`; Ireland (`eu-west-1`). Created pending verification. Receiving and open/click tracking remain disabled.

The following exact records were read from Resend's manual setup screen. They authorize Resend to send from `sequoservice.com`; they are separate from the already verified `auth.sequoservice.com` records. Root-domain DNS changes are pending the owner's action-time confirmation.

| Type | Name in the sequoservice.com Cloudflare zone | Value | Proxy | TTL |
| --- | --- | --- | --- | --- |
| TXT | `resend._domainkey` | Public DKIM key below | n/a | Auto |
| CNAME | `rsend` | `rsend-euw1.forge.rmta.net` | DNS only | Auto |
| CNAME | `send` | `send.forge.rmta.net` | DNS only | Auto |

Public DKIM key (not a secret):

```text
p=MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQCyc/0bBU4aRCuKsjjXdKEzr0u2XtjK7cOj8UCFIuqCMbgrprLlYCCClWPirOA0BJztlW0wfz+0p1ivtYbmWLLPP+NiNrKODAJ8+ULosd0Htn/k2pWwIqmkViYv3gpi36pEqVE2O+6A1aYI76/8ZZl51qRKXXaZkzHSkqKw7FAWSQIDAQAB
```

Preserve all existing website, Zoho MX/SPF/DKIM, and auth-subdomain records. Do not change nameservers, add receiving routes, or replace the root SPF record. Public DNS checks found no existing values at these three exact names; check the live zone again before writing.

After DNS verification, update only `ADMIN_EMAIL_FROM` in the protected VPS environment, keeping the existing sending-only API key. No new credential or paid subscription is needed. Deploy the tested branded template, confirm API readiness, and verify a live email's sender and delivery.
