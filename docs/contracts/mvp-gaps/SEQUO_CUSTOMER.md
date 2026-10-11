# Sequo Customer MVP Gaps

Source tracker: `/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoVendor/docs/CONTRACT_MVP_TRACKER.md`

This file lists only missing or partial contract items relevant to the customer app.

## Customer Order Creation

- Contract item: Customer creates an order.
- Current evidence/path: `/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/Sequo/ui/basket`, `/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/Sequo/logic/OrderLogic.kt`, `/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/order`.
- Missing work: Verify and finish a real customer order submission flow from basket to backend order creation, including request DTO mapping, loading/error states, and localized success/failure messages.
- Dependency: `sequo-api` order endpoints and payment validation rules.
- Suggested verification command: `cd /Users/muhirwagabooreste/AndroidStudioProjects/SequoService/Sequo && ./gradlew :androidApp:compileDebugKotlin`.

## Payment Before Order Validation

- Contract item: Payment or payment validation before final confirmation; order must be paid before validation.
- Current evidence/path: `/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/order/PendingPaymentCheckout.kt`, `/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/payment`, `/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/Sequo/PAYMENTS_AND_REFERRALS.md`.
- Missing work: Wire customer checkout to production-ready payment preparation/validation when Yas Togo and Moov Africa provider flows are ready. Until then, keep any fallback clearly marked and safe.
- Dependency: `sequo-api` payment provider integration, callbacks, and validation.
- Suggested verification command: `cd /Users/muhirwagabooreste/AndroidStudioProjects/SequoService/Sequo && ./gradlew :androidApp:compileDebugKotlin`.

## Home Delivery Or Relay Choice

- Contract item: Customer chooses home delivery or relay point.
- Current evidence/path: `/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/relay`, `/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/SequoHub`, customer delivery/relay labels in `Sequo`.
- Missing work: Finish or verify checkout delivery mode selection, relay eligibility messaging, selected relay persistence, and backend request mapping.
- Dependency: `sequo-api` relay endpoints and `SequoHub` operational readiness.
- Suggested verification command: `cd /Users/muhirwagabooreste/AndroidStudioProjects/SequoService/Sequo && ./gradlew :androidApp:compileDebugKotlin`.

## Customer Bargaining Flow

- Contract item: Customer can propose a lower price for eligible products.
- Current evidence/path: `/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/bargaining`, `/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/commerce/CommerceApi.kt`, `/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/Sequo/BARGAINING_FLOW.md`.
- Missing work: Complete customer submit/status UI, attempts-left display, locked accepted price handling, and safe localized errors.
- Dependency: `sequo-api` bargaining endpoints and `SequoVendor` seller response flow.
- Suggested verification command: `cd /Users/muhirwagabooreste/AndroidStudioProjects/SequoService/Sequo && ./gradlew :androidApp:compileDebugKotlin`.

## Customer Return Request

- Contract item: Return requests possible within 72 hours after delivery.
- Current evidence/path: `/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/returns`, `/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/Sequo/RETURNS_POLICY.md`, customer order return labels in `Sequo`.
- Missing work: Implement or finish customer return request UI, eligibility state, reason entry, relay drop-off option, backend API client, and confirmation screen.
- Dependency: `sequo-api` return endpoints and `SequoHub` return drop-off flow.
- Suggested verification command: `cd /Users/muhirwagabooreste/AndroidStudioProjects/SequoService/Sequo && ./gradlew :androidApp:compileDebugKotlin`.

## Return And Refund Status Tracking

- Contract item: Refund after SEQUO receives and checks product condition; refund instruction within a reasonable delay.
- Current evidence/path: `/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/returns`, `/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/Sequo/logic/OrderLogic.kt`.
- Missing work: Show backend-connected return/refund status in customer order detail without raw backend errors.
- Dependency: `sequo-api` returns/refunds status exposure and payment provider refund execution.
- Suggested verification command: `cd /Users/muhirwagabooreste/AndroidStudioProjects/SequoService/Sequo && ./gradlew :androidApp:compileDebugKotlin`.

