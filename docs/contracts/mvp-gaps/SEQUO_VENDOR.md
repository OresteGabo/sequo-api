# Sequo Vendor MVP Gaps

Source tracker: `/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoVendor/docs/CONTRACT_MVP_TRACKER.md`

This file lists only missing or partial contract items relevant to the vendor app.

## Merchant Product Writes

- Contract item: Merchants or SEQUO can add, edit and deactivate products.
- Current evidence/path: `/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoVendor/shared/src/commonMain/kotlin/dev/orestegabo/sequovendor/ui/catalog`, `/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoVendor/shared/src/commonMain/kotlin/dev/orestegabo/sequovendor/core/catalog`, `/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/commerce`.
- Missing work: Keep the editor behind a repository interface until merchant-scoped create/update/deactivate endpoints exist, then wire real writes without inventing routes.
- Dependency: `sequo-api` merchant catalog write endpoints.
- Suggested verification command: `cd /Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoVendor && ./gradlew :androidApp:compileDebugKotlin`.

## Product Photo Capture And Upload

- Contract item: Product photos prioritize real-time photos when necessary; generic images may be allowed for standard products.
- Current evidence/path: `/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/catalog/ProductMediaPolicy.kt`, vendor product editor placeholder in `SequoVendor`.
- Missing work: Add real camera capture/upload flow after the backend exposes merchant-scoped media upload and verification endpoints. Preserve the policy that gallery upload is prohibited unless allowed for generic sealed items.
- Dependency: `sequo-api` product media upload/policy endpoints.
- Suggested verification command: `cd /Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoVendor && ./gradlew :androidApp:compileDebugKotlin`.

## Food Options And Variants

- Contract item: Food options, supplements, variants or toppings.
- Current evidence/path: `/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/catalog/FoodCustomization.kt`, vendor editor policy notes.
- Missing work: Build vendor editing UI for food-specific options only after confirming backend DTOs and write endpoints. Keep general goods variants separate from food customization.
- Dependency: `sequo-api` food customization write endpoints.
- Suggested verification command: `cd /Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoVendor && ./gradlew :androidApp:compileDebugKotlin`.

## Seller Bargaining Controls

- Contract item: Seller can disable bargaining on products.
- Current evidence/path: `SequoVendor` product editor hides/disables bargaining for now; `/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/bargaining`.
- Missing work: Add product-level bargaining controls and seller negotiation response screens only when the backend exposes supported merchant operations.
- Dependency: `sequo-api` bargaining product controls and seller response routes; `Sequo` customer bargaining flow.
- Suggested verification command: `cd /Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoVendor && ./gradlew :androidApp:compileDebugKotlin`.

## Store Status And Opening Hours Backend

- Contract item: Merchant follows sales, returns and payment statuses according to rights; seller operational availability is needed for order preparation.
- Current evidence/path: `SequoVendor` local store availability/hours repository and UI.
- Missing work: Replace local persistence with real merchant store open/closed, temporary closure, and weekly hours endpoints when they exist. Keep current repository boundary.
- Dependency: `sequo-api` merchant availability/opening-hours endpoints.
- Suggested verification command: `cd /Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoVendor && ./gradlew :androidApp:compileDebugKotlin`.

## Merchant Sales, Returns And Payment Visibility

- Contract item: Merchant follows sales, returns and payment statuses according to rights.
- Current evidence/path: `SequoVendor` dashboard, orders, and account screens; backend orders, returns and settlement domains in `sequo-api`.
- Missing work: Expand vendor-visible returns/payment/settlement status after backend exposes merchant-scoped read models.
- Dependency: `sequo-api` merchant returns/payment/settlement endpoints.
- Suggested verification command: `cd /Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoVendor && ./gradlew :androidApp:compileDebugKotlin`.

## Merchant Context Discovery

- Contract item: Base role/access and profile information needed for orders and deliveries.
- Current evidence/path: `SequoVendor` auth and temporary merchant context handling; `sequo-api` auth and merchant membership domain.
- Missing work: Replace temporary merchant selection/dev config with a real merchant-membership/current-merchant endpoint when available.
- Dependency: `sequo-api` authenticated merchant context endpoint.
- Suggested verification command: `cd /Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoVendor && ./gradlew :androidApp:compileDebugKotlin`.

