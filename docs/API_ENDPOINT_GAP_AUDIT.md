# SEQUO API Endpoint Gap Audit

Source of truth:

- Current implemented mobile guide: `/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/MOBILE_API_GUIDE.md`
- Current controllers: `/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain`
- Contract tracker: `/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/docs/contracts/CONTRACT_MVP_TRACKER.md`

Important finding: use `/api`, not `/api/v1`, for current mobile work. `API_SPEC.md` contains target/future shapes and still references `/api/v1`, so it must not be treated as implemented endpoint truth.

## Customer Checkout: Home Delivery Vs Relay

Available implemented endpoints:

- `POST /api/orders/process`: validates or records pending payment checkout and creates fulfillment records after payment validation.
- `GET /api/orders`: lists authenticated customer orders.
- `GET /api/orders/{orderId}`: reads one authenticated customer order.
- `GET /api/delivery/tracking/{deliveryCode}?orderId={orderId}`: reads proof-redacted delivery tracking.
- `GET /api/consolidations/order/{orderId}`: reads customer consolidation manifest by order.
- `GET /api/relay/parcels`, `GET /api/relay/parcels/{parcelId}`: relay parcel reads for relay/admin scope, not customer checkout selection.

DTOs/source found:

- `OrderProcessingRequest` in `MOBILE_API_GUIDE.md`, with `route` values including `FastDelivery`, `GroupedSequo`, `Pickup`, and `PointDeRelai`.
- Order line fields include `productId`, `sellerId`, `quantity`, `unitPriceCfa`, optional `negotiatedUnitPriceCfa`, and `photoEvidence`.

Missing or not mobile-ready:

- No customer-facing relay-point discovery/selection endpoint was confirmed for checkout.
- No explicit customer checkout route/relay selection DTO was found beyond `OrderProcessingRequest.route = PointDeRelai`.
- No customer-facing relay eligibility preview endpoint was confirmed.

Recommended app behavior until backend is ready:

- Let the customer app use `POST /api/orders/process` only for currently supported routes.
- Keep relay choice behind repository/API capability checks if relay point selection cannot be backed by implemented endpoints.
- Do not call hub/relay operational endpoints from the customer app for checkout selection.

## Customer Returns

Available implemented endpoints:

- `POST /api/returns`: customer or admin creates a return request.
- `GET /api/returns`: customer lists own returns; operators can list by status/customer.
- `GET /api/returns/{returnId}`: reads a return with ownership checks.
- `GET /api/returns/orders/{orderId}`: admin/operator lists returns for an order.
- `POST /api/returns/{returnId}/relay-dropoff`: relay operator records customer return drop-off.
- `POST /api/returns/{returnId}/physical-receipt`: SEQUO/admin confirms physical receipt.
- `POST /api/returns/{returnId}/refund`: finance/admin triggers refund.

DTOs/source found:

- `ReturnController.CreateReturnRequest`: `returnId`, `orderId`, `customerId`, `reason`, `requestedRefundCfa`, `rawReturnPin`, `requestedAt`, `productReturnable`, `merchantAllowsReturn`, `adminOverride`.
- `ReturnController.RelayDropoffRequest`: `relayPointId`, `rawReturnPin`, `droppedAt`.
- `ReturnController.PhysicalReceiptRequest`: `receivedAt`, `conditionAssessment`, `responsibility`, `idempotencyKey`.
- `ReturnController.RefundRequest`: `amountCfa`, `idempotencyKey`.

Missing or not mobile-ready:

- Customer app still needs a safe UI/API client flow that creates `returnId` and handles `rawReturnPin` without exposing sensitive values unnecessarily.
- Relay drop-off is implemented for relay operators, but the customer app should not call it.
- Refund execution depends on admin/finance and payment provider readiness.

Recommended app behavior until backend is ready:

- Customer app can create/list/read returns through `/api/returns` if the app owns the user flow and stores only safe display fields.
- Hub app should handle `relay-dropoff`.
- Admin/finance app should handle receipt/refund actions.

## Merchant Catalog Create/Update/Deactivate/Upload

Available implemented endpoints:

- `GET /api/catalog/home`
- `GET /api/catalog/categories`
- `GET /api/catalog/merchants`
- `GET /api/catalog/products?category=&subcategory=&merchantId=&includeArchived=`
- `GET /api/catalog/products/{productId}/related?limit=`
- `POST /api/catalog/products`
- `PATCH /api/catalog/products/{productId}`
- `POST /api/catalog/products/{productId}/archive`
- `GET /api/catalog/products/{productId}/customizations`
- `PUT /api/catalog/products/{productId}/customizations`

DTOs/source found:

- `CatalogProductDto`
- `CatalogCategoryDto`
- `MerchantStorefrontDto`
- `ManageCatalogProductRequest`
- `PatchCatalogProductRequest`
- Food/customization DTOs in `CommerceApi.kt` and `PlatformCommerceApi.kt`.

Missing or not mobile-ready:

- The write routes are generic catalog routes, not confirmed as merchant-scoped seller-management routes with owner checks.
- No multipart or binary product photo upload endpoint is currently exposed; `MOBILE_API_GUIDE.md` explicitly says multipart upload is not currently exposed.
- No seller camera-capture verification upload endpoint was confirmed.
- No dedicated product bargaining enable/disable endpoint was confirmed.

Recommended app behavior until backend is ready:

- Vendor app may read products with `GET /api/catalog/products?merchantId={merchantId}`.
- Vendor app should keep product editor writes behind a repository interface and local/mock draft storage.
- Do not wire vendor production create/update/archive/photo upload to generic routes until merchant ownership, media policy, and upload contracts are explicit.

## Merchant Bargaining Enable/Disable And Seller Responses

Available implemented endpoints:

- `GET /api/bargaining/threads`
- `POST /api/bargaining/threads`
- `POST /api/bargaining/threads/{threadId}/offers`

DTOs/source found:

- `CreateBargainingThreadRequest`: `productId`, `amountCfa`, `message?`.
- `CreateBargainingOfferRequest`: `amountCfa?`, `message?`.
- `BargainingThreadDto`
- `BargainingOfferDto`
- Enums: `BargainingThreadStatus`, `BargainingActorType`, `BargainingOfferType`.

Missing or not mobile-ready:

- No dedicated seller response endpoint was confirmed for merchant accept/reject/counter actions.
- No product-level merchant bargaining enable/disable endpoint was confirmed.
- No merchant inbox/filter endpoint for bargaining threads was confirmed beyond generic authenticated thread reads.

Recommended app behavior until backend is ready:

- Customer app can build only the parts safely supported by implemented bargaining thread/offer routes.
- Vendor app should not expose seller negotiation actions until seller-scoped routes and role checks are available.
- Product editor bargaining toggle should remain hidden/disabled or local-only.

## Rider Missions, History And Payout

Available implemented endpoints:

- `GET /api/delivery/missions?courierId=&status=`
- `GET /api/delivery/missions/{missionId}`
- `POST /api/delivery/missions/{missionId}/accept`
- `POST /api/delivery/missions/{missionId}/pickup`
- `POST /api/delivery/missions/{missionId}/deliver`
- `POST /api/delivery/missions/{missionId}/relay-deposit`
- `POST /api/delivery/missions/{missionId}/problem`
- `POST /api/delivery/missions/{missionId}/relay-release` for relay operators.
- Admin-only mission endpoints: create, dispatch-ready, expire-stale, pause/unpause courier, availability read, assign, reassign, offer, delivery-pin, cancel, force-problem, resolve-problem, problem-resolutions.
- Settlement endpoints: `GET /api/settlements/merchant-payouts`, `GET /api/settlements/ledger`, `POST /api/settlements/merchant-payouts/evaluate-eligible`.

DTOs/source found:

- `DeliveryMissionController.ProofRequest`: `proofMetadata?`, `deliveryPin?`, `idempotencyKey?`.
- `DeliveryMissionController.ProblemRequest`: `reason`, `idempotencyKey?`.
- `DeliveryMissionController.RelayReleaseRequest`: `pickupCodeValidated`, `identityValidated`, `proofMetadata?`, `idempotencyKey?`.
- Mission states in `MOBILE_API_GUIDE.md`: `CREATED`, `OFFERED_TO_COURIER`, `ACCEPTED_BY_COURIER`, `PICKED_UP_FROM_SELLER`, `DEPOSITED_AT_RELAY`, `DELIVERED_TO_CUSTOMER`, `RELEASED_BY_RELAY`, `PROBLEM_REPORTED`, `CANCELLED`.

Missing or not mobile-ready:

- No explicit rider mission history endpoint was confirmed separate from `GET /api/delivery/missions` with filters.
- No rider-specific payout/earnings endpoint was confirmed; available settlement endpoints appear merchant/admin oriented.
- Courier availability write endpoints are admin-only, so a rider self-availability endpoint is not confirmed.

Recommended app behavior until backend is ready:

- Rider app can use mission list/detail/actions already implemented, with strict role handling and idempotency keys for pickup/deliver/deposit/problem.
- Keep history as filtered local/API mission list if supported by status filters.
- Keep payout visibility behind a repository interface until a courier payout endpoint exists.

## Hub Scan, Receive, Handover, Returns And Collection Manifest

Available implemented endpoints:

- `POST /api/hub/scan/resolve`
- `GET /api/hub/summary?hubId=`
- `POST /api/hub/lockers/{lockerId}/availability`
- `GET /api/hub/opening-hours?relayPointId=`
- `PUT /api/hub/opening-hours`
- `GET /api/hub/control-state?relayPointId=`
- `POST /api/hub/control-state`
- `GET /api/hub/control-state/history?relayPointId=`
- `GET /api/relay/parcels?relayPointId=&status=`
- `GET /api/relay/parcels/{parcelId}`
- `POST /api/relay/parcels`
- `POST /api/relay/parcels/{parcelId}/pickup-code`
- `POST /api/relay/parcels/{parcelId}/release`
- `POST /api/relay/parcels/{parcelId}/problem`
- `POST /api/relay/parcels/{parcelId}/return-to-seller`
- `POST /api/relay/parcels/storage-fees/assess`
- `GET /api/relay/parcels/storage-fees?relayPointId=`
- `POST /api/returns/{returnId}/relay-dropoff`

DTOs/source found:

- `HubMobileController.ScanResolveRequest`: `hubId`, `credential`, `credentialType`, `idempotencyKey?`.
- `HubMobileController.LockerAvailabilityRequest`: `relayPointId`, `status`, `reason?`, `expectedAvailableAt?`, `idempotencyKey?`.
- `HubMobileController.SaveOpeningHoursRequest`: `relayPointId`, `timezone`, `weeklyHours`, `exceptions`, `idempotencyKey?`.
- `HubMobileController.ApplyControlStateRequest`: relay/service control fields and `idempotencyKey`.
- Relay parcel controller request DTOs for creation, pickup-code, release, problem, return-to-seller, storage-fee assessment.

Missing or not mobile-ready:

- No explicit collection manifest endpoint for hub-to-SEQUO collection batches was confirmed beyond scan resolution and relay/return operations.
- Hub app still needs exact UI workflow mapping from `scan/resolve.workflowType` to receive/handover/return screens.

Recommended app behavior until backend is ready:

- Hub app should use `scan/resolve` as the primary entry point for QR/code flows.
- Use relay parcel and returns endpoints for concrete implemented actions.
- Keep collection manifest screens behind repository interfaces if no collection-batch endpoint exists.

## Admin Web Needs

Available implemented endpoints:

- `GET /api/admin/monitoring/operations`
- `GET /api/commissions/merchant-overrides/{merchantId}`
- `PUT /api/commissions/merchant-overrides/{merchantId}`
- `DELETE /api/commissions/merchant-overrides/{merchantId}`
- Delivery mission admin endpoints under `/api/delivery/missions`
- Relay parcel admin endpoints under `/api/relay/parcels`
- Return admin endpoints under `/api/returns`
- Settlement endpoints under `/api/settlements`
- Notification inbox/device/preference endpoints under `/api/notifications`

DTOs/source found:

- Admin monitoring models in `AdminMonitoring.kt`.
- Commission override request/response models in `MerchantCommissionController.kt`.
- Delivery, relay, return, settlement, and notification DTOs in their controllers/services.

Missing or not mobile-ready:

- The admin web app still needs a broad API-backed surface for users/roles, merchant activation, product moderation, operational safe actions, business parameter settings, and audit logs.
- Some admin needs may exist as domain services but not complete web-ready endpoints.

Recommended app behavior until backend is ready:

- `sequo-admin` should start with implemented reads/actions only and clearly disable routes that are not backed by controllers.
- Do not rely on `API_SPEC.md` future `/api/v1` routes until matching controllers exist.

## Payment Validation, Refunds And Payouts

Available implemented endpoints:

- `POST /api/orders/process`: validates payment or records pending provider checkout.
- `GET /api/payments/providers`
- `POST /api/payments/webhooks/{provider}`: provider-to-server route for Yas Togo and Moov Africa webhook events.
- `POST /api/returns/{returnId}/refund`: finance/admin refund trigger after receipt.
- `GET /api/settlements/merchant-payouts`
- `GET /api/settlements/ledger`
- `POST /api/settlements/merchant-payouts/evaluate-eligible`

DTOs/source found:

- `OrderProcessingRequest` payment fields: `paymentProvider.value`, `paymentReference`, amount is derived from order/pricing.
- `PaymentWebhookCommand` fields: `provider`, `eventId`, `checkoutId`, `paymentReference`, `providerReference?`, `amountCfa`, `status`, `occurredAt`, timestamp/signature metadata.
- `ReturnController.RefundRequest`: `amountCfa`, `idempotencyKey`.

Missing or not mobile-ready:

- No mobile payment-intent creation endpoint was confirmed; mobile checkout currently submits provider reference through `/api/orders/process`.
- Real provider initiation/adapters/credentials remain a current limitation in `MOBILE_API_GUIDE.md`.
- Courier payout endpoints are not confirmed; settlement endpoints appear merchant/admin oriented.

Recommended app behavior until backend is ready:

- Customer app should handle `200 OK` as paid/validated and `202 Accepted` as payment-processing/pending for the same `checkoutId`.
- Mobile apps must never call provider webhook routes.
- Rider payout UI should remain read-only/mock behind a repository until courier payout endpoints exist.

## Cross-Cutting API Rules For Apps

- Use `/api`, not `/api/v1`.
- Send `Content-Type: application/json` for request bodies.
- Use `Authorization: Bearer <accessToken>` on protected routes.
- Keep money as integer CFA.
- Use ISO-8601 timestamps.
- Honor `Retry-After` on `429`.
- Refresh once and retry once on `401`.
- Reuse idempotency keys for retried mutating workflow actions.
- Do not log access tokens, refresh tokens, OAuth tokens, payment tokens, FCM tokens, PINs, pickup codes, QR secrets, PII, webhook signatures, or backend secrets.
- Do not display raw backend stack traces or debug messages in any UI.

