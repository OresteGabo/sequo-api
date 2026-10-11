# Sequo Rider MVP Gaps

Source tracker: `/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoVendor/docs/CONTRACT_MVP_TRACKER.md`

This file lists only missing or partial contract items relevant to the rider app.

## Rider Auth And Role Guard

- Contract item: User creation/login and base courier role access.
- Current evidence/path: `/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoRider/shared`, `/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/auth`.
- Missing work: Implement rider auth, secure token storage, refresh flow, `/api/auth/me`, and courier-role checks, following the customer/vendor auth pattern.
- Dependency: `sequo-api` auth and role authorities.
- Suggested verification command: `cd /Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoRider && ./gradlew :androidApp:compileDebugKotlin`.

## Mission Inbox And Acceptance

- Contract item: Rider receives or accepts mission.
- Current evidence/path: Backend delivery mission domain in `sequo-api`; `SequoRider` docs/scaffold.
- Missing work: Build API client, repository, mission list/detail UI, accept/reject actions, loading/empty/offline states, and localized safe errors.
- Dependency: `sequo-api` delivery mission endpoints.
- Suggested verification command: `cd /Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoRider && ./gradlew :androidApp:compileDebugKotlin`.

## Pickup Workflow

- Contract item: Rider picks up from merchant, SEQUO or relay.
- Current evidence/path: `sequo-api` delivery/hub/relay domains; `SequoRider` pickup and dispatch docs.
- Missing work: Implement pickup screens for merchant pickup, SEQUO pickup, and relay pickup with code/PIN/proof validation where supported.
- Dependency: `sequo-api` delivery, hub, and relay endpoints; `SequoVendor` package-ready handoff.
- Suggested verification command: `cd /Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoRider && ./gradlew :androidApp:compileDebugKotlin`.

## Delivery Or Relay Deposit Workflow

- Contract item: Rider delivers to customer or deposits at relay.
- Current evidence/path: `sequo-api` delivery/relay domains; `SequoRider` route and relay docs.
- Missing work: Implement customer delivery confirmation, failed delivery fallback, relay deposit, and safe status transitions.
- Dependency: `sequo-api` delivery tracking and relay endpoints; `SequoHub` parcel intake.
- Suggested verification command: `cd /Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoRider && ./gradlew :androidApp:compileDebugKotlin`.

## Delivery Step Validation

- Contract item: Rider validates delivery steps in app.
- Current evidence/path: Backend delivery/PIN services and rider docs.
- Missing work: Add PIN/code scan/manual entry, duplicate-submit prevention, clear retry states, and status timeline updates.
- Dependency: `sequo-api` validation endpoints.
- Suggested verification command: `cd /Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoRider && ./gradlew :androidApp:compileDebugKotlin`.

## Rider History And Payout Visibility

- Contract item: Rider sees independent courier pay calculation.
- Current evidence/path: `/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoRider/PAYOUT_STRUCTURE.md`, backend settlement/delivery domains.
- Missing work: Implement mission history and pay calculation visibility for independent riders; avoid showing salaried-courier freelancer calculations.
- Dependency: `sequo-api` rider settlement/payout read endpoints.
- Suggested verification command: `cd /Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoRider && ./gradlew :androidApp:compileDebugKotlin`.

## Courier Availability And Assignment UX

- Contract item: Salaried and independent couriers, SEQUO priority for subscribers, fallback to independents, logistics tracking.
- Current evidence/path: `sequo-api` delivery assignment domain; `SequoRider` dispatch docs.
- Missing work: Add rider availability/capacity state, assignment state, timeout/reassignment display, and notifications.
- Dependency: `sequo-api` assignment/availability endpoints and notification routes.
- Suggested verification command: `cd /Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoRider && ./gradlew :androidApp:compileDebugKotlin`.

