# Resend domain setup — 2026-11-09

The user completed Resend signup. `auth.sequoservice.com` was added in the account, region Ireland (`eu-west-1`), sending enabled and receiving disabled. This is pending DNS verification, not an active sender yet. Tracking was not enabled. The following records were read from the actual Resend manual-setup screen; use these values rather than older generic SPF/MX examples.

| Type | Cloudflare name in sequoservice.com zone | Value | Proxy | TTL |
| --- | --- | --- | --- | --- |
| TXT | `resend._domainkey.auth` | See public DKIM value below | n/a | Auto |
| CNAME | `rsend.auth` | `rsend-euw1.forge.rmta.net` | DNS only | Auto |
| CNAME | `send.auth` | `send.forge.rmta.net` | DNS only | Auto |

Public DKIM TXT value (not a private key):

```text
p=MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDaYpD6cwDPa3qYNsyslJt9mSD1P8FuGQAeOLHHAzQSK1iQ3mHp/Ccrjx4nQJW1pnGIWBPG684RTR+HE/lLzs3BaeXrkh/MKr3WkRR1sZ534vGERHNOFQLia8CqhOroErp7XwXJZCqIXjFZKxuXTvzIdRHEai0BVYBntl6YuPuOyQIDAQAB
```

Do not modify the optional root `_dmarc` record, existing mailbox MX records, or website records. Inspect existing records at these exact names before adding anything; conflicting values require review. Do not use Cloudflare auto-configuration delegation without its own permission review. The user explicitly approved these three records. Cloudflare sign-in redirected through Google and automatic approval review blocked observing that outside origin; the user was asked to complete sign-in manually. No DNS records have been changed yet.

After DNS verifies, the user must create a sending-only API key restricted to this domain. Credential creation is a user handoff. Save the key directly to the API host's protected configuration, not chat, documentation or a VITE setting. Then enable the sender with ADMIN_EMAIL_FROM=no-reply@auth.sequoservice.com, restore hosted API readiness, provision/check the explicitly authorized admin account, and verify actual inbox receipt and sign-in.

Update after OVH renewal, 2026-11-09: the owner switched to the Cloudflare account containing the domain. All three exact records were added, both CNAMEs DNS-only, TTL Auto. Public DNS resolves the expected values. Existing website, Zoho MX, SPF and DKIM records were preserved. Resend confirmed `auth.sequoservice.com` as Verified. The owner subsequently created a Sending-access key; the UI offered only All domains, so its sending scope covers the account. The key is installed in the protected VPS environment and delivery is enabled. Resend reported the first owner-requested login email as Delivered. No secret is recorded in these docs.
