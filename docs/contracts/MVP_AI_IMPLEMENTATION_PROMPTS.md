# SEQUO MVP AI implementation prompts

Use these prompts in order. Each prompt is intentionally scoped to one small task and includes enough context for an AI coding agent to produce usable code.

Global project root:

`/Users/muhirwagabooreste/AndroidStudioProjects/SequoService`

Projects:

- Customer app: `/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/Sequo`
- Vendor app: `/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoVendor`
- Rider app: `/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoRider`
- Hub/relay app: `/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoHub`
- API/backend source of truth: `/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api`

Contract source:

`/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/docs/contracts/contrat_mvp_sequo.tex`

Tracker source:

`/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoVendor/docs/CONTRACT_MVP_TRACKER.md`

Important rules for every prompt:

- Do not invent backend routes.
- Read the API source before calling any endpoint.
- If an endpoint is missing, create repository interfaces and local/mock implementations, and document the missing API contract.
- Keep all user-visible strings localized in EN/FR if the project has localization.
- Do not display raw exception messages or stack traces in UI.
- Do not log access tokens, refresh tokens, OAuth tokens, payment tokens, device tokens, PII or backend secrets.
- Use `/api`, not `/api/v1`.
- Verify with the smallest relevant Gradle command at the end.

## 1. Audit the Contract Tracker Against Current Code

Prompt:

```text
You are working inside /Users/muhirwagabooreste/AndroidStudioProjects/SequoService.

Task: audit the current MVP tracker before implementing anything.

Read:
- sequo-api/docs/contracts/contrat_mvp_sequo.tex
- SequoVendor/docs/CONTRACT_MVP_TRACKER.md
- README.md or equivalent docs in Sequo, SequoVendor, SequoRider, SequoHub, sequo-api

Then scan each project for the tracker items marked incomplete or partial. Do not edit code yet unless the tracker is clearly wrong.

Output/update only:
- SequoVendor/docs/CONTRACT_MVP_TRACKER.md

Rules:
- Only list items from the contract.
- Do not add later feature ideas unless they are also in the contract.
- Mark an item done only if code or a concrete UI/API surface exists, not merely a design document.
- If a project has only docs or scaffold for an item, mark it partial/not done.
- Keep the tracker concise but evidence-based: include project paths in the “where done” column.

Verification:
- Run git status in SequoVendor and sequo-api.
- Confirm sequo-api has no changes.
```

## 2. Create Per-Project MVP Gap Files

Prompt:

```text
You are working inside /Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoVendor.

Task: split the contract tracker into per-project implementation checklists.

Read:
- docs/CONTRACT_MVP_TRACKER.md

Create:
- docs/mvp-gaps/SEQUO_CUSTOMER.md
- docs/mvp-gaps/SEQUO_VENDOR.md
- docs/mvp-gaps/SEQUO_RIDER.md
- docs/mvp-gaps/SEQUO_HUB.md
- docs/mvp-gaps/SEQUO_API.md
- docs/mvp-gaps/SEQUO_ADMIN_WEB.md

Each file must contain only contract-listed missing or partial items relevant to that project.

For each item include:
- contract item
- current evidence/path
- missing work
- dependency on another project, if any
- suggested verification command

Do not implement features in this prompt.

Verification:
- Confirm all six markdown files exist.
- Run git status.
```

## 3. Audit API Endpoint Availability For MVP Gaps

Prompt:

```text
You are working in /Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api.

Task: read-only audit of API endpoint availability for remaining MVP app work.

Do not modify sequo-api.

Read:
- MOBILE_API_GUIDE.md
- API_SPEC.md
- src/main/kotlin/dev/orestegabo/sequo_api/domain/**
- SequoVendor/docs/CONTRACT_MVP_TRACKER.md

Create or update this file only in the vendor app:
- /Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoVendor/docs/API_ENDPOINT_GAP_AUDIT.md

Cover these contract areas:
- customer checkout home vs relay choice
- customer returns
- merchant catalog create/update/deactivate/upload
- merchant bargaining enable/disable and seller negotiation responses
- rider mission list/detail/accept/pickup/deliver/deposit/history/payout
- hub scan/receive/handover/returns/collection manifest
- admin web needs
- payment validation/refunds/payouts

For each area list:
- available endpoints with exact method/path
- missing endpoints
- DTOs found in source
- recommended app behavior until backend is ready

Rules:
- Do not invent routes.
- Do not edit API files.
- Use API source as truth, not guesses.

Verification:
- git status in sequo-api must be clean.
- git status in SequoVendor should only show the new/updated audit file.
```

## 4. Vendor Catalog Repository Contract Cleanup

Prompt:

```text
You are working in /Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoVendor.

Task: make the vendor catalog repository boundary production-safe before adding more editor features.

Read:
- SequoVendor/docs/CONTRACT_MVP_TRACKER.md
- SequoVendor/docs/API_ENDPOINT_GAP_AUDIT.md if present
- SequoVendor/shared/src/commonMain/kotlin/dev/orestegabo/sequovendor/core/catalog/**
- SequoVendor/shared/src/commonMain/kotlin/dev/orestegabo/sequovendor/ui/catalog/**
- sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/commerce/CommerceApi.kt
- sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/catalog/**

Implement:
- A clear VendorCatalogRepository interface that separates server reads from local draft writes.
- Keep server read/filter behavior only for routes that exist.
- Keep product create/update/deactivate/photo upload behind local/mock methods if backend routes are missing.
- Add explicit “local draft” state to local write results.
- Ensure the UI labels local/mock saved products as local drafts, not server-synced products.

Do not:
- Invent POST/PUT/DELETE product routes.
- Display raw Throwable.message.
- Use floating-point money.

Verification:
- ./gradlew :androidApp:compileDebugKotlin
```

## 5. Vendor Product Editor: Bargaining Controls Local-Only

Prompt:

```text
You are working in /Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoVendor.

Task: add seller product-level bargaining controls to the product editor, but keep writes local/mock until backend support exists.

Read:
- Sequo/CATALOG_AND_ORDERING.md
- Sequo/BARGAINING_FLOW.md
- SequoVendor/shared/src/commonMain/kotlin/dev/orestegabo/sequovendor/ui/catalog/CatalogContent.kt
- SequoVendor/shared/src/commonMain/kotlin/dev/orestegabo/sequovendor/core/catalog/**
- SequoVendor/shared/src/commonMain/composeResources/values/strings.xml
- SequoVendor/shared/src/commonMain/composeResources/values-fr/strings.xml

Implement:
- A bargaining enabled/disabled switch in product editor.
- Hide or disable any unsupported detailed negotiation settings unless they can be safely stored locally.
- Show a localized note that bargaining settings are saved as a local draft until server sync is available.
- Persist in the existing local/mock catalog repository layer.
- Add fields to model classes only where needed.

Rules:
- All strings EN/FR.
- Do not call missing backend routes.
- Do not add bargaining UI to order fulfillment yet; this prompt is product settings only.

Verification:
- ./gradlew :androidApp:compileDebugKotlin
```

## 6. Vendor Product Editor: Food Options And Toppings Local Draft

Prompt:

```text
You are working in /Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoVendor.

Task: add food options/toppings/variants editor UI for vendor products as local draft data.

Read:
- Sequo/CATALOG_AND_ORDERING.md
- sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/catalog/FoodCustomization.kt
- sequo-api/src/test/kotlin/dev/orestegabo/sequo_api/domain/catalog/FoodCustomizationServiceTest.kt
- SequoVendor/shared/src/commonMain/kotlin/dev/orestegabo/sequovendor/ui/catalog/**
- SequoVendor/shared/src/commonMain/kotlin/dev/orestegabo/sequovendor/core/catalog/**

Implement:
- In product editor, add a section for food/perishable products:
  - option group name
  - required/optional
  - max selections if relevant
  - option/topping label
  - price delta in CFA as Int
  - availability toggle
- Keep it compact and operational.
- Save locally through repository interface.
- Show a localized local-draft/sync-pending label.

Rules:
- Money must remain Int CFA.
- All strings EN/FR.
- No backend writes unless an endpoint already exists.
- No generic upload flow for food photos unless backend policy allows it.

Verification:
- ./gradlew :androidApp:compileDebugKotlin
```

## 7. Vendor Negotiation Inbox UI

Prompt:

```text
You are working in /Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoVendor.

Task: add a seller negotiation inbox UI, using repository interfaces and mock/local fallback if backend endpoints are missing.

Read:
- Sequo/BARGAINING_FLOW.md
- sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/bargaining/Bargaining.kt
- sequo-api/src/test/kotlin/dev/orestegabo/sequo_api/domain/bargaining/BargainingServiceTest.kt
- SequoVendor/shared/src/commonMain/kotlin/dev/orestegabo/sequovendor/ui/app/SequoVendorShell.kt
- SequoVendor/shared/src/commonMain/kotlin/dev/orestegabo/sequovendor/ui/account/AccountContent.kt
- SequoVendor/shared/src/commonMain/kotlin/dev/orestegabo/sequovendor/ui/catalog/**

Implement:
- A compact “Negotiations” entry accessible from Account/Settings or Catalog.
- List mock/server negotiation threads:
  - product
  - customer offer
  - seller counter if present
  - attempts remaining
  - lock expiry if accepted
  - status
- Detail actions:
  - accept
  - reject with reason
  - counter-offer
- Keep repository interface ready for API integration.
- If backend mobile endpoints are missing, use fake data and mark the screen as local/demo clearly.

Rules:
- Do not add notification routing unless already present.
- No raw backend errors.
- All strings EN/FR.
- Prevent duplicate taps on actions.

Verification:
- ./gradlew :androidApp:compileDebugKotlin
```

## 8. Vendor Sales, Returns And Payment Status Visibility

Prompt:

```text
You are working in /Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoVendor.

Task: improve merchant visibility for sales, returns and payment/settlement statuses using only existing backend data or local placeholders.

Read:
- SequoVendor/shared/src/commonMain/kotlin/dev/orestegabo/sequovendor/ui/dashboard/**
- SequoVendor/shared/src/commonMain/kotlin/dev/orestegabo/sequovendor/ui/orders/**
- SequoVendor/shared/src/commonMain/kotlin/dev/orestegabo/sequovendor/core/fulfillment/**
- sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/settlement/**
- sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/returns/**
- sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/delivery/MerchantSubOrder.kt

Implement:
- In vendor dashboard/account, show compact cards for:
  - sales today
  - merchant net pending
  - returns awaiting decision/collection if available
  - payment/settlement state if available
- In order detail/list, show return/payment state only when present in DTOs.
- If fields/routes are missing, show “Not available yet” localized and document missing API in README.

Rules:
- Do not invent settlement routes.
- Do not show fake financial values as real.
- Money as Int CFA.
- Strings EN/FR.

Verification:
- ./gradlew :androidApp:compileDebugKotlin
```

## 9. Rider App Foundation: Replace Scaffold With Sequo-Style Shell

Prompt:

```text
You are working in /Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoRider.

Task: replace the current scaffold/demo app with a production-ready SequoRider app shell, inspired by Sequo and SequoVendor.

Read:
- Sequo/shared/src/commonMain/kotlin/dev/orestegabo/sequo/ui/app/SequoShell.kt
- Sequo/shared/src/commonMain/kotlin/dev/orestegabo/sequo/ui/chrome/BottomBar.kt
- SequoVendor/shared/src/commonMain/kotlin/dev/orestegabo/sequovendor/ui/app/SequoVendorShell.kt
- SequoRider/README.md
- SequoRider/FLEET_DISPATCH.md
- SequoRider/ROUTE_OPTIMIZATION.md
- SequoRider/PAYOUT_STRUCTURE.md

Implement folders if missing:
- core/auth
- core/network
- core/missions
- core/payout
- feature/auth
- feature/splash
- feature/settings
- ui/app
- ui/chrome
- ui/components
- ui/missions
- ui/route
- ui/history
- ui/payout
- model

Create screens:
- Missions
- Active Route
- History
- Payout
- Account/Settings

Keep first logged-in screen as Missions.

Rules:
- Use package dev.orestegabo.sequorider.
- Use operational courier UI, not customer shopping UI.
- Add localized EN/FR strings if localization exists or create the pattern.
- Do not connect fake mission actions to server yet.

Verification:
- ./gradlew :androidApp:compileDebugKotlin
```

## 10. Rider Auth: Copy Sequo Auth Pattern

Prompt:

```text
You are working in /Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoRider.

Task: implement rider authentication by adapting the Sequo customer auth pattern.

Read:
- Sequo/shared/src/commonMain/kotlin/dev/orestegabo/sequo/core/auth/**
- Sequo/shared/src/androidMain/kotlin/dev/orestegabo/sequo/core/auth/**
- Sequo/shared/src/iosMain/kotlin/dev/orestegabo/sequo/core/auth/**
- Sequo/shared/src/commonMain/kotlin/dev/orestegabo/sequo/feature/auth/AuthScreen.kt
- sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/auth/AuthController.kt
- sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/auth/RoleAuthorities.kt

Implement in SequoRider:
- AuthApiClient
- AuthRepository
- AuthSessionStore expect/actual
- SecureTokenStorage expect/actual if Sequo uses it
- Google sign-in result integration if current project has platform setup
- /api/auth/me support
- logout
- refresh token flow if Sequo pattern has it

Rider role rule:
- After /api/auth/me, require courier/rider role where possible.
- If rider role is not discoverable yet, show a temporary dev context screen clearly marked temporary.

Rules:
- Never log tokens.
- Never display raw Throwable.message.
- Map auth errors to localized EN/FR strings.
- Protected routes use Authorization: Bearer accessToken.

Verification:
- ./gradlew :androidApp:compileDebugKotlin
```

## 11. Rider Mission API Inventory And Repository Interface

Prompt:

```text
You are working in /Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoRider.

Task: build rider mission repository boundaries based on existing sequo-api endpoints.

Read:
- sequo-api/MOBILE_API_GUIDE.md
- sequo-api/API_SPEC.md
- sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/delivery/DeliveryMissionController.kt
- sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/delivery/DeliveryMissionPersistence.kt
- sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/delivery/DeliveryMissionController.kt
- sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/delivery/DeliveryPinService.kt
- sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/delivery/CourierAvailability.kt
- SequoRider/FLEET_DISPATCH.md

Implement:
- RiderMissionApiClient for only routes confirmed in API source.
- RiderMissionRepository interface.
- FakeRiderMissionRepository for previews/offline/dev.
- DTOs/models:
  - RiderMission
  - MissionStatus
  - PickupStop
  - DropoffStop
  - RouteStop
  - ProofRequirement
  - CourierAvailabilityState

Do not:
- Invent routes.
- Implement UI actions before repository methods are clear.

Document missing endpoints in SequoRider/README.md.

Verification:
- ./gradlew :androidApp:compileDebugKotlin
```

## 12. Rider Mission List Screen

Prompt:

```text
You are working in /Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoRider.

Task: implement the rider mission list screen.

Read:
- SequoRider/core/missions from previous prompt
- SequoRider/FLEET_DISPATCH.md
- SequoRider/ROUTE_OPTIMIZATION.md
- SequoVendor/ui/orders for compact operational card style

Screen requirements:
- Tabs/filters:
  - Available
  - Assigned
  - Active
  - Completed
  - Problem
- Each mission card shows:
  - mission code/id
  - mission type
  - pickup count
  - dropoff count
  - distance if available
  - ETA/SLA warning if available
  - courier payout if independent and available
  - status
- Include visible refresh action.
- Loading, empty, offline, retry states.

Actions:
- For available mission: accept if endpoint exists; otherwise disabled with localized “Not available yet”.
- For assigned mission: view route.

Rules:
- Prevent duplicate taps.
- No raw errors.
- All strings EN/FR.

Verification:
- ./gradlew :androidApp:compileDebugKotlin
```

## 13. Rider Mission Detail And Route Steps

Prompt:

```text
You are working in /Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoRider.

Task: implement rider mission detail with route steps.

Read:
- SequoRider/core/missions
- SequoRider/ROUTE_OPTIMIZATION.md
- sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/delivery/DeliveryMissionController.kt

Detail must show:
- mission code/id
- status timeline
- pickup stops
- dropoff stops
- relay dropoff/pickup if present
- customer delivery note if present
- package count
- proof/PIN requirements
- payout preview for independent courier if available
- SLA/ETA warning if available

Actions must be safe:
- start pickup
- mark pickup done
- report pickup problem
- start delivery
- confirm delivery or relay deposit
- report delivery problem

Only enable actions that have real API endpoints. If endpoint missing, keep the button disabled and localized.

Verification:
- ./gradlew :androidApp:compileDebugKotlin
```

## 14. Rider Pickup Flow

Prompt:

```text
You are working in /Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoRider.

Task: implement rider pickup flow against existing endpoints or local repository placeholders.

Read:
- sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/delivery/**
- SequoRider/FLEET_DISPATCH.md
- SequoRider/ROUTE_OPTIMIZATION.md
- SequoVendor order packed/handoff flow for merchant-side coordination

Implement UI:
- pickup stop card
- merchant/hub/SEQUO pickup source label
- package count expected
- scan/confirm package placeholder
- “cannot pick up” reason dialog
- custody note / package condition note

Repository:
- Use existing API route if present.
- If missing, define interface methods and fake/local behavior only.
- Document missing route in README.

Rules:
- Reason text sanitized and length-limited.
- No duplicate submits.
- All strings EN/FR.

Verification:
- ./gradlew :androidApp:compileDebugKotlin
```

## 15. Rider Delivery And Relay Deposit Flow

Prompt:

```text
You are working in /Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoRider.

Task: implement delivery completion and relay deposit UI.

Read:
- sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/delivery/DeliveryPinService.kt
- sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/delivery/DeliveryMissionController.kt
- sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/relay/**
- SequoHub/LOCKER_WORKFLOWS.md

Implement:
- Home delivery confirmation panel:
  - PIN/code input if required
  - customer unavailable action
  - delivery problem reason
- Relay deposit panel:
  - relay point label
  - package code/QR placeholder
  - locker/slot handoff if available
  - deposit confirmation

Only call endpoints that exist. Missing endpoints should be repository interface methods with fake/local fallback and README documentation.

Rules:
- Codes should be sanitized.
- No offline-only final delivery if server validation is required.
- No raw errors.
- All strings EN/FR.

Verification:
- ./gradlew :androidApp:compileDebugKotlin
```

## 16. Rider History Screen

Prompt:

```text
You are working in /Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoRider.

Task: implement rider mission history screen.

Read:
- SequoRider/core/missions
- SequoRider/PAYOUT_STRUCTURE.md
- sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/delivery/**
- sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/settlement/**

Implement:
- Completed/problem/cancelled mission history list.
- Date filter or simple segmented control.
- Mission detail summary with:
  - pickup/dropoff locations
  - completion time
  - issue status
  - payout preview if independent courier
- Empty/loading/offline/retry states.

Rules:
- Use repository interface.
- Do not invent routes.
- Local/mock data must be labeled as local/demo if shown.
- All strings EN/FR.

Verification:
- ./gradlew :androidApp:compileDebugKotlin
```

## 17. Rider Payout Visibility

Prompt:

```text
You are working in /Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoRider.

Task: implement rider payout visibility for independent couriers without executing real payments.

Read:
- SequoRider/PAYOUT_STRUCTURE.md
- sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/settlement/**
- sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/delivery/**

Screen:
- Current period earnings
- Completed deliveries
- Pending payout
- Held/problem payout
- Digital payout method label: Yas/Moov if present
- Internal/salaried courier should see “salary handled outside delivery splits” style text.

Rules:
- Do not create real payout execution.
- Do not invent endpoints.
- If no payout endpoint exists, use repository interface and fake/local preview data with clear local/demo label.
- All money Int CFA.
- All strings EN/FR.

Verification:
- ./gradlew :androidApp:compileDebugKotlin
```

## 18. Customer Checkout: Home Delivery Vs Relay Choice Audit/Fix

Prompt:

```text
You are working in /Users/muhirwagabooreste/AndroidStudioProjects/SequoService/Sequo.

Task: verify and finish the customer checkout choice between home delivery and relay point if it is missing or incomplete.

Read:
- Sequo/shared/src/commonMain/kotlin/dev/orestegabo/sequo/ui/basket/**
- Sequo/shared/src/commonMain/kotlin/dev/orestegabo/sequo/ui/orders/**
- Sequo/shared/src/commonMain/kotlin/dev/orestegabo/sequo/model/SequoModels.kt
- Sequo/CATALOG_AND_ORDERING.md
- Sequo/DELIVERY_PRICING.md
- sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/order/**
- sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/relay/**

Implement if needed:
- Checkout delivery option selector:
  - Home delivery
  - Relay pickup when available/eligible
- Show eligibility notes:
  - food/perishable/sensitive products may not use relay
  - relay pickup uses code/QR/ID verification
- Include selected delivery mode in order draft model only if backend DTO supports it.
- If backend does not support this yet, store locally and document missing API.

Rules:
- Do not invent checkout route fields silently.
- All strings localized EN/FR.
- No raw errors.

Verification:
- ./gradlew :androidApp:compileDebugKotlin
```

## 19. Customer Return Request UI

Prompt:

```text
You are working in /Users/muhirwagabooreste/AndroidStudioProjects/SequoService/Sequo.

Task: implement or complete customer return request UI for the contract 72-hour return flow.

Read:
- Sequo/RETURNS_POLICY.md
- Sequo/shared/src/commonMain/kotlin/dev/orestegabo/sequo/ui/orders/**
- sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/returns/ReturnController.kt
- sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/returns/ReturnProcessing.kt
- sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/relay/**

Implement:
- In delivered order detail, show return eligibility:
  - available within 72 hours
  - not eligible after deadline
  - product/category not eligible if data exists
- Return request flow:
  - select eligible item(s)
  - choose reason
  - confirm relay drop-off flow
  - show return reference/code if API returns it
- If endpoint missing, use repository interface and mock/local confirmation clearly labeled.

Rules:
- Do not promise refund before SEQUO inspection.
- No cash refund or cash withdrawal wording.
- All strings EN/FR.
- No raw errors.

Verification:
- ./gradlew :androidApp:compileDebugKotlin
```

## 20. Customer Return Status Tracking

Prompt:

```text
You are working in /Users/muhirwagabooreste/AndroidStudioProjects/SequoService/Sequo.

Task: add return/refund status tracking to customer order details.

Read:
- Sequo/RETURNS_POLICY.md
- Sequo/shared/src/commonMain/kotlin/dev/orestegabo/sequo/ui/orders/**
- sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/returns/**
- sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/payment/**

Show statuses:
- return requested
- drop-off pending
- dropped at relay
- collected by SEQUO
- under inspection
- refund processing
- refund completed
- return rejected/expired

Rules:
- Use only statuses returned by API if available.
- If unavailable, map local/demo status clearly.
- Do not expose raw backend names if they are ugly; map to localized strings.
- All strings EN/FR.

Verification:
- ./gradlew :androidApp:compileDebugKotlin
```

## 21. Customer Bargaining End-To-End UI Audit/Fix

Prompt:

```text
You are working in /Users/muhirwagabooreste/AndroidStudioProjects/SequoService/Sequo.

Task: audit and complete customer bargaining UI where possible.

Read:
- Sequo/BARGAINING_FLOW.md
- Sequo/shared/src/commonMain/kotlin/dev/orestegabo/sequo/ui/home/**
- Sequo/shared/src/commonMain/kotlin/dev/orestegabo/sequo/ui/catalog/**
- Sequo/shared/src/commonMain/kotlin/dev/orestegabo/sequo/ui/basket/**
- sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/bargaining/Bargaining.kt

Implement/fix:
- Product detail shows bargaining availability only when product allows it.
- Customer can submit lower price offer if endpoint exists.
- Show attempts remaining.
- Show accepted locked price expiry if available.
- Checkout uses negotiated price only when still valid.
- If backend mobile endpoint is missing, keep UI behind repository interface and local/mock label.

Rules:
- No bargaining on products where seller disabled it.
- No fake discount presented as real.
- All strings EN/FR.
- Prevent duplicate submissions.

Verification:
- ./gradlew :androidApp:compileDebugKotlin
```

## 22. Hub API Wiring Audit

Prompt:

```text
You are working in /Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoHub.

Task: audit SequoHub screens against current sequo-api routes and wire repositories where safe.

Read:
- SequoHub/README.md
- SequoHub/LOCKER_WORKFLOWS.md
- SequoHub/RETURNS_HANDLING.md
- SequoHub/STORAGE_PENALTIES.md
- SequoHub/shared/src/commonMain/kotlin/dev/orestegabo/sequohub/**
- sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/hub/**
- sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/relay/**
- sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/returns/**

Implement:
- HubRepository interface.
- HubApiClient for existing endpoints only.
- FakeHubRepository for previews/offline/dev.
- Update dashboard/scan/handover/activity screens to consume repository state.

Rules:
- Do not invent routes.
- Single-use code/QR values must not be logged.
- No raw errors.
- Keep UI compact and operational.

Verification:
- ./gradlew :androidApp:compileDebugKotlin
```

## 23. Hub Scan/Receive Package Flow

Prompt:

```text
You are working in /Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoHub.

Task: complete the hub package receive flow.

Read:
- SequoHub/LOCKER_WORKFLOWS.md
- SequoHub/shared/src/commonMain/kotlin/dev/orestegabo/sequohub/feature/scan/ScanReceiveScreen.kt
- SequoHub/shared/src/commonMain/kotlin/dev/orestegabo/sequohub/feature/hub/LockerUi.kt
- sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/hub/**
- sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/relay/**

Implement:
- Scan or enter package code.
- Validate expected hub if API supports it.
- Reject/flag wrong hub.
- Exclude food/perishable missed-delivery packages.
- Assign free locker.
- Confirm storage.
- Show single-use code/QR status if available.

Rules:
- Sanitize code input.
- No offline-only final receive if server validation is required.
- All strings localized if project uses localization.
- No raw backend errors.

Verification:
- ./gradlew :androidApp:compileDebugKotlin
```

## 24. Hub Customer Pickup/Handover Flow

Prompt:

```text
You are working in /Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoHub.

Task: complete customer pickup/handover flow at relay point.

Read:
- SequoHub/LOCKER_WORKFLOWS.md
- SequoHub/shared/src/commonMain/kotlin/dev/orestegabo/sequohub/feature/handover/HandoverScreen.kt
- sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/hub/**
- sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/relay/**

Implement:
- Enter/scan pickup code or QR.
- Show package/locker details after validation.
- Require ID verification checkbox/confirmation.
- Show storage fees due if available.
- Confirm handover and free locker.
- Show invalid/expired/already-used code states.

Rules:
- Do not display raw codes after validation longer than needed.
- No raw errors.
- Prevent duplicate confirm taps.

Verification:
- ./gradlew :androidApp:compileDebugKotlin
```

## 25. Hub Return Drop-Off Flow

Prompt:

```text
You are working in /Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoHub.

Task: implement eligible customer return drop-off at hub.

Read:
- SequoHub/RETURNS_HANDLING.md
- sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/returns/**
- sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/hub/**
- SequoHub/shared/src/commonMain/kotlin/dev/orestegabo/sequohub/feature/**

Implement:
- Return code/QR entry or scan.
- Validate return is within 72 hours and eligible if API provides data.
- Verify customer ID checkbox/confirmation.
- Record visible package condition note.
- Assign locker/storage.
- Show “refund is decided later by SEQUO” message.
- Add return item to activity/dashboard.

Rules:
- Hub must not approve refund.
- Reason/condition note sanitized.
- No raw errors.

Verification:
- ./gradlew :androidApp:compileDebugKotlin
```

## 26. Hub Collection Manifest Flow

Prompt:

```text
You are working in /Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoHub.

Task: implement SEQUO collection manifest handoff flow.

Read:
- SequoHub/LOCKER_WORKFLOWS.md
- SequoHub/RETURNS_HANDLING.md
- sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/hub/**
- sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/relay/**

Implement:
- Screen or panel for collection agent pickup.
- Enter/scan collection manifest code.
- Show listed packages/returns.
- Confirm each listed locker/package release.
- Record handoff and free lockers locally/API if endpoint exists.
- Show partial collection/problem state.

Rules:
- Do not allow release of unlisted package.
- No raw errors.
- Prevent duplicate submits.

Verification:
- ./gradlew :androidApp:compileDebugKotlin
```

## 27. Hub Storage Fees And Overdue Inventory UI

Prompt:

```text
You are working in /Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoHub.

Task: implement storage age, fee and overdue inventory visibility.

Read:
- SequoHub/STORAGE_PENALTIES.md
- sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/relay/RelayStorageFee.kt
- SequoHub/shared/src/commonMain/kotlin/dev/orestegabo/sequohub/feature/hub/HubDashboard.kt
- SequoHub/shared/src/commonMain/kotlin/dev/orestegabo/sequohub/feature/activity/ActivityScreen.kt

Implement:
- Dashboard summary:
  - packages under grace period
  - fee due
  - overdue
  - return-to-seller due
- Per package:
  - stored since
  - days stored
  - fee due if any
  - next action

Rules:
- Money as Int CFA.
- Do not charge/collect money unless backend endpoint exists.
- No raw errors.

Verification:
- ./gradlew :androidApp:compileDebugKotlin
```

## 28. API Merchant Catalog Write Routes

Prompt:

```text
You are working in /Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api.

Task: implement merchant-scoped catalog write endpoints required by the contract.

Before editing, read:
- MOBILE_API_GUIDE.md
- API_SPEC.md
- src/main/kotlin/dev/orestegabo/sequo_api/domain/commerce/CommerceApi.kt
- src/main/kotlin/dev/orestegabo/sequo_api/domain/catalog/**
- src/main/kotlin/dev/orestegabo/sequo_api/domain/auth/AuthorizationPolicy.kt
- SequoVendor/docs/CONTRACT_MVP_TRACKER.md
- SequoVendor/docs/API_ENDPOINT_GAP_AUDIT.md if present

Implement only if no equivalent endpoint already exists:
- POST /api/merchant/catalog/products
- PATCH /api/merchant/catalog/products/{productId}
- POST /api/merchant/catalog/products/{productId}/deactivate
- POST /api/merchant/catalog/products/{productId}/activate

Rules:
- Require merchant role/membership.
- Merchant can only manage own products.
- Money as Int CFA.
- Include commission/margin/service fee fields only if existing domain model supports them.
- Include bargaining enabled flag.
- Include food customization fields if existing policy supports them.
- Add tests for authorization, validation and ownership.

Do not implement photo upload in this prompt.

Verification:
- ./gradlew test
```

## 29. API Product Photo Upload Policy Endpoint

Prompt:

```text
You are working in /Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api.

Task: implement merchant product photo metadata/upload preparation endpoint required by contract photo policy.

Read:
- src/main/kotlin/dev/orestegabo/sequo_api/domain/catalog/ProductMediaPolicy.kt
- src/test/kotlin/dev/orestegabo/sequo_api/domain/catalog/ProductMediaPolicyServiceTest.kt
- Sequo/CATALOG_AND_ORDERING.md
- SequoVendor/docs/CONTRACT_MVP_TRACKER.md

Implement only if no endpoint exists:
- POST /api/merchant/catalog/products/{productId}/photos

Design:
- Accept metadata needed to classify:
  - real-time camera photo
  - generic standard-product image if allowed
  - sealed-item exception if policy allows
- Reject gallery upload for products requiring real-time photos.
- Enforce merchant ownership.
- Return safe photo status/verification DTO.

Rules:
- Do not store binary blobs unless project already has a storage pattern.
- If real upload storage is not ready, implement metadata/status endpoint and document storage gap.
- Add tests for allowed/rejected photo cases.

Verification:
- ./gradlew test
```

## 30. Wire Vendor Catalog Writes To New API

Prompt:

```text
You are working in /Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoVendor.

Task: wire vendor product editor to real merchant catalog write endpoints if they now exist.

Read:
- SequoVendor/core/catalog/**
- SequoVendor/ui/catalog/**
- sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/catalog/**
- sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/commerce/**
- sequo-api/MOBILE_API_GUIDE.md

Implement:
- CatalogApiClient methods for merchant product create/update/activate/deactivate if routes exist.
- Repository chooses server write when authenticated merchant context exists.
- Keep local draft fallback when server returns unavailable or user is in dev/mock mode.
- Update UI copy so server-synced vs local draft is clear.

Rules:
- Do not call routes that do not exist.
- No raw errors.
- Prevent duplicate saves.
- All strings EN/FR.

Verification:
- ./gradlew :androidApp:compileDebugKotlin
```

## 31. API Rider Mission Mobile Endpoints Audit/Completion

Prompt:

```text
You are working in /Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api.

Task: complete rider mobile mission endpoints required by contract if missing.

Read:
- src/main/kotlin/dev/orestegabo/sequo_api/domain/delivery/DeliveryMissionController.kt
- src/main/kotlin/dev/orestegabo/sequo_api/domain/delivery/DeliveryMissionPersistence.kt
- src/main/kotlin/dev/orestegabo/sequo_api/domain/delivery/DeliveryFulfillmentWorkflow.kt
- src/main/kotlin/dev/orestegabo/sequo_api/domain/delivery/DeliveryPinService.kt
- src/main/kotlin/dev/orestegabo/sequo_api/domain/auth/AuthorizationPolicy.kt
- SequoRider/FLEET_DISPATCH.md
- SequoRider/ROUTE_OPTIMIZATION.md

Implement only missing endpoints:
- GET /api/rider/missions
- GET /api/rider/missions/{missionId}
- POST /api/rider/missions/{missionId}/accept
- POST /api/rider/missions/{missionId}/pickup/start
- POST /api/rider/missions/{missionId}/pickup/complete
- POST /api/rider/missions/{missionId}/delivery/start
- POST /api/rider/missions/{missionId}/delivery/complete
- POST /api/rider/missions/{missionId}/relay-deposit
- POST /api/rider/missions/{missionId}/problem

Rules:
- Require courier/rider role.
- Rider can only mutate assigned/available missions according to dispatch policy.
- Validate PIN/code where required.
- Add tests for state transitions and authorization.

Verification:
- ./gradlew test
```

## 32. Wire Rider App To Real Mission Endpoints

Prompt:

```text
You are working in /Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoRider.

Task: wire rider app mission screens to real API endpoints if they now exist.

Read:
- SequoRider/core/missions/**
- SequoRider/ui/missions/**
- SequoRider/ui/route/**
- sequo-api/MOBILE_API_GUIDE.md
- sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/delivery/DeliveryMissionController.kt

Implement:
- Api client calls for list/detail/accept/pickup/delivery/relay/problem.
- Refresh after successful actions.
- Disable duplicate taps while action is in progress.
- Show safe localized error states.
- Keep fake repository for previews/offline/dev fallback.

Rules:
- Use Authorization Bearer token.
- Do not log tokens or mission codes unnecessarily.
- No raw Throwable.message in UI.

Verification:
- ./gradlew :androidApp:compileDebugKotlin
```

## 33. API Admin Web Minimum Surface

Prompt:

```text
You are working in /Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api.

Task: audit and complete backend endpoints needed for the contract admin web interface.

Read:
- src/main/kotlin/dev/orestegabo/sequo_api/domain/admin/**
- src/main/kotlin/dev/orestegabo/sequo_api/domain/auth/**
- src/main/kotlin/dev/orestegabo/sequo_api/domain/order/**
- src/main/kotlin/dev/orestegabo/sequo_api/domain/delivery/**
- src/main/kotlin/dev/orestegabo/sequo_api/domain/commission/**
- src/main/kotlin/dev/orestegabo/sequo_api/domain/pricing/**
- src/main/kotlin/dev/orestegabo/sequo_api/domain/relay/**
- SequoVendor/docs/CONTRACT_MVP_TRACKER.md

Implement only missing backend admin endpoints for:
- users
- merchants
- orders
- deliveries
- payments/settlements
- commissions
- delivery pricing
- relay settings
- business parameters from Annex A.6

Rules:
- Require SEQUO admin role.
- Add tests for authorization.
- Keep sensitive data out of responses.
- Do not build web UI in this prompt.

Verification:
- ./gradlew test
```

## 34. Create Admin Web App Skeleton

Prompt:

```text
You are working in /Users/muhirwagabooreste/AndroidStudioProjects/SequoService.

Task: create a minimal SEQUO admin web app for contract MVP internal management.

First inspect whether a web admin app already exists. If none exists, create a new project folder:
- SequoAdmin

Requirements:
- Internal operational web UI, not marketing.
- Login/auth shell.
- Sections:
  - Dashboard
  - Users
  - Merchants
  - Orders
  - Deliveries
  - Payments/settlements
  - Commissions
  - Relay points
  - Settings/business parameters
- Use API `/api` endpoints only.
- Use safe mock data until endpoints are ready.

Do not:
- Put secrets in frontend.
- Build customer-facing pages.
- Add features outside contract.

After creation, update:
- SequoVendor/docs/CONTRACT_MVP_TRACKER.md

Verification:
- Run the web build/test command appropriate to the created stack.
```

## 35. Admin Web Auth And Role Guard

Prompt:

```text
You are working in /Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoAdmin.

Task: implement admin authentication and role guard.

Read:
- sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/auth/AuthController.kt
- sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/auth/RoleAuthorities.kt
- Sequo/core/auth or SequoVendor/core/auth for client-side patterns

Implement:
- Login with supported backend auth.
- Store access/refresh tokens safely for web app constraints.
- Call /api/auth/me.
- Require SEQUO admin role.
- Logout.
- Refresh token flow if available.

Rules:
- Never log tokens.
- No raw backend errors.
- All UI text EN/FR if localization is set up.

Verification:
- Run project build/test command.
```

## 36. Admin Web Dashboard

Prompt:

```text
You are working in /Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoAdmin.

Task: build the admin dashboard for contract MVP operations.

Use:
- sequo-api admin monitoring endpoints if available.
- Mock/fallback data only when endpoint is absent, clearly labeled local/demo.

Dashboard cards:
- pending orders
- merchant packages waiting
- active deliveries
- relay overdue packages
- returns awaiting inspection
- payment/settlement issues
- courier availability issues

Rules:
- Operational dense UI.
- No marketing hero.
- No raw errors.
- Do not invent routes.

Verification:
- Run project build/test command.
```

## 37. Admin Web Business Parameter Settings

Prompt:

```text
You are working in /Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoAdmin.

Task: build admin settings UI for contract Annex A.6 business parameters.

Parameters:
- minimum delivery price
- price per kilometer
- commission percentages
- subscriber discount rates
- referral credits
- bargaining attempt count
- negotiated price lock duration
- return request delay
- relay storage delays and fees
- categories excluded from relay/returns

Read corresponding sequo-api domains:
- pricing
- commission
- subscription
- referral
- bargaining
- returns
- relay

Rules:
- Use endpoints only if present.
- Missing endpoint: show disabled field with “backend endpoint needed” and update README/TODO.
- Money as Int CFA.
- No raw errors.

Verification:
- Run project build/test command.
```

## 38. Admin Web Users And Roles

Prompt:

```text
You are working in /Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoAdmin.

Task: build users/roles admin UI for contract account management.

Read:
- sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/auth/**
- SequoVendor/docs/CONTRACT_MVP_TRACKER.md

UI:
- user list
- search/filter by role
- user detail
- roles displayed: client, courier, merchant/vendor, SEQUO admin, relay if included
- profile info necessary for orders/deliveries

Actions:
- Only implement actions with real admin endpoints.
- Disable missing actions with localized note.

Rules:
- Do not expose sensitive auth internals.
- No raw errors.

Verification:
- Run project build/test command.
```

## 39. Admin Web Merchant Management

Prompt:

```text
You are working in /Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoAdmin.

Task: build merchant management UI for contract MVP.

Read:
- sequo-api/domain/auth/MerchantMembership.kt
- sequo-api/domain/commerce/**
- sequo-api/domain/catalog/**
- sequo-api/domain/commission/**

UI:
- merchant list
- merchant profile/detail
- product count
- commission rate/configuration
- catalog status
- store/order readiness status if available

Actions:
- View merchant products.
- Configure commission if endpoint exists.
- Activate/deactivate merchant products only if endpoint exists.

Rules:
- No invented routes.
- All money Int CFA.
- No raw errors.

Verification:
- Run project build/test command.
```

## 40. Admin Web Orders And Delivery Tracking

Prompt:

```text
You are working in /Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoAdmin.

Task: build orders and delivery tracking UI for contract MVP.

Read:
- sequo-api/domain/order/**
- sequo-api/domain/delivery/**
- sequo-api/domain/relay/**

UI:
- order list with payment/order status
- order detail with merchant sub-orders
- delivery mission status
- courier assignment
- relay fallback if applicable
- internal logistics tracking timeline

Actions:
- Only implement actions backed by admin endpoints.
- Missing actions should be disabled and documented.

Rules:
- No raw errors.
- Avoid exposing sensitive customer data beyond operational need.

Verification:
- Run project build/test command.
```

## 41. Admin Web Returns And Refunds

Prompt:

```text
You are working in /Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoAdmin.

Task: build returns/refunds admin UI for contract MVP.

Read:
- sequo-api/domain/returns/**
- sequo-api/domain/payment/**
- Sequo/RETURNS_POLICY.md
- SequoHub/RETURNS_HANDLING.md

UI:
- return requests list
- dropped at relay
- collected by SEQUO
- under inspection
- approved/rejected
- refund pending/completed/problem

Actions:
- Inspect/approve/reject only if backend endpoint exists.
- Trigger refund instruction only if backend endpoint exists.

Rules:
- Refund after physical receipt and condition check only.
- No cash refund path.
- No raw errors.

Verification:
- Run project build/test command.
```

## 42. Payment Provider Integration Audit

Prompt:

```text
You are working in /Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api.

Task: audit payment provider readiness for Yas Togo and Moov Africa without adding credentials.

Read:
- src/main/kotlin/dev/orestegabo/sequo_api/domain/payment/**
- src/test/kotlin/dev/orestegabo/sequo_api/domain/payment/**
- Sequo/PAYMENTS_AND_REFERRALS.md
- SequoVendor/docs/CONTRACT_MVP_TRACKER.md

Create/update:
- /Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoVendor/docs/PAYMENT_PROVIDER_READINESS.md

Include:
- current implemented payment abstractions
- missing provider credentials/config
- callback/webhook requirements
- order validation dependency
- refund dependency
- courier payout dependency
- environment variables needed, using placeholder names only

Do not modify API code.

Verification:
- git status in sequo-api remains clean.
```

## 43. API Payment Provider Adapter Interfaces

Prompt:

```text
You are working in /Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api.

Task: add provider adapter interfaces for Yas Togo and Moov Africa without real credentials.

Read:
- src/main/kotlin/dev/orestegabo/sequo_api/domain/payment/**
- SequoVendor/docs/PAYMENT_PROVIDER_READINESS.md

Implement:
- Provider-neutral interface for:
  - create payment attempt
  - verify provider callback/webhook
  - check payment status
  - issue refund instruction if supported
  - payout instruction if supported
- Yas adapter stub using configuration placeholders.
- Moov adapter stub using configuration placeholders.
- Tests for “not configured” and safe failure behavior.

Rules:
- No real secrets.
- No fake successful payments in production profile.
- Keep existing payment tests passing.

Verification:
- ./gradlew test
```

## 44. Customer Payment UI Provider States

Prompt:

```text
You are working in /Users/muhirwagabooreste/AndroidStudioProjects/SequoService/Sequo.

Task: update checkout payment UI to reflect provider-ready states without pretending real payment is complete.

Read:
- Sequo/PAYMENTS_AND_REFERRALS.md
- Sequo/shared/src/commonMain/kotlin/dev/orestegabo/sequo/ui/basket/**
- sequo-api/domain/payment/**
- sequo-api/domain/order/PendingPaymentCheckout.kt

Implement:
- Payment method selector for supported providers if backend exposes them.
- Clear pending/validated/failed/cancelled states.
- Disable final order confirmation until payment validated.
- Safe fallback when provider is not configured.

Rules:
- Do not fake payment success.
- No cash payment path unless contract changes.
- All strings EN/FR.

Verification:
- ./gradlew :androidApp:compileDebugKotlin
```

## 45. API Settlement/Payout Mobile Visibility Endpoint

Prompt:

```text
You are working in /Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api.

Task: expose safe rider payout visibility endpoints if missing.

Read:
- src/main/kotlin/dev/orestegabo/sequo_api/domain/settlement/**
- src/main/kotlin/dev/orestegabo/sequo_api/domain/delivery/**
- SequoRider/PAYOUT_STRUCTURE.md

Implement only if missing:
- GET /api/rider/payouts/summary
- GET /api/rider/payouts/history

Rules:
- Require courier/rider role.
- Rider can only view own payout records.
- Internal salaried couriers should not see per-order freelancer payout as payable.
- Money as Int CFA.
- Add tests for role/ownership.

Verification:
- ./gradlew test
```

## 46. Wire Rider Payout Screen To API

Prompt:

```text
You are working in /Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoRider.

Task: wire rider payout screen to real payout visibility endpoint if it now exists.

Read:
- SequoRider/ui/payout/**
- SequoRider/core/payout/**
- sequo-api/domain/settlement/**

Implement:
- PayoutApiClient.
- PayoutRepository interface plus fake fallback.
- Summary/history UI refresh.
- Safe error states.

Rules:
- Do not execute real payouts.
- No raw errors.
- All money Int CFA.
- All strings EN/FR.

Verification:
- ./gradlew :androidApp:compileDebugKotlin
```

## 47. Cross-App Notification App Families Audit

Prompt:

```text
You are working in /Users/muhirwagabooreste/AndroidStudioProjects/SequoService.

Task: audit notification integration across contract apps.

Read:
- sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/notification/**
- Sequo/shared/src/commonMain/kotlin/dev/orestegabo/sequo/ui/notifications/**
- SequoVendor/shared/src/commonMain/kotlin/dev/orestegabo/sequovendor/core/notifications/**
- SequoHub/shared/src/commonMain/kotlin/dev/orestegabo/sequohub/feature/notifications/**
- SequoRider if notification code exists

Update only:
- SequoVendor/docs/NOTIFICATION_APP_FAMILY_AUDIT.md

Verify:
- Customer app family should match customer.
- Vendor uses SEQUO_MERCHANT.
- Rider uses rider/courier app family if backend supports it.
- Hub uses hub/relay app family if backend supports it.

List missing app-family support if backend does not have it.

Do not edit code in this prompt.
```

## 48. Rider Notifications Integration

Prompt:

```text
You are working in /Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoRider.

Task: implement rider notifications integration if backend app family supports it.

Read:
- SequoVendor/core/notifications/**
- sequo-api/domain/notification/**
- SequoVendor/docs/NOTIFICATION_APP_FAMILY_AUDIT.md

Implement:
- Device registration/unregistration.
- Inbox list.
- Read/archive actions.
- Preferences where API supports it.
- Categories:
  - new mission
  - route update
  - pickup problem
  - delivery problem
  - payout status if available

Rules:
- No Firebase server credentials in app.
- Use correct appFamily from API audit.
- No raw errors.
- All strings EN/FR.

Verification:
- ./gradlew :androidApp:compileDebugKotlin
```

## 49. Admin/Backend Business Parameter End-To-End Test

Prompt:

```text
You are working in /Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api.

Task: add focused tests proving Annex A.6 business parameters are configurable or prepared.

Read:
- src/test/kotlin/dev/orestegabo/sequo_api/domain/pricing/**
- src/test/kotlin/dev/orestegabo/sequo_api/domain/commission/**
- src/test/kotlin/dev/orestegabo/sequo_api/domain/subscription/**
- src/test/kotlin/dev/orestegabo/sequo_api/domain/referral/**
- src/test/kotlin/dev/orestegabo/sequo_api/domain/bargaining/**
- src/test/kotlin/dev/orestegabo/sequo_api/domain/returns/**
- src/test/kotlin/dev/orestegabo/sequo_api/domain/relay/**

Add or improve tests for:
- minimum delivery price
- price per kilometer
- commission percentages
- subscriber discounts
- referral credits
- bargaining attempts
- negotiated price lock
- return delay
- relay storage fees
- excluded relay/return categories

Rules:
- Do not refactor unrelated code.
- Keep tests focused.

Verification:
- ./gradlew test
```

## 50. Final Contract MVP Integration Audit

Prompt:

```text
You are working inside /Users/muhirwagabooreste/AndroidStudioProjects/SequoService.

Task: final contract MVP integration audit across all projects.

Read:
- sequo-api/docs/contracts/contrat_mvp_sequo.tex
- SequoVendor/docs/CONTRACT_MVP_TRACKER.md
- SequoVendor/docs/mvp-gaps/*.md if present
- API docs and project READMEs

Scan:
- sequo-api
- Sequo
- SequoVendor
- SequoRider
- SequoHub
- SequoAdmin if it exists

Update:
- SequoVendor/docs/CONTRACT_MVP_TRACKER.md

For every contract-listed item:
- mark done/partial/not done
- list exact project paths where implemented
- list remaining gap
- avoid extra features not in the contract

Then run verification where practical:
- sequo-api: ./gradlew test
- Sequo: ./gradlew :androidApp:compileDebugKotlin
- SequoVendor: ./gradlew :androidApp:compileDebugKotlin
- SequoRider: ./gradlew :androidApp:compileDebugKotlin
- SequoHub: ./gradlew :androidApp:compileDebugKotlin
- SequoAdmin: project build command if it exists

If any command fails, document:
- exact command
- failure summary
- likely responsible project/file
- next prompt/task needed

Do not edit the contract .tex file.
```
