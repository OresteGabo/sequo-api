# Sequo API MVP Gaps

Source tracker: `/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoVendor/docs/CONTRACT_MVP_TRACKER.md`

This file lists only missing or partial contract items relevant to the backend. Do not invent routes; read the API source before implementing any endpoint.

## Merchant Catalog Write API

- Contract item: Merchants or SEQUO can add, edit and deactivate products.
- Current evidence/path: `/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/commerce`, `/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/catalog`, vendor local editor in `SequoVendor`.
- Missing work: Add merchant-scoped product create/update/deactivate routes, DTOs, validation, role checks, tests, and mobile API documentation if not already present.
- Dependency: `SequoVendor` product editor wiring.
- Suggested verification command: `cd /Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api && ./gradlew test`.

## Product Media Upload And Policy API

- Contract item: Product photos prioritize real-time photos when necessary; generic images may be allowed for standard products.
- Current evidence/path: `ProductMediaPolicy.kt`, catalog policy tests/docs.
- Missing work: Add merchant media upload/verification endpoints that enforce real-time camera photo requirements and allowed generic-image exceptions.
- Dependency: `SequoVendor` camera/upload UI.
- Suggested verification command: `cd /Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api && ./gradlew test`.

## Merchant Store Availability And Hours API

- Contract item: Merchant operational availability needed for order fulfillment.
- Current evidence/path: Local repository in `SequoVendor`; no merchant-specific store status/hours API confirmed in tracker.
- Missing work: Add merchant open/closed status, temporary closure, weekly hours, exceptions, last-updated, and customer-facing message routes with merchant role checks.
- Dependency: `SequoVendor` store screen.
- Suggested verification command: `cd /Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api && ./gradlew test`.

## Merchant Bargaining Response And Controls API

- Contract item: Customer can negotiate when allowed; seller can disable bargaining on products.
- Current evidence/path: `/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/bargaining`, `CommerceApi.kt`.
- Missing work: Verify complete routes for product-level bargaining enable/disable, seller accept/counter/reject, attempts, lock duration, and customer status. Add missing routes/tests/docs only where absent.
- Dependency: `Sequo` customer bargaining UI and `SequoVendor` seller response UI.
- Suggested verification command: `cd /Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api && ./gradlew test`.

## Payment Provider Production Integration

- Contract item: Integrate or prepare Yas Togo and Moov Africa payments; order must be paid before validation; pay couriers through same systems when possible.
- Current evidence/path: `/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/payment`, order checkout/payment tests.
- Missing work: Complete real provider adapters, secure credentials, callbacks/webhooks, payment validation, refunds, and payout execution paths.
- Dependency: `Sequo` checkout, `SequoRider` payout visibility, admin finance UI.
- Suggested verification command: `cd /Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api && ./gradlew test`.

## Rider Mobile Mission API Audit

- Contract item: Rider receives/accepts missions, picks up, delivers/deposits, validates steps, and sees pay calculation.
- Current evidence/path: `sequo-api` delivery mission/assignment/settlement domains; `SequoRider` scaffold/docs.
- Missing work: Audit exact mobile-ready mission endpoints and add any missing list/detail/action/history/payout routes with strict role checks and tests.
- Dependency: `SequoRider` mission app.
- Suggested verification command: `cd /Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api && ./gradlew test`.

## Hub/Relay Mobile API Audit

- Contract item: Relay receives package, assigns code/QR/locker, validates pickup, records history, handles returns and storage fees.
- Current evidence/path: `sequo-api` hub/relay domains; `SequoHub` sample/local UI surfaces.
- Missing work: Audit exact mobile-ready hub/relay endpoints and add any missing receive/handover/return/manifest/storage-fee routes with role checks and tests.
- Dependency: `SequoHub` scan, handover, return, and storage screens.
- Suggested verification command: `cd /Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api && ./gradlew test`.

## Admin Configuration And Operations API

- Contract item: Admin web controls users, merchants, orders, deliveries, payments, commissions, settings and relays; business parameters are configurable or prepared.
- Current evidence/path: `sequo-api/domain/admin`, commission/pricing/subscription/referral/bargaining/returns/relay domains.
- Missing work: Audit and fill backend admin endpoints needed by `sequo-admin` for operational dashboards and configuration, with safe mutations, confirmations/reason fields where appropriate, and audit trails.
- Dependency: `sequo-admin` web app.
- Suggested verification command: `cd /Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api && ./gradlew test`.

## Merchant Context Endpoint

- Contract item: Base roles/access and profile information needed for orders and deliveries.
- Current evidence/path: `sequo-api` auth and merchant membership domains; temporary merchant context in `SequoVendor`.
- Missing work: Add or expose authenticated current-merchant/memberships endpoint so vendor app can stop using temporary merchant selection.
- Dependency: `SequoVendor` auth/account and order fulfillment.
- Suggested verification command: `cd /Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api && ./gradlew test`.

