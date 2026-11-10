# Customer account data and deletion requests

Normal Sequo Settings exposes **About Sequo**, **Download my data**, and **Delete my account**. About shows the native version/build and the existing policy links. Guests see About, but not private account actions.

## Export format

Authenticated `POST /api/account/data-export` takes optional `devicePreferences` (language, theme, largerText, tapFeedback, pinnedCategories, categoryUsage). User ownership always comes from the authenticated principal; client-supplied user IDs cannot select another account. The response is an attachment with `application/zip` and `Cache-Control: no-store`; no public export URL or bearer token is written into the archive.

The ZIP includes:

- `START_HERE.html`: readable, escaped, offline report; browser Print can produce a PDF.
- `data.json`: reusable structured records, format version, timestamp and customer-app scope.
- `data.txt`: a UTF-8 text copy.
- `attachments/`: original stored product photos referenced by the customer's own cart/order items.
- `README.txt`: scope, exclusions and interpretation of attachment availability.

Explicit ownership predicates and field allowlists cover profile, all saved addresses (including inactive), account/provider/device metadata, cart/options, pending checkouts and saved destination snapshots, orders/items/prices, seller preparation, deliveries/parcels/events/storage charges/pickup confirmations, returns, negotiations/messages, notifications/preferences/delivery status, subscriptions/benefits/referral credits and deletion requests. Device-local shopping preferences are included separately, labelled as device supplied.

Authentication credentials, refresh/push token material, PIN/pickup secrets, internal security/fraud records, other users' private data and merchant/hub/rider business workspaces are excluded. Photos are current catalog bytes, not historical proof of purchase or physical handoff. External/static image references without DB bytes are listed as reference-only; the server never fetches client-controlled URLs. Customer attachment uploads are not currently a supported product feature.

Interactive archives are capped at 10,000 records per section and 48 MiB of expanded content. Limits fail explicitly with 413 rather than silently producing an incomplete ZIP. Large accounts need an assisted, ownership-checked export; the app does not claim that an assisted-export request has been submitted. Downloads are limited to three per user per hour per API instance. The snapshot uses a read-only repeatable-read transaction. No export blob is retained by the server. iOS uses the Files document exporter and Android uses the existing system document saver; success is shown after native save completion, not merely after opening a chooser. Temporary iOS files are removed on completion/cancellation.

## Deletion: existing review workflow, not immediate erasure

`POST /api/account/deletion-requests` is the existing persisted initiation endpoint. It requires the exact server confirmation `DELETE_MY_ACCOUNT`; the app additionally asks the person to type `DELETE` in a confirmation dialog. Downloading first is offered, not required. Active requests are reused by the existing service.

New authenticated `GET /api/account/deletion-requests` returns only that person's request metadata, most recent first, with no-store. The app distinguishes REQUESTED, UNDER_REVIEW, APPROVED, COMPLETED, REJECTED and CANCELLED. A request is never described as completed erasure, and does not log the person out or block their subsequent download.

**Operational limitation:** the existing backend has no automated erasure executor. This change adds the in-app initiation/download/status experience; it does not invent a completion deadline or mark records erased. Operations must process requests, review open orders/disputes/records requiring retention, arrange the appropriate personal-data deletion/anonymization, revoke sessions/devices and linked identity grants, and record actual completion. Approving a request or changing its status alone does not erase data. Do not claim production-complete erasure until that processing/retention policy is implemented and verified.

For Sign in with Apple, token revocation must be included in permanent erasure. Current native Apple login supplies identity tokens, not an authorization-code exchange/refresh token suitable for revocation; that integration is a remaining operations/authentication dependency. See [Apple's account-deletion guidance](https://developer.apple.com/support/offering-account-deletion-in-your-app/). No live account is deleted while testing this feature.

## Verification

- Isolated HTTP tests exercise ownership, another account's address/photo exclusion, secret redaction, HTML escaping, actual stored file bytes, no-store, authentication/inactive users, rate limiting, deletion confirmation and request reuse.
- The full test relay journey additionally exports a delivered order, its item and mission, without creating money movements.
- Client contract tests exercise authenticated ZIP download, preferences, request status and exact deletion confirmation serialization.
- Runtime translation coverage checks every declared string in both selectable languages. This also repairs six cart labels missing from the earlier manual lookup maps.

### Deployed verification, 2026-11-10

Backend source `e6d06f86648049e5d94164c1149d172f50d14e52` is deployed as `sequo-api:account-data-20261110`; JAR SHA-256 `b0858860d1c71672e9d1c8e87f493d6351f71e47f29e04d72f524a3e046e66d8`. The previous configuration and a database backup were retained privately before activation. No schema migration was needed; existing auth/payment configuration was preserved. Public health, readiness and deployed artifact identity passed.

The live fictional customer successfully downloaded a valid owner-scoped archive containing completed orders, with no-store and no forbidden secret columns. Status retrieval passed; the temporary verification session was logged out. [Non-secret verification receipt](verification/account-data-export-2026-11-10.json). No live deletion request was submitted. Backend checks passed 5 isolation/privacy/contract tests plus the completed-order journey/export test; client download/confirmation and full translation coverage tests passed. Android and iOS compilation passed; the signed normal iPhone app was built and installed. Native Files interaction still needs a human save/cancel exercise; compilation/installation is not a claim that a user saved an archive.
