# Activity history

The admin website's **Activity → Activity history** panel reads the persistent
`activity_journal` table through `GET /api/admin/web/activity`.
Owner, Staff manager and Operations can read it. Customers, merchants and riders
cannot. There are no update/delete endpoints. Responses use `Cache-Control: no-store`.

## What is recorded

All authenticated requests reaching an API controller with POST, PUT, PATCH or
DELETE are journaled, including customer, merchant and rider requests. Each records
the authenticated user ID, role snapshot, HTTP method and route template, safe
path resource IDs, operation ID, timestamp and eventual HTTP status.

An independent transaction commits `STARTED` **before** controller execution. If
this insert fails, the controller is not executed. A second append records
`HTTP_SUCCEEDED` (2xx) or `HTTP_FAILED`. Both entries share an operation ID.
A business rollback cannot erase the initial entry. If a crash or database outage
prevents the final append, the STARTED entry remains and needs investigation;
Spring logs the completion failure. It is not proof of a committed change.

An HTTP success is a response outcome, not proof of a new change: idempotent
retries, quotes and other POST reads can succeed without changing business state.
Asynchronous provider completion and background jobs retain their own domain
events; they are not represented as human requests. Requests rejected before MVC
(invalid authentication, rate limits), anonymous requests and ordinary GET reads
are outside this request journal.

Administrator authentication events are also persisted as `AUTH_EVENT`, using
the existing sanitized auth event contract. Anonymous or unknown identity events
can have a null actor. No email address is inferred for those events.

## Detailed changes and reasons

The journal complements existing transactional domain history. For example,
administrator removal records the actor, target, reason, before/after roles,
idempotency key and timestamp in `admin_access_changes`. It invalidates the removed
administrator's old sessions. Open the account's access history to inspect those
details. Personnel changes, enrollment, dispatch, relay administration, catalog
media, commissions and other business workflows retain their existing histories.

The new journal deliberately does not copy arbitrary request bodies, headers,
query values, passwords, email codes, tokens, uploaded files or customer contact
details. Only path variables ending in `Id` are retained as resource references;
session, request, credential and token IDs are excluded from that collection.
Body-only targets must be investigated through the domain's detailed history.

Historical records are preserved in their original tables. The general request
journal starts at deployment of migration V64. Past events are not fabricated.

## Searching

The panel supports exact actor ID, exact target reference (for example
`userId=…`), operation ID, from/until timestamps and page number (starting at 0).
Time filters are inclusive from / exclusive until. Results show newest first,
50 per page by default, maximum 100. Multiple path IDs form a sorted reference,
for example `merchantId=…; userId=…`. Copy the displayed reference to filter it.

## Preservation and deployment

V64 is additive. PostgreSQL rejects UPDATE, DELETE and TRUNCATE on the journal
through an append-only trigger. Actor IDs have no cascading foreign key, so
removing an identity cannot remove its audit trail. Existing backups must continue
to include this table. No retention deletion job is configured. Database owners
can change database definitions; this is not an externally signed archive.

Before production activation: run ActivityJournalHttpTest,
AdminUserAccessHttpTest and AdminAuthControllerEmailCodeTest; build the admin
website; validate V64 and its immutable trigger against an isolated PostgreSQL
copy. Do not edit already-applied Flyway migrations.

## Deployment verification — 9 November 2026

- API revision `7635b0acae3be14f0152ab26c32bae3816084eaf`, release
  `sequo-activity-20261109-01`; production Flyway version 64 and public readiness UP.
- Admin website revision `aacc6e7b0caeda80bdd05eefeb19e00217655b3d` deployed
  successfully through Vercel. Activity history loaded in Safari using the
  existing Owner session. No test mutations were made to production accounts.
- 40 backend checks passed across the journal, account access, email login,
  concurrent email verification, operational controls, enrollment and relay
  administration; all four website permission checks and the production build passed.
- An isolated PostgreSQL database copy validated schema migration, unfiltered,
  actor and date-filtered history reads, and persistent actor/target/failure
  evidence from a rejected administrator mutation. UPDATE, DELETE and TRUNCATE
  were each rejected by the append-only trigger. The copy used its own signing
  key and was removed after validation.
- GitHub Actions could not start because of the existing account billing lock.
  The tested artifact was deployed using the established API-only manual fallback.
  Its SHA-256 is
  `a3bdabe8b6fda58792f0f3bbe9275893188229726933c83b2fb4fb0bd92c7bb6`.
  A private pre-deployment database backup and rollback environment were retained
  on the VPS; production secrets, identities, roles and paid-launch gates were preserved.
