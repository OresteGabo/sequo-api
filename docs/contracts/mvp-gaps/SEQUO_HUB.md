# Sequo Hub MVP Gaps

Source tracker: `/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoVendor/docs/CONTRACT_MVP_TRACKER.md`

This file lists only missing or partial contract items relevant to the hub/relay app.

## Backend-Connected Package Intake

- Contract item: Relay receives package and assigns code/QR/locker.
- Current evidence/path: `/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoHub/shared`, `/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/hub`, `/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/relay`.
- Missing work: Verify or wire scan/receive package screens to real backend endpoints, including code/QR/locker assignment and localized safe errors.
- Dependency: `sequo-api` hub/relay package intake endpoints.
- Suggested verification command: `cd /Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoHub && ./gradlew :androidApp:compileDebugKotlin`.

## Customer Pickup/Handover

- Contract item: Pickup by numeric code, QR code and identity check; relay validates customer pickup and stores history.
- Current evidence/path: `SequoHub` scan/handover/activity features and `sequo-api` relay domain.
- Missing work: Connect handover to backend validation, identity-check recording, immutable history, and duplicate-submit prevention.
- Dependency: `sequo-api` relay pickup validation endpoints.
- Suggested verification command: `cd /Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoHub && ./gradlew :androidApp:compileDebugKotlin`.

## Return Drop-Off At Relay

- Contract item: Eligible returns can be dropped at relay point.
- Current evidence/path: `/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoHub/RETURNS_HANDLING.md`, `SequoHub` handover surfaces, `sequo-api` returns/relay domains.
- Missing work: Implement backend-connected return drop-off flow with eligibility check, receipt state, and SEQUO recovery status.
- Dependency: `sequo-api` returns and relay endpoints; `Sequo` customer return request.
- Suggested verification command: `cd /Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoHub && ./gradlew :androidApp:compileDebugKotlin`.

## Collection Manifest Flow

- Contract item: SEQUO recovers returned product; internal logistics tracking by SEQUO.
- Current evidence/path: `SequoHub` workflow docs and hub/relay backend domains.
- Missing work: Implement or verify collection manifest UI and API wiring for SEQUO pickup from relay/hub.
- Dependency: `sequo-api` hub collection/manifest endpoints and `SequoRider` pickup flow if riders collect packages.
- Suggested verification command: `cd /Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoHub && ./gradlew :androidApp:compileDebugKotlin`.

## Storage Fees And Overdue Inventory

- Contract item: Storage fees after more than two weeks; after about one month, return to seller or apply policy.
- Current evidence/path: `/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/relay/RelayStorageFee.kt`, `/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoHub/STORAGE_PENALTIES.md`.
- Missing work: Show backend-connected overdue parcels, fees, policy actions, and operator-safe state changes.
- Dependency: `sequo-api` relay storage-fee and policy endpoints; admin settings for fee/delay configuration.
- Suggested verification command: `cd /Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoHub && ./gradlew :androidApp:compileDebugKotlin`.

## Hub Role Enforcement

- Contract item: Simplified relay role/access.
- Current evidence/path: `SequoHub` auth/settings and `sequo-api` hub auth/domain.
- Missing work: Verify every protected hub action checks relay/hub role and hides disabled actions in the UI.
- Dependency: `sequo-api` auth role enforcement.
- Suggested verification command: `cd /Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoHub && ./gradlew :androidApp:compileDebugKotlin`.

