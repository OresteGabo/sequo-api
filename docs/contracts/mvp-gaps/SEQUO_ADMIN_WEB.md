# Sequo Admin Web MVP Gaps

Source tracker: `/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoVendor/docs/CONTRACT_MVP_TRACKER.md`

This file lists only missing or partial contract items relevant to the internal SEQUO web admin interface.

## Production Admin Auth And Permissions

- Contract item: Internal SEQUO web admin interface; base admin role/access.
- Current evidence/path: `/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-admin`, `/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/auth`, `/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/admin`.
- Missing work: Replace template/demo state with real auth, secure token handling, admin role guards, route protection, and safe localized errors.
- Dependency: `sequo-api` auth/admin endpoints.
- Suggested verification command: `cd /Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-admin && npm run build`.

## Operational Dashboard

- Contract item: Internal logistics tracking by SEQUO; internal admin monitoring.
- Current evidence/path: `sequo-admin/src/App.vue` has demo operational dashboard; backend admin monitoring exists.
- Missing work: Wire dashboard cards/tables to real API data for orders, missions, deliveries, relay parcels, returns, payments, and incidents.
- Dependency: `sequo-api` admin monitoring/read endpoints.
- Suggested verification command: `cd /Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-admin && npm run build`.

## Users, Roles And Profiles Management

- Contract item: User creation/login, base roles, profile information needed for orders/deliveries.
- Current evidence/path: `sequo-admin` demo users/profiles screens; `sequo-api` auth/admin domains.
- Missing work: Build real user/profile search, review, role display/actions, merchant/rider/hub activation, and permission-aware safe mutations.
- Dependency: `sequo-api` admin user/profile endpoints.
- Suggested verification command: `cd /Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-admin && npm run build`.

## Merchant, Catalog And Commission Management

- Contract item: SEQUO can manage products; commissions configurable by merchant/category.
- Current evidence/path: `sequo-admin` demo shops/commissions screens; `sequo-api` catalog/commission domains.
- Missing work: Build API-backed merchant management, catalog review, commission override UI, reason fields, and audit-friendly actions.
- Dependency: `sequo-api` merchant/catalog/commission admin endpoints.
- Suggested verification command: `cd /Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-admin && npm run build`.

## Orders, Sub-Orders And Delivery Tracking

- Contract item: Customer order flow, merchant preparation, logistics tracking, delivery grouping.
- Current evidence/path: `sequo-admin` demo orders/deliveries/missions/consolidations screens; `sequo-api` order/delivery domains.
- Missing work: Build real searchable tables and details for orders, merchant sub-orders, delivery missions, consolidation manifests, status timelines, and safe operational actions.
- Dependency: `sequo-api` order/delivery/admin endpoints.
- Suggested verification command: `cd /Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-admin && npm run build`.

## Relay, Hub And Storage Controls

- Contract item: Relay points, pickup code/QR/identity check, storage fees, return-to-seller policy.
- Current evidence/path: `sequo-admin` demo hubs/relay screens; `sequo-api` hub/relay domains.
- Missing work: Build real hub/relay inventory, parcel history, overdue fee, locker/counter state, and policy action screens.
- Dependency: `sequo-api` hub/relay admin endpoints and `SequoHub` mobile workflows.
- Suggested verification command: `cd /Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-admin && npm run build`.

## Returns And Refunds Operations

- Contract item: Return request, relay drop-off, SEQUO recovery, condition check, refund instruction.
- Current evidence/path: `sequo-admin` demo returns screen; `sequo-api` returns/payment domains.
- Missing work: Build return review, physical receipt, condition decision, refund status, and history views using backend data.
- Dependency: `sequo-api` returns/refunds endpoints and payment provider refund execution.
- Suggested verification command: `cd /Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-admin && npm run build`.

## Payments, Settlements And Payouts

- Contract item: Yas/Moov payments, courier pay, SEQUO shortfall coverage, merchant statuses.
- Current evidence/path: `sequo-admin` demo settlements/webhooks screens; `sequo-api` payment/settlement domains.
- Missing work: Build finance views for payments, provider webhooks, merchant settlements, rider payout calculations, held batches, reconciliation, and safe execution flows.
- Dependency: `sequo-api` payment/settlement endpoints and production provider setup.
- Suggested verification command: `cd /Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-admin && npm run build`.

## Business Parameter Settings

- Contract item: Minimum delivery price, per-km price, commissions, subscriber discounts, referral credits, bargaining attempt count, lock duration, return delay, relay storage delay/fees, excluded categories.
- Current evidence/path: Backend domain models exist across pricing, commission, subscription, referral, bargaining, returns and relay; `sequo-admin` has a demo settings screen.
- Missing work: Build real configuration UI for each contract parameter, with permission checks, validation, change history, and safe publish/revert behavior.
- Dependency: `sequo-api` configuration/admin endpoints.
- Suggested verification command: `cd /Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-admin && npm run build`.

## Notifications, Support And Audit

- Contract item: Internal operations/support plus notification-provider integration as prepared system behavior.
- Current evidence/path: `sequo-admin` demo notifications/support/audit screens; `sequo-api` notifications/admin domains.
- Missing work: Wire notification inbox/provider status, support investigations, and audit log views to real API data. Avoid exposing secrets, PII, or raw backend debug messages.
- Dependency: `sequo-api` notification/support/audit endpoints.
- Suggested verification command: `cd /Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-admin && npm run build`.

