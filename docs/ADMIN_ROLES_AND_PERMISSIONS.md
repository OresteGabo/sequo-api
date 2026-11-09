# SEQUO administrator roles and permissions

Initial model: 9 November 2026. Support and daily operations are combined. There are three assignable administrator tiers.

## Permission matrix

| Capability | Operations (support included) | Staff manager | Owner |
| --- | --- | --- | --- |
| Sign in to the admin website with an email code | Yes | Yes | Yes |
| Read operational records and handle support issues | Yes | Yes | Yes |
| Assign deliveries, cancel eligible missions, reject eligible merchant orders | Yes | Yes | Yes |
| Manage catalog, returns and existing operational/financial workflows | Yes | Yes | Yes |
| Approve, suspend or reactivate operational personnel accounts | No | Yes | Yes |
| Grant/revoke courier, relay-partner or merchant personnel roles | No | Yes, for non-admin accounts | Yes |
| Enroll rider/merchant profiles and configure relay sites | No | Yes | Yes |
| Change rider workforce, relay operator assignments or merchant memberships | No | Yes | Yes |
| Appoint administrators or change an administrator's access/roles | No | No | Yes |
| Change your own access or roles | No | No | No |
| Disable or remove the last active Owner | No | No | No |
| Permanently delete personnel through these controls | No | No | No |

Permissions do not bypass business rules: paid-order checks, custody proof, cancellation eligibility, refund rules, merchant scope and audit requirements still apply. A cancellation is not proof that money was refunded. No unrestricted financial capability or new destructive endpoint was added.

An operational dispatch pause is separate from suspending a personnel account: Operations retains incident-response pause/unpause workflows, while only Staff managers or Owners can disable the person's account access.

## Stored roles and compatibility

| User-facing tier | Stored/API role |
| --- | --- |
| Operations (support included) | `ADMIN` |
| Staff manager | `STAFF_MANAGER` |
| Owner | `SUPER_ADMIN` |

Existing ADMIN and SUPER_ADMIN assignments are preserved. Adding the new role does not promote any account. Migration V63 only extends the role constraint; previously applied migrations remain unchanged. The role with the highest authority determines the displayed tier when an account holds several roles.

SUPPORT_AGENT is retained for compatibility with historical read-only support records. It is not a fourth assignable tier in the admin website and does not gain admin email sign-in. To move a legacy support account into the combined Operations tier, an Owner must explicitly grant ADMIN. This avoids silently increasing existing support accounts' permissions.

Vendor's role decoder and Hub's session checks recognize STAFF_MANAGER. Older installed apps may need an updated build before a newly appointed Staff manager uses those apps. Native application store distribution is separate from deploying the backend and website.

## Personnel management

A Staff manager can manage another non-admin account's operational roles. Account suspension/reactivation requires that the target already has a courier, relay-partner or merchant personnel role. Staff managers cannot change an account holding ADMIN, STAFF_MANAGER, SUPER_ADMIN or legacy SUPPORT_AGENT, even if it also has a personnel role.

In **Users**, load the current account and access history. Assign the required personnel role, then use the appropriate rider/merchant enrollment form. To approve an established personnel account, set active to true and status to ACTIVE; to stop its access, set active to false and status to SUSPENDED. Required reasons must describe the reviewed decision.

PENDING or DELETED identities cannot be verified, restored or recreated through these controls. Normal identity verification must finish first. Suspending access preserves the account, delivery/order history and responsibility records.

Existing protections remain: an assigned relay operator must be unbound or reassigned first, and a sole active merchant owner needs a replacement before removal. A personnel role cannot be revoked while an active matching merchant membership would restore its authority.

## Administrator management

Only an Owner can grant or revoke the three administrator roles or suspend another administrator. The server checks the actor's current persisted authority, not just the role names supplied by a client or stale token. Staff-manager access changes require the staff-management boundary at both controller and service layers.

Self access/role/membership changes are rejected. A global database lock serializes access changes, including concurrent attempts to remove the last Owner. Every write requires a reason, the exact current updatedAt revision and an idempotency key; retries retain the original actor and intent.

An accepted role/access/membership change rotates the target's authorization version and revokes its refresh sessions/devices. Existing access tokens then stop working. Reactivation does not revive old credentials: the person signs in again through the normal flow.

History records the actor, target, action, before/after values, reason and server timestamp. The website disables inappropriate controls and limits role choices; backend checks remain authoritative if someone calls an endpoint directly.

## Initial Owner

The project owner explicitly selected `orestegabo@icloud.com` as the initial Owner. After migration validation, a private operator transaction granted SUPER_ADMIN to that existing active account, preserved its ADMIN role, recorded the before/after authority in admin_access_changes, rotated its authorization version and revoked its old sessions. This one-time bootstrap is separate from the application self-change prohibition; it is not an email allowlist, public signup rule or automatic role migration. Unrelated accounts were preserved. A fresh sign-in is required.

Future administrators should be added from an explicitly approved list of exact work emails, with their intended tier recorded. Do not grant every staff member Owner access.

## Future changes

The backend RoleGroups and the website adminPermissions module centralize tier decisions. If support and operations need to split later, introduce an explicit permission model and reviewed migrations rather than inferring authority from email addresses or hiding buttons alone. Re-run the permission matrix and concurrent access-change tests when extending these rules.

## Verification and deployment

- 49 backend tests passed, including direct HTTP permission denials, forged role-claim denial, personnel grants/suspension, session invalidation, last-Owner protection, concurrent changes, staff-manager email sign-in and mobile approval regressions.
- Four UI permission tests passed; Vue/TypeScript production build passed. The hosted Vercel deployment for website commit `471e5d6a9b084aa402516d34bf83c0ff202f7a46` succeeded, and the public custom domain's actual bundle contains all three tiers.
- Vendor and Hub shared Kotlin metadata compiled successfully with STAFF_MANAGER compatibility. Their source changes were pushed; installed native apps are not automatically replaced by these commits.
- API release `sequo-admin-roles-20261109-01`, source `6d83d72ba2c578b4b35d30c0dbb6726b9d887332`, artifact SHA-256 `84a78078c91cc7f4eb6829e0469c645cef8445fa948df681af86253262648435` was backed up and tested first against a private production database copy. All 63 migration checksums validated; V63 applied successfully, with email sending and background workers disabled in the validation copy.
- The same release was activated on the VPS as `sequo-api:admin-roles-20261109`. V63 applied successfully to production and public readiness returned UP. Signing secrets, Resend credentials, business records and existing unrelated role assignments were retained.
- The temporary validation database/container and duplicate environment file were removed after live readiness passed. The protected pre-change database dump and environment snapshot remain under `/home/ubuntu/sequo-admin-roles-20261109/` with the rollback image `sequo-api:before-admin-roles-20261109`.
- After the audited Owner assignment, a fresh normal email-code sign-in was completed in Safari. The live dashboard loaded server records and displayed the Owner badge for the confirmed account. No login code or browser token was added to this guide or committed.

Do not automatically downgrade to an older application after assigning STAFF_MANAGER accounts: older clients/services may not recognize that additive role. Review assignments and preserve applied migrations when planning a rollback.
