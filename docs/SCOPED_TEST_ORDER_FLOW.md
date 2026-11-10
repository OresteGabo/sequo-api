# Owner-authorized test accounts and no-funds fulfillment

The owner requested usable credentials for the already seeded fictional vendor/hub profiles and explicitly approved no-money confirmation for baskets containing only test products. This uses the normal deployed API/database and normal native apps. It does not enable the loopback-only development fixture endpoints or the global payment simulator.

## Authentication

Migration V69 adds `test_account_credentials`: unique usernames, salted PBKDF2-HMAC-SHA256 hashes (210,000 iterations), a single allowed app family, explicit enabled state and an expiry timestamp. No plaintext password is stored in this table. Provisioning generates unique passwords privately and expires them after 30 days. Passwords/tokens must not be logged or committed.

`TEST_ACCOUNT_LOGIN_ENABLED` defaults false. The normal apps request `/api/auth/test-accounts` and offer password-protected test sign-in only when enabled. `/api/auth/test-accounts/session` accepts username/password/family plus normal device metadata. It rate limits failures, refuses expired/disabled credentials and locks the current user while checking identity, roles and scope before issuing the ordinary device-bound session.

Only reserved fictional IDs with EMAIL provider, `.invalid` addresses and their exact non-privileged role may use this entry. An owner/staff must have exactly its expected active shop membership; an operator must have exactly its expected assigned active hub; a rider must be linked to its seeded active courier. Extra ordinary-shop/hub scope or an elevated role blocks test-password login. One `live-test-v1-customer` identity is allowed for the CUSTOMER family. Ordinary email/password login is not enabled.

The legacy backend uses `SEQUO_HUB` as Vendor's device app source too. The separate VENDOR/HUB credential-family check prevents cross-app credential use without a broad, unrelated AppSource migration.

Credentials expiring or being disabled stops new password sessions. Existing sessions retain normal refresh/revocation rules: revoke sessions or deactivate test users to end them immediately.

## No-money order boundary

`TEST_ORDER_FLOW_ENABLED` defaults false. Test quotes are eligible only for a nonempty basket of exact seeded product/merchant pairs. Mixed test/ordinary baskets are rejected at submission. Test records cannot go through ordinary payment submission while this flow is enabled. A legacy omitted `testOrder` flag stays compatible with ordinary checkout.

When ordinary routing has no origin configured, only an eligible test basket receives the fictional Tokoin estimate origin (6.1725, 1.2314). Ordinary baskets still require normal routing configuration.

Explicit test submission uses a closed pending-only payment processor: no wallet adapter call is made. It snapshots a server-owned `TEST_NO_FUNDS:` payment reference, truthful `DEMO_ILLUSTRATION` photo evidence, no benefit reservation/consumption and no subscriber priority. Ordinary photo/payment processing is unchanged.

The customer's explicit test-payment action locks only their own tagged pending checkout, revalidates every immutable snapshot line and uses normal fulfillment persistence. It does not create provider webhook evidence or a provider transaction claim. Provider callbacks cannot fulfill these test checkouts. Repeating confirmation returns the existing order.

For the initial one-shop journey, a bounded customer test-dispatch action requires the package to be packed and ready and permits only the four seeded test riders. Normal assignment, capacity and offer services run with the actual customer recorded as the requesting actor and a TEST reason. Test riders and ordinary orders are separated at assignment, offer visibility and acceptance. Test checkout cannot use an ordinary hub, and ordinary checkout cannot use a test hub.

Vendor acceptance/preparation, rider pickup/deposit and Hub customer-code release remain the normal scoped operational APIs. Test delivery skips merchant accrual and rider settlement posting. Test parcels skip storage-fee assessment; test orders cannot request refundable returns. No real benefit, ledger, payout, refund or provider receipt is created by the guided journey.

## Provisioning, verification and cleanup

`scripts/test-access/provision.py` is a one-time, private-server provisioning procedure. It requires V69 and an empty credential table, validates insertion with rollback before committing, creates the dedicated test customer/address and 27 unique scoped credentials, and saves private Markdown/JSON in a mode-restricted release directory. It refuses to overwrite existing accounts/credentials.

`scripts/test-access/live_smoke.py` signs in through the normal test-password endpoint for all 27 accounts and exercises the complete customer → vendor → rider → hub → customer pickup path through HTTP. Its custody metadata explicitly states scripted test validation, not a physical delivery. It verifies incorrect role access is denied and no financial records, provider receipts, consumed benefit applications or occupied slot remain.

Security/checkout focused tests and the final complete backend suite passed: 785 tests, zero failures/errors, one optional database exercise skipped. Native HTTP client tests and Android/iOS compilation/signing are recorded per app. A live PostgreSQL migration-copy check and deployed smoke verification are required before claiming deployment complete.

The user-facing walkthrough is `TEST_ORDER_FLOW.md` in the customer repository. Private credentials are excluded from Git at `demo/.private/TEST_ACCOUNTS.md`. Seed manifests record IDs for later cleanup. Before a real launch: disable the two feature flags, revoke/deactivate test identities, unpublish fixtures and preserve referenced order/audit history. Do not reuse reserved test IDs for real inventory.

## Provider sandbox

KKiaPay sandbox checkouts share the no-real-funds fulfillment protections but require server-verified provider evidence. They cannot use manual test-payment confirmation. Only Yas Togo and Moov Africa Togo are allowed. See [KKIAPAY_SETUP.md](KKIAPAY_SETUP.md) for activation, environment configuration and pending provider tests.
